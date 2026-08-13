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
    printf '%s\n' '{"TCP":{"9090":{"HTTPS":true}},"Web":{"mock-device.example.ts.net:9090":{"Handlers":{"/api/quotes":{"Proxy":"http://127.0.0.1:9090/api/quotes"},"/api/quotes/one":{"Proxy":"http://127.0.0.1:9090/api/quotes/one"},"/api/public/market-index":{"Proxy":"http://127.0.0.1:9090/api/public/market-index"},"/api/assets/latest":{"Proxy":"http://127.0.0.1:9090/api/assets/latest"},"/api/public/exchange-rate/usd-twd":{"Proxy":"http://127.0.0.1:9090/api/public/exchange-rate/usd-twd"}}}}}'
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
while (($#)); do
  case "$1" in
    -o|-D|-w|--data-urlencode)
      option=$1
      value=${2:?}
      case "$option" in
        -o) output_file=$value ;;
        -D) headers_file=$value ;;
      esac
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
    body='[{"stockCode":"2330","market":"台股"}]'
    ;;
  */api/quotes/one)
    endpoint=quote-one
    body='{"stockCode":"2330","market":"台股"}'
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
  *)
    printf 'unexpected URL: %s\n' "$url" >&2
    exit 2
    ;;
esac

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
  [[ "$(grep -c '^serve ' "$case_dir/tailscale.log")" == 5 ]]
  grep -Fq 'Tailscale Serve 已安全設定' "$case_dir/stdout"
}

run_content_type_failure quotes '本機 /api/quotes Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure quote-one '本機 /api/quotes/one Content-Type 不是 application/json；不會 reset Serve。'
run_content_type_failure market-index '本機 market-index Content-Type 不是 application/json；不會 reset Serve。'
run_success

printf '%s\n' 'PASS: 五路 preflight Content-Type／reset fail-closed regression'
