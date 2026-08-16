#!/usr/bin/env bash
set -Eeuo pipefail

readonly LOCAL_BASE='http://127.0.0.1:9090'
readonly -a SERVE_PATHS=(
  '/api/quotes'
  '/api/quotes/one'
  '/api/public/market-index'
  '/api/assets/latest'
  '/api/public/exchange-rate/usd-twd'
  '/api/public/crawler-data/rescan'
  '/api/public/market-analysis/today'
  '/api/public/portfolio-advice/latest'
)

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

get_200() {
  local url=$1
  local output_file=$2
  local label=$3
  local headers_file=$4
  local -a curl_args=(-sS -D "$headers_file" -o "$output_file" -w '%{http_code}')
  local status
  if ! status="$(curl "${curl_args[@]}" "$url")"; then
    die "$label transport 失敗；不會 reset Serve。"
  fi
  [[ "$status" == 200 ]] || die "$label 必須回 HTTP 200，實際為 ${status}；不會 reset Serve。"
  grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' "$headers_file" || \
    die "$label Content-Type 不是 application/json；不會 reset Serve。"
}

# 第六條路由（POST /api/public/crawler-data/rescan）語意是「立即抓取並匯出」的免登入版本，
# 對 GET 的正確回應是 405，不是 200——刻意不沿用 get_200()，且全程不對本路徑發送任何 POST，
# 否則每次執行本腳本都會真的觸發一輪對外爬蟲抓取。
get_405_post_only() {
  local url=$1
  local output_file=$2
  local label=$3
  local headers_file=$4
  local -a curl_args=(-sS -D "$headers_file" -o "$output_file" -w '%{http_code}')
  local status
  if ! status="$(curl "${curl_args[@]}" "$url")"; then
    die "$label transport 失敗；不會 reset Serve。"
  fi
  [[ "$status" == 405 ]] || die "$label 對 GET 必須回 HTTP 405（避免真的觸發爬蟲），實際為 ${status}；不會 reset Serve。"
  grep -Eiq '^allow:[[:space:]]*POST[[:space:]]*$' "$headers_file" || \
    die "$label 缺少 Allow: POST header；不會 reset Serve。"
}

find_tailscale() {
  if command -v tailscale >/dev/null 2>&1; then
    command -v tailscale
    return
  fi
  local candidate
  for candidate in \
    '/Applications/Tailscale.app/Contents/MacOS/Tailscale' \
    '/Applications/Tailscale.app/Contents/MacOS/tailscale'; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  return 1
}

command -v curl >/dev/null 2>&1 || die '需要 curl 才能執行 endpoint preflight。'
command -v python3 >/dev/null 2>&1 || die '需要 python3 才能驗證 JSON 契約。'
TAILSCALE_BIN="$(find_tailscale)" || die '找不到 Tailscale CLI；macOS 可先執行 brew install --cask tailscale-app。'

serve_help="$("$TAILSCALE_BIN" serve --help 2>&1)" || die '無法讀取 Tailscale Serve 功能。'
grep -q -- '--set-path' <<<"$serve_help" || \
  die '這個 Tailscale CLI 不支援 --set-path；已 fail closed，不會建立 root proxy 或另猜 listener。'

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/asset-api-gateway-tailscale.XXXXXX")"
cleanup_partial=0
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ "$cleanup_partial" == 1 && "$rc" -ne 0 ]]; then
    local cleanup_status="$work_dir/serve-cleanup.json"
    if "$TAILSCALE_BIN" serve status --json >"$cleanup_status" 2>/dev/null && \
       validate_owned_config "$cleanup_status" subset; then
      printf '設定未完整，且現況仍只有本輪 handler；清除 partial Tailscale Serve config…\n' >&2
      "$TAILSCALE_BIN" serve reset >/dev/null 2>&1 || \
        printf 'WARNING: partial Serve config 自動 reset 失敗，請立即執行 tailscale serve status --json 確認。\n' >&2
    else
      printf 'WARNING: 設定失敗後發現非本輪或無法確認的 Serve 狀態；為避免誤刪，不會自動 reset。\n' >&2
      python3 -m json.tool "$cleanup_status" >&2 2>/dev/null || true
      printf '請由管理者執行 tailscale serve status --json 後人工處理。\n' >&2
    fi
  fi
  rm -rf "$work_dir"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

