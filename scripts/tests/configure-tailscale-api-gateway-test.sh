#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly SCRIPT="$REPO_ROOT/scripts/configure-tailscale-api-gateway.sh"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/tailscale-gateway-test.XXXXXX")"
cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

make_fakes() {
  local case_dir=$1
  mkdir -p "$case_dir/bin"

  cat >"$case_dir/bin/tailscale" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${MOCK_TAILSCALE_LOG:?}"
: "${MOCK_TAILSCALE_STATE:?}"

if [[ "$*" == 'serve --help' ]]; then
  printf '%s\n' 'usage: tailscale serve --set-path'
elif [[ "$*" == 'status --json' ]]; then
  printf '%s\n' '{"BackendState":"Running","Self":{"Online":true,"DNSName":"mock-device.example.ts.net."}}'
elif [[ "$*" == 'serve status --json' ]]; then
  if [[ -f "$MOCK_TAILSCALE_STATE" ]]; then
    printf '%s\n' '{"TCP":{"9090":{"HTTPS":true}},"Web":{"mock-device.example.ts.net:9090":{"Handlers":{"/api/quotes":{"Proxy":"http://127.0.0.1:9090/api/quotes"},"/api/quotes/one":{"Proxy":"http://127.0.0.1:9090/api/quotes/one"},"/api/public/market-index":{"Proxy":"http://127.0.0.1:9090/api/public/market-index"},"/api/assets/latest":{"Proxy":"http://127.0.0.1:9090/api/assets/latest"},"/api/public/exchange-rate/usd-twd":{"Proxy":"http://127.0.0.1:9090/api/public/exchange-rate/usd-twd"},"/api/public/crawler-data/rescan":{"Proxy":"http://127.0.0.1:9090/api/public/crawler-data/rescan"},"/api/public/market-analysis/today":{"Proxy":"http://127.0.0.1:9090/api/public/market-analysis/today"},"/api/public/portfolio-advice/latest":{"Proxy":"http://127.0.0.1:9090/api/public/portfolio-advice/latest"},"/api/public/trading-radar/today":{"Proxy":"http://127.0.0.1:9090/api/public/trading-radar/today"},"/api/public/trading-radar/stock":{"Proxy":"http://127.0.0.1:9090/api/public/trading-radar/stock"},"/api/public/transactions":{"Proxy":"http://127.0.0.1:9090/api/public/transactions"},"/api/public/trading-calendar":{"Proxy":"http://127.0.0.1:9090/api/public/trading-calendar"},"/api/public/commodity-prices":{"Proxy":"http://127.0.0.1:9090/api/public/commodity-prices"},"/api/public/srpp/daily-context":{"Proxy":"http://127.0.0.1:9090/api/public/srpp/daily-context"}}}}}'
  else
    printf '%s\n' '{}'
  fi
elif [[ "$*" == 'serve reset' ]]; then
  printf '%s\n' reset >>"$MOCK_TAILSCALE_LOG"
  rm -f "$MOCK_TAILSCALE_STATE"
elif [[ "${1:-}" == serve && "${2:-}" == --bg ]]; then
  printf 'serve %s\n' "$*" >>"$MOCK_TAILSCALE_LOG"
  if [[ "$*" == *'/api/public/exchange-rate/usd-twd'* ]]; then
    : >"$MOCK_TAILSCALE_STATE"
  fi
else
  printf 'unexpected tailscale args: %s\n' "$*" >&2
  exit 2
fi
MOCK

  cat >"$case_dir/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail

output_file=''
headers_file=''
url=''
request_body=''
while (($#)); do
  case "$1" in
    -o|-D|-w|--data-urlencode|--data-binary)
      option=$1
      value=${2:?}
      case "$option" in
        -o) output_file=$value ;;
        -D) headers_file=$value ;;
        --data-binary) request_body=$value ;;
      esac
      shift 2
      ;;
    -X|-H)
      shift 2
      ;;
    -sS|--get)
      shift
      ;;
    http://*)
      url=$1
      shift
      ;;
    *)
      printf 'unexpected curl arg: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

