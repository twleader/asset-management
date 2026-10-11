#!/usr/bin/env python3
"""獨立有界 sender；不讀來源／金融API，durable ACK核對後才移除pending。"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import importlib.util
import json
import os
import pathlib
import re
import ssl
import signal
import stat
import sys
import time
import threading
import urllib.parse

# 背景服務只寫自身state，不在正式main checkout產生import cache。
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("ai_flow_collector", pathlib.Path(__file__).with_name("collect-srpp-ai-flow.py"))
collector = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = collector
spec.loader.exec_module(collector)


def read_token(path: pathlib.Path) -> str:
    path = path.expanduser().absolute()
    current = pathlib.Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if current.is_symlink():
            raise collector.ObservationError("TOKEN_PERMISSIONS")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        metadata = os.fstat(fd)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o600:
            raise collector.ObservationError("TOKEN_PERMISSIONS")
        raw = os.read(fd, 4097)
    finally:
        os.close(fd)
    if len(raw) > 4096:
        raise collector.ObservationError("TOKEN_UNAVAILABLE")
    try:
        token = raw.decode("ascii").strip()
    except UnicodeError as exc:
        raise collector.ObservationError("TOKEN_UNAVAILABLE") from exc
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,512}", token):
        raise collector.ObservationError("TOKEN_UNAVAILABLE")
    return token


def endpoint_parts(endpoint: str):
    try:
        parsed = urllib.parse.urlsplit(endpoint)
        port = parsed.port
    except (TypeError, ValueError) as exc:
        raise collector.ObservationError("INVALID_ENDPOINT") from exc
    if parsed.path != "/api/srpp/ai-flow/events" or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise collector.ObservationError("INVALID_ENDPOINT")
    if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"} and port == 9090:
        return parsed
    if parsed.scheme == "https" and parsed.hostname and port == 9090:
        return parsed
    raise collector.ObservationError("INVALID_ENDPOINT")


def post(endpoint: str, token: str, body: bytes):
    if threading.current_thread() is not threading.main_thread():
        raise collector.ObservationError("SENDER_THREAD_UNSUPPORTED")
    parsed = endpoint_parts(endpoint)
    connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    kwargs = {"timeout": 2}
    if parsed.scheme == "https":
        kwargs["context"] = ssl.create_default_context()
    connection = connection_type(parsed.hostname, parsed.port, **kwargs)
    # socket timeout限制閒置；絕對timer另防慢速header/body每隔1.9秒滴入而無限延長。
    started = time.monotonic()
    old_handler = signal.getsignal(signal.SIGALRM)
    old_timer = signal.getitimer(signal.ITIMER_REAL)
    def deadline(_signum, _frame):
        raise TimeoutError("UPLOAD_DEADLINE")
    signal.signal(signal.SIGALRM, deadline)
    signal.setitimer(signal.ITIMER_REAL, min(2, old_timer[0]) if old_timer[0] > 0 else 2)
    try:
        # 不followredirect、不讀proxy環境，憑證只送往使用者指定的exact觀測API。
        connection.request("POST", parsed.path, body=body, headers={"Content-Type": "application/json", "X-AI-Flow-Token": token})
        response = connection.getresponse()
        raw = response.read(256 * 1024 + 1)
        if len(raw) > 256 * 1024:
            raise collector.ObservationError("INVALID_ACK")
        return response.status, raw
    finally:
        connection.close()
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old_handler)
        if old_timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL, max(0.000001, old_timer[0] - (time.monotonic() - started)), old_timer[1])


def validate_ack(envelope: dict, status: int, raw: bytes):
    if status not in {200, 201}:
        raise collector.ObservationError("UPLOAD_REJECTED_" + str(status))
    ack = collector.strict_json(raw)
    ids = {item["eventId"] for item in envelope["events"]}
    if not isinstance(ack, dict) or set(ack) != {"runId", "inserted", "replayed", "eventIds"} or ack["runId"] != envelope["run"]["runId"]:
        raise collector.ObservationError("INVALID_ACK")
    if any(isinstance(ack[key], bool) or not isinstance(ack[key], int) or ack[key] < 0 for key in ("inserted", "replayed")):
        raise collector.ObservationError("INVALID_ACK")
    if not isinstance(ack["eventIds"], list) or not all(isinstance(item, str) for item in ack["eventIds"]) or len(ack["eventIds"]) != len(ids) or set(ack["eventIds"]) != ids or ack["inserted"] + ack["replayed"] != len(ids):
        raise collector.ObservationError("INVALID_ACK")
    if status == 200 and ack["inserted"] != 0 or status == 201 and ack["inserted"] == 0:
        raise collector.ObservationError("INVALID_ACK")
    return ack


def send_round(store, endpoint: str, token_file: pathlib.Path, *, transport=post, max_batches=3, seconds=5):
    start = time.monotonic()
    sent, inserted, replayed, failures = 0, 0, 0, {}
    purged = store.purge()
    endpoint_parts(endpoint)
    token = read_token(token_file)
    for path in sorted((store.root / "pending").glob("*.json")):
        # 保守保留完整2秒deadline，不能在剩餘<2秒時啟動下一request而拖過round。
        if sent >= max_batches or seconds - (time.monotonic() - start) < 2:
            break
        try:
            with collector.open_readonly(path, store.root) as stream:
                body = stream.read(256 * 1024 + 1)
            if len(body) > 256 * 1024:
                raise collector.ObservationError("BODY_TOO_LARGE")
            envelope = collector.strict_json(body)
            collector.validate_envelope(envelope, store.today)
            if seconds - (time.monotonic() - start) < 2:
                break
            status, raw = transport(endpoint, token, body)
            ack = validate_ack(envelope, status, raw)
            run = envelope["run"]
            acknowledged = store.ack_ids(run) | set(ack["eventIds"])
            collector.atomic_json(store.root / "acks" / (run["runId"] + ".json"),
                                  {"reportDate": run["reportDate"], "runId": run["runId"], "eventIds": sorted(acknowledged)})
            path.unlink()
            sent += 1
            inserted += ack["inserted"]
            replayed += ack["replayed"]
        except (OSError, collector.ObservationError, http.client.HTTPException, TimeoutError) as exc:
            code = str(exc) if isinstance(exc, collector.ObservationError) else "UPLOAD_UNAVAILABLE"
            failures[code] = failures.get(code, 0) + 1
            # 後續batch可能依賴此batch的parent；失敗就保留全部pending至下一輪。
            break
    receipt = {"schemaVersion": 1, "mode": "SENDER", "ackedBatches": sent, "insertedEvents": inserted,
               "replayedEvents": replayed, "pendingBatches": sum(1 for _ in (store.root / "pending").glob("*.json")),
               "purgedFiles": purged, "wallMillis": round((time.monotonic() - start) * 1000, 3), "errors": failures}
    collector.atomic_json(store.root / "sender-receipt.json", receipt)
    return receipt


def cli(argv=None):
    parser = argparse.ArgumentParser(description="獨立上傳AI觀測spool，每輪最多3批，單次網路timeout2秒。")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--endpoint", default=collector.DEFAULT_ENDPOINT)
    parser.add_argument("--token-file", type=pathlib.Path, default=collector.default_state() / "collector.token")
    parser.add_argument("--state-dir", type=pathlib.Path, default=collector.default_state())
    args = parser.parse_args(argv)
    try:
        today = dt.datetime.now(collector.TAIPEI).date()
        store = collector.Store(args.state_dir, today)
        receipt = send_round(store, args.endpoint, args.token_file)
        print(json.dumps(receipt, ensure_ascii=False))
        return 0 if not receipt["errors"] else 1
    except (OSError, collector.ObservationError) as exc:
        code = str(exc) if isinstance(exc, collector.ObservationError) else "SENDER_IO_UNAVAILABLE"
        print(json.dumps({"mode": "SENDER_UNAVAILABLE", "code": code}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(cli())