status_file="$work_dir/tailscale-status.json"
"$TAILSCALE_BIN" status --json >"$status_file" || \
  die 'Tailscale 尚未連線；請先打開 Tailscale 完成 browser login。'
tail_dns="$(python3 - "$status_file" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("BackendState") != "Running" or data.get("Self", {}).get("Online") is not True:
    raise SystemExit(1)
dns = data.get("Self", {}).get("DNSName", "").rstrip(".")
if not dns:
    raise SystemExit(1)
print(dns)
PY
)" || die 'Tailscale 未處於 Running/Online；請完成登入與裝置授權後重跑。'

validate_owned_config() {
  local config_file=$1
  local mode=$2
  python3 - "$config_file" "$tail_dns" "$mode" <<'PY'
import json, sys

filename, dns_name, mode = sys.argv[1], sys.argv[2], sys.argv[3]
if mode not in {"allow-empty", "exact", "subset"}:
    raise SystemExit("invalid validation mode")
data = json.load(open(filename, encoding="utf-8"))
if data == {}:
    if mode in {"allow-empty", "subset"}:
        raise SystemExit(0)
    raise SystemExit("Serve config 為空")

known = {"TCP", "Web", "Services", "AllowFunnel", "Foreground"}
unexpected = set(data) - known
if unexpected:
    raise SystemExit(f"出現未識別的設定欄位: {sorted(unexpected)}")
for key in ("Services", "AllowFunnel", "Foreground"):
    if data.get(key):
        raise SystemExit(f"存在非本任務或不允許的 {key} 設定")

if data.get("TCP") != {"9090": {"HTTPS": True}}:
    raise SystemExit("TCP 必須只有 HTTPS 9090")

expected = {
    "/api/quotes": "http://127.0.0.1:9090/api/quotes",
    "/api/quotes/one": "http://127.0.0.1:9090/api/quotes/one",
    "/api/public/market-index": "http://127.0.0.1:9090/api/public/market-index",
    "/api/assets/latest": "http://127.0.0.1:9090/api/assets/latest",
    "/api/public/exchange-rate/usd-twd": "http://127.0.0.1:9090/api/public/exchange-rate/usd-twd",
    "/api/public/crawler-data/rescan": "http://127.0.0.1:9090/api/public/crawler-data/rescan",
    "/api/public/market-analysis/today": "http://127.0.0.1:9090/api/public/market-analysis/today",
    "/api/public/portfolio-advice/latest": "http://127.0.0.1:9090/api/public/portfolio-advice/latest",
}
web = data.get("Web")
expected_host = f"{dns_name}:9090"
if not isinstance(web, dict) or set(web) != {expected_host}:
    raise SystemExit(f"Web host/port 必須精確為 {expected_host}")
handlers = web[expected_host].get("Handlers") if isinstance(web[expected_host], dict) else None
if not isinstance(handlers, dict):
    raise SystemExit("Handlers 必須是 object")
handler_paths = set(handlers)
if mode in {"allow-empty", "exact"} and handler_paths != set(expected):
    raise SystemExit("必須精確只有本任務管理的八條 path handler")
if mode == "subset" and not handler_paths.issubset(expected):
    raise SystemExit("partial config 含非本任務 path handler")
for path in handler_paths:
    if handlers[path] != {"Proxy": expected[path]}:
        raise SystemExit(f"handler 不屬於本任務: {path}")
PY
}

