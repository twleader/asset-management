#!/usr/bin/env python3
"""Real isolated Nginx regression: parallel large SRPP packages under 16 MiB /tmp.

Requires the local nginx:alpine and python:3-alpine images. No host ports,
production services, credentials, compose project or financial data are used.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
ROUTES = ("/api/public/srpp/report-calculations", "/api/public/srpp/us-report-calculations")

UPSTREAM = r'''
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        consumer = parse_qs(urlparse(self.path).query).get("consumer", ["Codex"])[0]
        body = json.dumps({"consumer": consumer, "fixture": "SYNTHETIC_ONLY", "payload": "x" * (12*1024*1024)}, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "private, no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
    def log_message(self, *args):
        pass
ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
'''

CLIENT = r'''
import concurrent.futures
import hashlib
import json
import socket
import sys
import threading
import time

host, route = sys.argv[1:]
barrier = threading.Barrier(2)
def fetch(consumer):
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 32768)
    sock.settimeout(25)
    sock.connect((host, 9090))
    barrier.wait(timeout=10)
    sock.sendall(("GET " + route + "?consumer=" + consumer + " HTTP/1.1\r\nHost: gateway\r\nConnection: close\r\n\r\n").encode())
    # Both upstream responses arrive while the clients intentionally withhold reads.
    # Buffering-on spills to /tmp; buffering-off instead applies socket backpressure.
    time.sleep(4)
    raw = bytearray()
    while True:
        chunk = sock.recv(262144)
        if not chunk:
            break
        raw.extend(chunk)
    sock.close()
    head, body = bytes(raw).split(b"\r\n\r\n", 1)
    lines = head.decode().split("\r\n")
    headers = dict(line.lower().split(": ", 1) for line in lines[1:] if ": " in line)
    expected = json.dumps({"consumer": consumer, "fixture": "SYNTHETIC_ONLY", "payload": "x" * (12*1024*1024)}, separators=(",", ":")).encode()
    valid_json = False
    try:
        value = json.loads(body)
        valid_json = value["consumer"] == consumer and value["fixture"] == "SYNTHETIC_ONLY"
    except (ValueError, KeyError):
        pass
    return {"consumer": consumer, "status": int(lines[0].split()[1]),
            "contentLength": int(headers.get("content-length", -1)), "receivedBytes": len(body),
            "expectedBytes": len(expected), "sha256": hashlib.sha256(body).hexdigest(),
            "expectedSha256": hashlib.sha256(expected).hexdigest(), "validJson": valid_json,
            "cacheControl": headers.get("cache-control"),
            "complete": body == expected and len(body) == int(headers.get("content-length", -1)) and valid_json}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(fetch, ["Codex", "Claude"]))
print(json.dumps(results))
'''

MATRIX = r'''
import http.client
import json
import sys
host, route = sys.argv[1:]
results=[]
for method, path, status in [("POST",route,405),("PUT",route,405),("DELETE",route,405),("GET",route+"/child",404)]:
    client=http.client.HTTPConnection(host,9090,timeout=5)
    client.request(method,path)
    response=client.getresponse();response.read()
    assert response.status==status,(method,path,response.status)
    if status==405: assert response.getheader("Allow")=="GET"
    results.append({"method":method,"path":path,"status":response.status})
    client.close()
print(json.dumps(results))
'''


def command(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, capture_output=True, check=check, timeout=120)


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return command("docker", *args, check=check)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Directory for synthetic transport evidence")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for image in ("nginx:alpine", "python:3-alpine"):
        docker("image", "inspect", image)
    config = (ROOT / "api-gateway/nginx.conf").read_text()
    # Build a causal control by reverting only the two route-local buffering switches.
    baseline = config
    for route in ROUTES:
        pattern = re.compile(r"(location = " + re.escape(route) + r" \{)(.*?)(\n        \})", re.S)
        match = pattern.search(baseline)
        if not match or match.group(2).count("proxy_buffering off;") != 1:
            raise AssertionError(f"Missing single route-local streaming switch: {route}")
        baseline = baseline[:match.start(2)] + match.group(2).replace("proxy_buffering off;", "proxy_buffering on;") + baseline[match.end(2):]
    identifier = "srpp-stream-test-" + uuid.uuid4().hex[:10]
    network, upstream = identifier + "-net", identifier + "-upstream"
    containers: list[str] = []
    evidence = {"fixture": "SYNTHETIC_ONLY", "tmpfsBytes": 16*1024*1024, "hostPorts": [], "runs": []}
    try:
        docker("network", "create", "--internal", network)
        with tempfile.TemporaryDirectory(prefix=identifier) as scratch:
            work = Path(scratch)
            (work / "upstream.py").write_text(UPSTREAM)
            (work / "client.py").write_text(CLIENT)
            (work / "matrix.py").write_text(MATRIX)
            docker("run", "-d", "--name", upstream, "--network", network, "--network-alias", "bff",
                   "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                   "-v", f"{work}:/fixture:ro", "python:3-alpine", "python", "-u", "/fixture/upstream.py")
            containers.append(upstream)
            for mode, text in (("buffered-control", baseline), ("streaming", config)):
                for number, route in enumerate(ROUTES):
                    gateway = identifier + "-" + mode + "-" + str(number)
                    cfg = work / (gateway + ".conf");cfg.write_text(text)
                    logdir = work / (gateway + "-logs");logdir.mkdir();logdir.chmod(0o777)
                    docker("run", "-d", "--name", gateway, "--network", network,
                           "--read-only", "--user", "nginx", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                           "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                           "-v", f"{cfg}:/etc/nginx/nginx.conf:ro", "-v", f"{logdir}:/var/log/nginx-shared",
                           "--entrypoint", "nginx", "nginx:alpine", "-g", "daemon off;")
                    containers.append(gateway)
                    docker("exec", upstream, "python", "-c",
                           "import socket,time,sys\nfor attempt in range(100):\n try:\n  s=socket.create_connection((sys.argv[1],9090),.2);s.close();break\n except OSError:time.sleep(.05)\nelse:raise RuntimeError('gateway unavailable')", gateway)
                    received = json.loads(docker("exec", upstream, "python", "/fixture/client.py", gateway, route).stdout)
                    matrix = json.loads(docker("exec", upstream, "python", "/fixture/matrix.py", gateway, route).stdout)
                    errors = docker("logs", gateway).stderr
                    (output / (mode + "-" + str(number) + "-error.log")).write_text(errors)
                    capacity_error = bool(re.search(r"No space left on device|written only \d+ of \d+", errors))
                    row = {"mode":mode,"route":route,"responses":received,"matrix":matrix,"tempCapacityError":capacity_error}
                    evidence["runs"].append(row)
                    (output / "result.json").write_text(json.dumps(evidence,indent=2)+"\n")
                    if mode == "buffered-control":
                        assert capacity_error and any(not item["complete"] for item in received), row
                    else:
                        assert not capacity_error and all(item["complete"] and item["status"]==200 and item["cacheControl"]=="private, no-store" for item in received),row
                    docker("rm", "-f", gateway);containers.remove(gateway)
            evidence["status"]="PASS"
            (output / "result.json").write_text(json.dumps(evidence,indent=2)+"\n")
            print(json.dumps({"status":"PASS","runs":len(evidence["runs"]),"evidence":str(output / "result.json")}))
    finally:
        for container in reversed(containers):
            docker("rm", "-f", container, check=False)
        docker("network", "rm", network, check=False)


if __name__ == "__main__":
    main()
