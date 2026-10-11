#!/usr/bin/env python3
"""只追加 AI 觀測 exact Serve path；不探測金融 API、不 reset Serve。"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PATH = "/api/srpp/ai-flow/events"
LOCAL = "http://127.0.0.1:9090"
LEGACY = {
    "/api/quotes", "/api/quotes/one", "/api/public/market-index", "/api/assets/latest",
    "/api/public/exchange-rate/usd-twd", "/api/public/crawler-data/rescan",
    "/api/public/market-analysis/today", "/api/public/portfolio-advice/latest",
    "/api/public/trading-radar/today", "/api/public/trading-radar/stock",
    "/api/public/transactions", "/api/public/trading-calendar", "/api/public/commodity-prices",
    "/api/public/srpp/daily-context", "/api/public/srpp/calculation-context", "/api/public/srpp/calculations",
    "/api/public/srpp/market-facts", "/api/public/srpp/completed-technicals", "/api/public/srpp/technical-series",
    "/api/public/srpp/report-calculations", "/api/public/srpp/event-evidence/capture",
    "/api/public/srpp/daily-decision/evaluate", "/api/srpp/daily-report-mail",
}

def run(args):
    # Tailscale 的 help 寫到 stderr；只對 exact help 指令合併兩個輸出。
    # status JSON 與 curl status 必須保持 stdout 純淨，不能混入診斷文字。
    if len(args) == 3 and args[1:] == ["serve", "--help"]:
        return subprocess.check_output(args, text=True, timeout=15, stderr=subprocess.STDOUT)
    return subprocess.check_output(args, text=True, timeout=15)

def owned(data, dns):
    if set(data) - {"TCP", "Web", "Services", "AllowFunnel", "Foreground"}:
        raise ValueError("存在未知 Serve 設定欄位")
    if any(data.get(k) for k in ("Services", "AllowFunnel", "Foreground")):
        raise ValueError("存在非本功能或 Funnel 設定")
    if data.get("TCP") != {"9090": {"HTTPS": True}}:
        raise ValueError("TCP 並非僅 HTTPS 9090")
    if not isinstance(data.get("Web"), dict) or set(data["Web"]) != {dns + ":9090"}:
        raise ValueError("Web host/port 不屬既有 managed 9090")
    host = data["Web"][dns + ":9090"]
    if set(host) != {"Handlers"} or not isinstance(host["Handlers"], dict):
        raise ValueError("Web host 的欄位不是既有 Handlers")
    handlers = host["Handlers"]
    if set(handlers) not in (LEGACY, LEGACY | {PATH}):
        raise ValueError("managed paths 不符；missing=" + repr(sorted(LEGACY - set(handlers))) + " extra=" + repr(sorted(set(handlers) - LEGACY - {PATH})))
    for path, handler in handlers.items():
        if handler != {"Proxy": LOCAL + path}:
            raise ValueError("handler proxy 不屬既有 managed gateway: " + path)
    return handlers

def probe(base):
    # TLS 憑證由 curl 預設驗證；絕不 -k。只呼叫 AI 觀測 path。
    with tempfile.TemporaryDirectory(prefix="ai-flow-serve-") as temporary:
        for method, suffix, expected in (("POST", "", 401), ("GET", "", 405), ("GET", "/child", 404)):
            body = Path(temporary) / "response.json"
            headers = Path(temporary) / "headers"
            args = ["curl", "--silent", "--show-error", "--max-time", "5", "--request", method,
                    "--output", str(body), "--dump-header", str(headers), "--write-out", "%{http_code}"]
            if method == "POST":
                args += ["--header", "Content-Type: application/json", "--data", "{}"]
            status = run(args + [base + PATH + suffix]).strip()
            if status != str(expected):
                raise ValueError(f"觀測 endpoint {method} {suffix or '/'} 預期{expected}，實際{status}")
            if method == "POST":
                problem = json.loads(body.read_text())
                if problem.get("code") != "TOKEN_REQUIRED" or problem.get("status") != 401:
                    raise ValueError("匿名觀測 POST 沒有回 sanitized TOKEN_REQUIRED")
            if expected == 405 and "allow: post" not in headers.read_text().lower():
                raise ValueError("觀測 GET 缺 Allow: POST")

def main():
    cli = shutil.which("tailscale")
    if not cli:
        cli = next((str(p) for p in (Path("/Applications/Tailscale.app/Contents/MacOS/Tailscale"), Path("/Applications/Tailscale.app/Contents/MacOS/tailscale")) if p.is_file()), None)
    if not cli:
        raise ValueError("找不到 Tailscale CLI")
    if "--set-path" not in run([cli, "serve", "--help"]):
        raise ValueError("CLI 不支援 --set-path")
    state = json.loads(run([cli, "status", "--json"]))
    dns = state.get("Self", {}).get("DNSName", "").rstrip(".")
    if state.get("BackendState") != "Running" or state.get("Self", {}).get("Online") is not True or not dns:
        raise ValueError("Tailscale 未 Running/Online")
    before = json.loads(run([cli, "serve", "status", "--json"]))
    handlers = owned(before, dns)
    probe(LOCAL)
    recheck = json.loads(run([cli, "serve", "status", "--json"]))
    owned(recheck, dns)
    if before != recheck:
        raise ValueError("preflight 期間 Serve config 已改變；拒絕更新")
    expected = json.loads(json.dumps(before))
    if PATH not in handlers:
        subprocess.check_call([cli, "serve", "--bg", "--yes", "--https=9090", "--set-path=" + PATH, LOCAL + PATH], timeout=15)
        expected["Web"][dns + ":9090"]["Handlers"][PATH] = {"Proxy": LOCAL + PATH}
    after = json.loads(run([cli, "serve", "status", "--json"]))
    owned(after, dns)
    if expected != after:
        raise ValueError("更新後存在非本條觀測 route 的 config 差異；不自動 reset")
    probe("https://" + dns + ":9090")
    print("PASS: AI 觀測 Serve route 已驗證，既有23 handlers保持原值；HTTPS憑證/401/405/404通過")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.SubprocessError, OSError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error) + "；未 reset 或刪除 Serve 設定。", file=sys.stderr)
        sys.exit(1)