# 先確認現有設定所有權；這一步不對 Serve 做任何修改。
serve_before="$work_dir/serve-before.json"
"$TAILSCALE_BIN" serve status --json >"$serve_before" || die '無法讀取 Tailscale Serve status。'
if ! validate_owned_config "$serve_before" allow-empty; then
  printf '現有 Serve 設定含非本任務 handler，拒絕 reset：\n' >&2
  python3 -m json.tool "$serve_before" >&2 || true
  die '請先由管理者決定如何保留或移除現有 Serve 設定。'
fi

# Endpoint-aware preflight：任一契約不健康都在 reset 前停止。
quotes_json="$work_dir/quotes.json"
quotes_headers="$work_dir/quotes.headers"
get_200 "$LOCAL_BASE/api/quotes" "$quotes_json" '本機 /api/quotes' "$quotes_headers"
read -r quote_code quote_market < <(python3 - "$quotes_json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(data, list):
    raise SystemExit("/api/quotes 必須是 JSON array")
if not data:
    raise SystemExit("NO_QUOTE_SENTINEL: /api/quotes 為空，無法驗證 /api/quotes/one")
first = data[0]
code, market = first.get("stockCode"), first.get("market")
if not isinstance(code, str) or not code or not isinstance(market, str) or not market:
    raise SystemExit("報價首筆缺少合法 stockCode/market")
print(code, market)
PY
) || die 'NO_QUOTE_SENTINEL 或 quote list 契約錯誤；不會 reset Serve。'

quote_one_json="$work_dir/quote-one.json"
quote_one_headers="$work_dir/quote-one.headers"
quote_one_status=''
if ! quote_one_status="$(curl -sS --get --data-urlencode "code=$quote_code" \
  --data-urlencode "market=$quote_market" -D "$quote_one_headers" -o "$quote_one_json" -w '%{http_code}' \
  "$LOCAL_BASE/api/quotes/one")"; then
  die '本機 /api/quotes/one transport 失敗；不會 reset Serve。'
fi
[[ "$quote_one_status" == 200 ]] || \
  die "本機 /api/quotes/one 必須回 HTTP 200，實際為 ${quote_one_status}；不會 reset Serve。"
grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' "$quote_one_headers" || \
  die '本機 /api/quotes/one Content-Type 不是 application/json；不會 reset Serve。'
python3 - "$quote_one_json" "$quote_code" "$quote_market" <<'PY' || die '/api/quotes/one payload 與 quote list 首筆不一致。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(data, dict) or data.get("stockCode") != sys.argv[2] or data.get("market") != sys.argv[3]:
    raise SystemExit(1)
PY

index_json="$work_dir/market-index.json"
index_headers="$work_dir/market-index.headers"
get_200 "$LOCAL_BASE/api/public/market-index?market=TWSE&range=1m" "$index_json" '本機 market-index' "$index_headers"
python3 - "$index_json" <<'PY' || die 'market-index 必須 labels 非空且六個圖表陣列等長。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
keys = ("labels", "closes", "ma5", "ma20", "ma60", "ma240")
if not isinstance(data, dict) or not isinstance(data.get("labels"), list) or not data["labels"]:
    raise SystemExit(1)
if any(not isinstance(data.get(k), list) or len(data[k]) != len(data["labels"]) for k in keys):
    raise SystemExit(1)
PY

assets_headers="$work_dir/assets.headers"
assets_json="$work_dir/assets.json"
get_200 "$LOCAL_BASE/api/assets/latest" "$assets_json" '本機 assets/latest' "$assets_headers"
python3 - "$assets_json" <<'PY' || die 'assets/latest payload 不符合已核准契約。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
snapshot_id = data.get("snapshot", {}).get("id")
live_id = data.get("liveAssets", {}).get("snapshotId")
if data.get("valuationPolicy") != "TARGET_SESSION_WITH_EXPLICIT_FALLBACK":
    raise SystemExit(1)
if not isinstance(snapshot_id, int) or isinstance(snapshot_id, bool) or snapshot_id != live_id:
    raise SystemExit(1)
PY