case "$url" in
  */api/quotes)
    endpoint=quotes
    body='[{"stockCode":"2330","stockName":"範例","market":"台股","price":1,"previousClose":null,"priceChange":null,"changePercent":null,"buyPrice":null,"sellPrice":null,"openPrice":null,"highPrice":null,"lowPrice":null,"volume":null,"tradingDate":"2026-08-24","updatedAt":null,"closed":false,"source":"TEST","quoteStatus":"LIVE","premiumDiscountPct":null,"marketData":{"chart":{"status":"NO_DATA","intraday":{"status":"NO_DATA","ticks":[]}},"quoteDetail":{},"etfConstituents":{},"dividends":{}},"quoteDetail":{},"bidLevels":[],"askLevels":[],"dividendHistory":{}}]'
    ;;
  */api/quotes/one)
    endpoint=quote-one
    body='{"stockCode":"2330","stockName":"範例","market":"台股","price":1,"previousClose":null,"priceChange":null,"changePercent":null,"buyPrice":null,"sellPrice":null,"openPrice":null,"highPrice":null,"lowPrice":null,"volume":null,"tradingDate":"2026-08-24","updatedAt":null,"closed":false,"source":"TEST","quoteStatus":"LIVE","premiumDiscountPct":null,"marketData":{"chart":{"status":"NO_DATA","intraday":{"status":"NO_DATA","ticks":[]}},"quoteDetail":{},"etfConstituents":{},"dividends":{}},"quoteDetail":{},"bidLevels":[],"askLevels":[],"dividendHistory":{}}'
    ;;
  *'/api/public/market-index?'*)
    endpoint=market-index
    body='{"labels":["09:00"],"closes":[1],"ma5":[1],"ma20":[1],"ma60":[1],"ma240":[1]}'
    ;;
  */api/assets/latest)
    endpoint=assets
    body='{"valuationPolicy":"TARGET_SESSION_WITH_EXPLICIT_FALLBACK","snapshot":{"id":7},"liveAssets":{"snapshotId":7}}'
    ;;
  */api/public/exchange-rate/usd-twd)
    endpoint=usd-twd
    body='{"pair":"USD/TWD","baseCurrency":"USD","quoteCurrency":"TWD","refreshIntervalSeconds":2,"timezone":"Asia/Taipei","spot":{"source":"HISTORY"},"history":[{"date":"2026-08-13"}],"count":1}'
    ;;
  # 第六條是 POST-only，腳本刻意只對它發 GET 並預期 405 + Allow: POST（不真的觸發爬蟲）。
  */api/public/crawler-data/rescan)
    endpoint=rescan
    body=''
    ;;
  */api/public/market-analysis/today)
    endpoint=market-analysis
    body='{"status":"OK","analysisDate":"2026-08-16"}'
    ;;
  # 尚無任何一筆建議時 business 仍回 HTTP 200 的 {"status":"NONE"}；腳本沿用 get_200()，無「無資料」旁路。
  */api/public/portfolio-advice/latest)
    endpoint=portfolio-advice
    body='{"status":"NONE"}'
    ;;
  */api/public/trading-radar/today)
    endpoint=trading-radar
    body='{"ruleVersion":"TW_RULES_V14","actionPolicyVersion":"EVIDENCE_GATE_V1","generatedAt":"2026-08-21T12:00:00+08:00","market":{},"usMarket":{},"stocks":[],"skippedNonTwStocks":0,"publicInformation":[]}'
    ;;
  *'/api/public/trading-radar/stock?'*)
    endpoint=trading-radar-stock-error
    body='{"title":"Invalid trading radar request","status":400}'
    ;;
  */api/public/transactions)
    endpoint=transactions
    body='{"selection":{"mode":"ALL","year":null,"start":null,"end":null},"allTimeSummary":{},"summary":{},"yearSummaries":[],"records":[]}'
    ;;
  *'/api/public/trading-calendar?'*)
    endpoint=trading-calendar
    calendar_year="${url##*=}"
    body="$(python3 - "$calendar_year" <<'PY'
import calendar, json, sys
year = int(sys.argv[1])
print(json.dumps({
    "year": year, "generatedAt": "2026-08-26T12:00:00+08:00", "timezone": "Asia/Taipei",
    "availableYears": [year - 1, year, year + 1], "minYear": year - 1, "maxYear": year + 1,
    "markets": [], "availability": {}, "tradingDayCount": {}, "holidays": {},
    "days": [{}] * (366 if calendar.isleap(year) else 365), "marketStatus": {}
}, ensure_ascii=False))
PY
)"
    ;;
  *'/api/public/commodity-prices?'*)
    endpoint=commodity-invalid
    body='{"type":"about:blank","title":"Invalid commodity price request","status":400,"detail":"不支援 query parameter 或 request body","instance":"/api/public/commodity-prices"}'
    ;;
  */api/public/commodity-prices)
    if [[ -n "$request_body" ]]; then
      endpoint=commodity-invalid
      body='{"type":"about:blank","title":"Invalid commodity price request","status":400,"detail":"不支援 query parameter 或 request body","instance":"/api/public/commodity-prices"}'
    else
      endpoint=commodity
      body='{"marketOpen":false,"quotes":{"WTI":null,"BRENT":null,"GOLD":null}}'
    fi
    ;;
  # 第十四條：全零規則包探測，business registry 必定查無 → 合成 409 POLICY_UNSUPPORTED problem。
  */api/public/srpp/daily-context)
    endpoint=srpp
    body='{"type":"about:blank","title":"SRPP policy bundle unsupported","status":409,"detail":"指定的規則包尚未登錄或未通過驗證。","instance":"/api/public/srpp/daily-context","code":"POLICY_UNSUPPORTED","retryable":false}'
    ;;
  *)
    printf 'unexpected URL: %s\n' "$url" >&2
    exit 2
    ;;
