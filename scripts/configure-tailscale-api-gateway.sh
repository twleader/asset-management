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
  '/api/public/trading-radar/today'
  '/api/public/trading-radar/stock'
  '/api/public/transactions'
  '/api/public/trading-calendar'
  '/api/public/commodity-prices'
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

get_400_json() {
  local url=$1
  local output_file=$2
  local label=$3
  local headers_file=$4
  local -a curl_args=(-sS -D "$headers_file" -o "$output_file" -w '%{http_code}')
  local status
  if ! status="$(curl "${curl_args[@]}" "$url")"; then
    die "$label transport 失敗；不會 reset Serve。"
  fi
  [[ "$status" == 400 ]] || die "$label 必須回 HTTP 400，實際為 ${status}；不會 reset Serve。"
  grep -Eiq '^content-type:[[:space:]]*application/(problem\+)?json([;[:space:]]|$)' "$headers_file" || \
    die "$label Content-Type 不是 JSON；不會 reset Serve。"
}

get_400_json_get_body() {
  local url=$1
  local output_file=$2
  local label=$3
  local headers_file=$4
  local -a curl_args=(-sS -X GET -H 'Content-Length: 1' --data-binary 'x' -D "$headers_file" -o "$output_file" -w '%{http_code}')
  local status
  if ! status="$(curl "${curl_args[@]}" "$url")"; then
    die "$label transport 失敗；不會 reset Serve。"
  fi
  [[ "$status" == 400 ]] || die "$label 必須回 HTTP 400，實際為 ${status}；不會 reset Serve。"
  grep -Eiq '^content-type:[[:space:]]*application/problem\+json([;[:space:]]|$)' "$headers_file" || \
    die "$label Content-Type 不是 application/problem+json；不會 reset Serve。"
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
    "/api/public/trading-radar/today": "http://127.0.0.1:9090/api/public/trading-radar/today",
    "/api/public/trading-radar/stock": "http://127.0.0.1:9090/api/public/trading-radar/stock",
    "/api/public/transactions": "http://127.0.0.1:9090/api/public/transactions",
    "/api/public/trading-calendar": "http://127.0.0.1:9090/api/public/trading-calendar",
    "/api/public/commodity-prices": "http://127.0.0.1:9090/api/public/commodity-prices",
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
    raise SystemExit("必須精確只有本任務管理的十三條 path handler")
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
quote_identity="$(python3 - "$quotes_json" <<'PY'
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
raw_keys = ("stockCode", "stockName", "market", "price", "previousClose", "priceChange",
            "changePercent", "buyPrice", "sellPrice", "openPrice", "highPrice", "lowPrice",
            "volume", "tradingDate", "updatedAt", "closed", "source", "quoteStatus",
            "premiumDiscountPct")
if any(key not in first for key in raw_keys):
    raise SystemExit("報價首筆缺少原始 19 欄")
direct_keys = ("quoteDetail", "bidLevels", "askLevels", "dividendHistory")
if any(key not in first for key in direct_keys):
    raise SystemExit("報價首筆缺少 direct 行情／五檔／股利欄位")
market_data = first.get("marketData")
if not isinstance(market_data, dict) or set(market_data) != {"chart", "quoteDetail", "etfConstituents", "dividends"}:
    raise SystemExit("報價首筆缺少固定 marketData 四個 child")
chart = market_data["chart"]
if not isinstance(chart, dict) or chart.get("status") not in {"AVAILABLE", "NO_DATA", "UNAVAILABLE"}:
    raise SystemExit("報價首筆 chart 狀態不合法")
intraday = chart.get("intraday")
if not isinstance(intraday, dict) or intraday.get("status") not in {"AVAILABLE", "NO_DATA", "UNAVAILABLE"} or not isinstance(intraday.get("ticks"), list):
    raise SystemExit("報價首筆 intraday shape 不合法")
print(code, market)
PY
)" || die 'NO_QUOTE_SENTINEL 或 quote list 契約錯誤；不會 reset Serve。'
quote_code=''
quote_market=''
read -r quote_code quote_market <<<"$quote_identity"
[[ -n "$quote_code" && -n "$quote_market" ]] || \
  die 'NO_QUOTE_SENTINEL 或 quote list 契約錯誤；不會 reset Serve。'

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
python3 - "$quote_one_json" "$quote_code" "$quote_market" <<'PY' || die '/api/quotes/one payload 與 quote list 首筆不一致或缺完整市場資料。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(data, dict) or data.get("stockCode") != sys.argv[2] or data.get("market") != sys.argv[3]:
    raise SystemExit(1)