usd_headers="$work_dir/usd-twd.headers"
usd_json="$work_dir/usd-twd.json"
get_200 "$LOCAL_BASE/api/public/exchange-rate/usd-twd" "$usd_json" '本機 USD/TWD' "$usd_headers"
python3 - "$usd_json" <<'PY' || die 'USD/TWD payload 不符合已核准契約。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
history = data.get("history") if isinstance(data, dict) else None
spot = data.get("spot") if isinstance(data, dict) else None
if data.get("pair") != "USD/TWD" or data.get("baseCurrency") != "USD" or data.get("quoteCurrency") != "TWD":
    raise SystemExit(1)
if data.get("refreshIntervalSeconds") != 2 or data.get("timezone") != "Asia/Taipei":
    raise SystemExit(1)
if not isinstance(history, list) or not history or data.get("count") != len(history):
    raise SystemExit(1)
if not isinstance(spot, dict) or not spot:
    raise SystemExit(1)
PY

rescan_json="$work_dir/rescan.json"
rescan_headers="$work_dir/rescan.headers"
get_405_post_only "$LOCAL_BASE/api/public/crawler-data/rescan" "$rescan_json" '本機爬蟲重新搜尋' "$rescan_headers"

# 第七、八條都是唯讀 GET 且預期 200，沿用 get_200()。第八條在「尚無任何一筆建議」時 business 仍回
# HTTP 200（PortfolioAdviceController.latest() 回 PortfolioAdviceDto.none()，status="NONE" 的普通
# JSON DTO），故不需要任何「無資料」旁路；若回非 200 代表 configured-admin bootstrap 或路由本身有
# 問題，照既有「任一契約不健康即整批 fail closed」規則處理。
market_analysis_json="$work_dir/market-analysis.json"
market_analysis_headers="$work_dir/market-analysis.headers"
get_200 "$LOCAL_BASE/api/public/market-analysis/today" "$market_analysis_json" \
  '本機今日股市分析' "$market_analysis_headers"

portfolio_advice_json="$work_dir/portfolio-advice.json"
portfolio_advice_headers="$work_dir/portfolio-advice.headers"
get_200 "$LOCAL_BASE/api/public/portfolio-advice/latest" "$portfolio_advice_json" \
  '本機資產配置建議' "$portfolio_advice_headers"

printf '現有 Serve 設定所有權與本機八路 API preflight 通過，開始更新 path-scoped Serve…\n'

# Preflight 可能耗時；reset 前重新讀取並比較解析後 JSON，避免期間有人新增 handler
# 卻被本腳本用過時的所有權判斷刪除。
serve_recheck="$work_dir/serve-recheck.json"
"$TAILSCALE_BIN" serve status --json >"$serve_recheck" || die 'reset 前無法重新讀取 Tailscale Serve status。'
if ! validate_owned_config "$serve_recheck" allow-empty; then
  printf 'preflight 期間 Serve 設定出現非本任務 handler，拒絕 reset：\n' >&2
  python3 -m json.tool "$serve_recheck" >&2 || true
  die 'Serve 設定已變更；請由管理者確認後重跑。'
fi
python3 - "$serve_before" "$serve_recheck" <<'PY' || \
  die 'preflight 期間 Serve 設定已變更；為避免誤刪，本輪不會 reset。'
import json, sys
before = json.load(open(sys.argv[1], encoding="utf-8"))
after = json.load(open(sys.argv[2], encoding="utf-8"))
if before != after:
    raise SystemExit(1)
PY

"$TAILSCALE_BIN" serve reset
cleanup_partial=1
for path in "${SERVE_PATHS[@]}"; do
  "$TAILSCALE_BIN" serve --bg --yes --https=9090 --set-path="$path" "$LOCAL_BASE$path"
done

serve_after="$work_dir/serve-after.json"
"$TAILSCALE_BIN" serve status --json >"$serve_after"
validate_owned_config "$serve_after" exact || die '建立後的 Serve config 不是預期八條 exact handler。'
cleanup_partial=0

printf 'Tailscale Serve 已安全設定：https://%s:9090\n' "$tail_dns"
python3 -m json.tool "$serve_after"