esac

if [[ "$endpoint" == rescan ]]; then
  printf '%s' "$body" >"$output_file"
  printf 'HTTP/1.1 405 Method Not Allowed\r\nAllow: POST\r\n\r\n' >"$headers_file"
  printf '405'
  exit 0
fi

if [[ "$endpoint" == trading-radar-stock-error ]]; then
  printf '%s' "$body" >"$output_file"
  printf 'HTTP/1.1 400 Bad Request\r\nContent-Type: application/problem+json\r\n\r\n' >"$headers_file"
  printf '400'
  exit 0
fi

if [[ "$endpoint" == commodity-invalid ]]; then
  printf '%s' "$body" >"$output_file"
  printf 'HTTP/1.1 400 Bad Request\r\nContent-Type: application/problem+json\r\n\r\n' >"$headers_file"
  printf '400'
  exit 0
fi

if [[ "$endpoint" == srpp ]]; then
  srpp_content_type=application/problem+json
  if [[ "${FAIL_CONTENT_TYPE:-}" == srpp ]]; then
    srpp_content_type=application/json
  fi
  printf '%s' "$body" >"$output_file"
  printf 'HTTP/1.1 409 Conflict\r\nContent-Type: %s\r\nCache-Control: private, no-store\r\n\r\n' "$srpp_content_type" >"$headers_file"
  printf '409'
  exit 0
fi

content_type=application/json
if [[ "${FAIL_CONTENT_TYPE:-}" == "$endpoint" ]]; then
  content_type=text/plain
fi
printf '%s' "$body" >"$output_file"
printf 'HTTP/1.1 200 OK\r\nContent-Type: %s\r\n\r\n' "$content_type" >"$headers_file"
printf '200'
MOCK

  chmod +x "$case_dir/bin/tailscale" "$case_dir/bin/curl"
}

run_content_type_failure() {
  local endpoint=$1
  local expected_message=$2
  local case_dir="$test_root/$endpoint"
  make_fakes "$case_dir"
  : >"$case_dir/tailscale.log"

  if env \
      PATH="$case_dir/bin:$PATH" \
      MOCK_TAILSCALE_LOG="$case_dir/tailscale.log" \
      MOCK_TAILSCALE_STATE="$case_dir/tailscale.state" \
      FAIL_CONTENT_TYPE="$endpoint" \
      "$SCRIPT" >"$case_dir/stdout" 2>"$case_dir/stderr"; then
    printf 'FAIL: %s Content-Type 錯誤時腳本竟成功\n' "$endpoint" >&2
    exit 1
  fi
  grep -Fq "$expected_message" "$case_dir/stderr"
  if grep -Fxq reset "$case_dir/tailscale.log"; then
    printf 'FAIL: %s Content-Type 錯誤後仍 reset Serve\n' "$endpoint" >&2
    exit 1
  fi
}

run_success() {
  local case_dir="$test_root/success"
  make_fakes "$case_dir"
  : >"$case_dir/tailscale.log"

  env \
    PATH="$case_dir/bin:$PATH" \
    MOCK_TAILSCALE_LOG="$case_dir/tailscale.log" \
    MOCK_TAILSCALE_STATE="$case_dir/tailscale.state" \
    "$SCRIPT" >"$case_dir/stdout" 2>"$case_dir/stderr"

  [[ "$(grep -Fxc reset "$case_dir/tailscale.log")" == 1 ]]
  [[ "$(grep -c '^serve ' "$case_dir/tailscale.log")" == 14 ]]
  grep -Fq -- '--set-path=/api/public/srpp/daily-context http://127.0.0.1:9090/api/public/srpp/daily-context' "$case_dir/tailscale.log"
  grep -Fq 'Tailscale Serve 已安全設定' "$case_dir/stdout"
}

run_content_type_failure quotes '本機 /api/quotes Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure quote-one '本機 /api/quotes/one Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure market-index '本機 market-index Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure trading-radar '本機今日交易雷達 Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure transactions '本機交易紀錄 Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure trading-calendar '本機交易日曆 Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure commodity '本機商品批次報價 Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure srpp '本機 SRPP 共用計算結果 Content-Type 不是 application/problem+json；不會 reset Serve。'
run_success

printf '%s\n' 'PASS: 十四路 preflight Content-Type、commodity request gate、SRPP 409 POLICY_UNSUPPORTED 探測／reset fail-closed regression'