raw_keys = ("stockCode", "stockName", "market", "price", "previousClose", "priceChange",
            "changePercent", "buyPrice", "sellPrice", "openPrice", "highPrice", "lowPrice",
            "volume", "tradingDate", "updatedAt", "closed", "source", "quoteStatus",
            "premiumDiscountPct")
if any(key not in data for key in raw_keys):
    raise SystemExit(1)
if any(key not in data for key in ("quoteDetail", "bidLevels", "askLevels", "dividendHistory")):
    raise SystemExit(1)
market_data = data.get("marketData")
if not isinstance(market_data, dict) or set(market_data) != {"chart", "quoteDetail", "etfConstituents", "dividends"}:
    raise SystemExit(1)
chart = market_data.get("chart")
if not isinstance(chart, dict) or chart.get("status") not in {"AVAILABLE", "NO_DATA", "UNAVAILABLE"}:
    raise SystemExit(1)
intraday = chart.get("intraday")
if not isinstance(intraday, dict) or intraday.get("status") not in {"AVAILABLE", "NO_DATA", "UNAVAILABLE"} or not isinstance(intraday.get("ticks"), list):
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

# 第七至第九條都是唯讀 GET 且預期 200，沿用 get_200()。第八條在「尚無任何一筆建議」時 business 仍回
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

trading_radar_json="$work_dir/trading-radar.json"
trading_radar_headers="$work_dir/trading-radar.headers"
get_200 "$LOCAL_BASE/api/public/trading-radar/today" "$trading_radar_json" \
  '本機今日交易雷達' "$trading_radar_headers"
radar_selector="$(python3 - "$trading_radar_json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
required = {
    "ruleVersion", "actionPolicyVersion", "generatedAt", "market", "usMarket",
    "stocks", "skippedNonTwStocks", "publicInformation",
}
if not isinstance(data, dict) or not required.issubset(data):
    raise SystemExit(1)
if not isinstance(data["market"], dict) or not isinstance(data["usMarket"], dict):
    raise SystemExit(1)
if not isinstance(data["stocks"], list) or not isinstance(data["publicInformation"], list):
    raise SystemExit(1)
if not data["stocks"]:
    print("EMPTY")
    raise SystemExit(0)
first = data["stocks"][0]
code, market = first.get("stockCode"), first.get("market")
if not isinstance(code, str) or not code or not isinstance(market, str) or not market:
    raise SystemExit(1)
print(code, market)
PY
 )" || die '今日交易雷達 payload 不符合已核准契約。'

if [[ "$radar_selector" == "EMPTY" ]]; then
  radar_stock_error_json="$work_dir/trading-radar-stock-error.json"
  radar_stock_error_headers="$work_dir/trading-radar-stock-error.headers"
  get_400_json "$LOCAL_BASE/api/public/trading-radar/stock?stockCode=&market=" \
    "$radar_stock_error_json" '本機交易雷達空 selector' "$radar_stock_error_headers"
else
  radar_stock_code=''
  radar_stock_market=''
  read -r radar_stock_code radar_stock_market <<<"$radar_selector"
  radar_stock_json="$work_dir/trading-radar-stock.json"
  radar_stock_headers="$work_dir/trading-radar-stock.headers"
  radar_stock_status="$(curl -sS --get --data-urlencode "stockCode=$radar_stock_code" \
    --data-urlencode "market=$radar_stock_market" -D "$radar_stock_headers" -o "$radar_stock_json" -w '%{http_code}' \
    "$LOCAL_BASE/api/public/trading-radar/stock")" || \
    die '本機交易雷達指定股票 transport 失敗；不會 reset Serve。'
  [[ "$radar_stock_status" == 200 ]] || \
    die "本機交易雷達指定股票必須回 HTTP 200，實際為 ${radar_stock_status}；不會 reset Serve。"
  grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' "$radar_stock_headers" || \
    die '本機交易雷達指定股票 Content-Type 不是 application/json；不會 reset Serve。'
  python3 - "$radar_stock_json" "$radar_stock_code" "$radar_stock_market" <<'PY' || \
    die '本機交易雷達指定股票 payload 與首頁 selector 不一致。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
stock = data.get("stock") if isinstance(data, dict) else None
if not isinstance(stock, dict) or stock.get("stockCode") != sys.argv[2] or stock.get("market") != sys.argv[3]:
    raise SystemExit(1)
PY
fi

transactions_json="$work_dir/transactions.json"
transactions_headers="$work_dir/transactions.headers"
get_200 "$LOCAL_BASE/api/public/transactions" "$transactions_json" '本機交易紀錄' "$transactions_headers"
python3 - "$transactions_json" <<'PY' || die '交易紀錄 payload 不符合已核准契約。'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
required = {"selection", "allTimeSummary", "summary", "yearSummaries", "records"}
if not isinstance(data, dict) or not required.issubset(data):
    raise SystemExit(1)
if not isinstance(data["records"], list) or not isinstance(data["yearSummaries"], list):
    raise SystemExit(1)
PY

calendar_year="$(python3 - <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo
print(datetime.now(ZoneInfo("Asia/Taipei")).year)
PY
)"
calendar_json="$work_dir/trading-calendar.json"
calendar_headers="$work_dir/trading-calendar.headers"
get_200 "$LOCAL_BASE/api/public/trading-calendar?year=$calendar_year" "$calendar_json" \
  '本機交易日曆' "$calendar_headers"
python3 - "$calendar_json" "$calendar_year" <<'PY' || die '交易日曆 payload 不符合已核准契約。'
import calendar, json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
year = int(sys.argv[2])
required = {"year", "generatedAt", "timezone", "availableYears", "minYear", "maxYear", "markets",
            "availability", "tradingDayCount", "holidays", "days", "marketStatus"}
if not isinstance(data, dict) or not required.issubset(data) or data.get("year") != year:
    raise SystemExit(1)
if data.get("timezone") != "Asia/Taipei" or len(data.get("days", [])) != (366 if calendar.isleap(year) else 365):
    raise SystemExit(1)
PY

commodity_json="$work_dir/commodity-prices.json"
commodity_headers="$work_dir/commodity-prices.headers"
get_200 "$LOCAL_BASE/api/public/commodity-prices" "$commodity_json" '本機商品批次報價' "$commodity_headers"
python3 - "$commodity_json" <<'PY' || die '商品批次報價 payload 不符合已核准固定三-slot 契約。'
import json, math, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(data, dict) or list(data) != ["marketOpen", "quotes"] or type(data.get("marketOpen")) is not bool:
    raise SystemExit(1)
quotes = data.get("quotes")
if not isinstance(quotes, dict) or list(quotes) != ["WTI", "BRENT", "GOLD"]:
    raise SystemExit(1)
units = {"WTI": "USD_PER_BARREL", "BRENT": "USD_PER_BARREL", "GOLD": "USD_PER_TROY_OUNCE"}
required = {"commodityCode", "unit", "price", "change", "changePercent", "sessionDate", "quoteTime", "polledAt", "status", "dayHigh", "dayLow", "provider"}
for code, quote in quotes.items():
    if quote is None:
        continue
    if not isinstance(quote, dict) or set(quote) != required or quote.get("commodityCode") != code or quote.get("unit") != units[code]:
        raise SystemExit(1)
    if not isinstance(quote.get("price"), (int, float)) or isinstance(quote.get("price"), bool) or not math.isfinite(quote["price"]) or quote["price"] <= 0:
        raise SystemExit(1)
    if (quote.get("change") is None) != (quote.get("changePercent") is None):
        raise SystemExit(1)
    if quote.get("status") not in {"LIVE", "STALE", "SETTLED"} or not isinstance(quote.get("provider"), str) or not quote["provider"].strip():
        raise SystemExit(1)
    for field in ("dayHigh", "dayLow"):
        value = quote.get(field)
        if value is not None and (not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0):
            raise SystemExit(1)
PY

commodity_query_json="$work_dir/commodity-prices-query.json"
commodity_query_headers="$work_dir/commodity-prices-query.headers"
get_400_json "$LOCAL_BASE/api/public/commodity-prices?unexpected=1" "$commodity_query_json" \
  '本機商品批次報價 query gate' "$commodity_query_headers"
commodity_body_json="$work_dir/commodity-prices-body.json"
commodity_body_headers="$work_dir/commodity-prices-body.headers"
get_400_json_get_body "$LOCAL_BASE/api/public/commodity-prices" "$commodity_body_json" \
  '本機商品批次報價 GET body gate' "$commodity_body_headers"
python3 - "$commodity_query_json" "$commodity_body_json" <<'PY' || die '商品批次報價 request gate 沒有回固定 ProblemDetail。'
import json, sys
expected = {
    "type": "about:blank",
    "title": "Invalid commodity price request",
    "status": 400,
    "detail": "不支援 query parameter 或 request body",
    "instance": "/api/public/commodity-prices",
}
for filename in sys.argv[1:]:
    data = json.load(open(filename, encoding="utf-8"))
    if data != expected:
        raise SystemExit(1)
PY

printf '現有 Serve 設定所有權與本機十三路 API preflight 通過，開始更新 path-scoped Serve…\n'

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
validate_owned_config "$serve_after" exact || die '建立後的 Serve config 不是預期十三條 exact handler。'
cleanup_partial=0

printf 'Tailscale Serve 已安全設定：https://%s:9090\n' "$tail_dns"
python3 -m json.tool "$serve_after"
