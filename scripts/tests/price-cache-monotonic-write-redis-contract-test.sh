#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
redis_container="${T350_REDIS_CONTAINER:-asset-redis}"
run_id="${T350_RUN_ID:-$(date +%Y%m%d%H%M%S)-$$}"
if [[ ! "$run_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "FAIL: T350_RUN_ID contains unsafe characters" >&2
  exit 1
fi

lua_host="$repo_root/external-materials-service/src/main/resources/redis/price-cache-monotonic-write.lua"
lua_container="/tmp/t350-price-cache-contract-$run_id.lua"
latest="t350:test:latest:$run_id"
index="t350:test:index:$run_id"
latest_type="t350:test:latest-type:$run_id"
index_type="t350:test:index-type:$run_id"
channel="t350:test:channel:$run_id"
member="T350-CONTRACT-$run_id"
lua_copied=false

redis() {
  docker exec "$redis_container" redis-cli "$@"
}

cleanup() {
  if docker inspect "$redis_container" >/dev/null 2>&1; then
    redis DEL "$latest" "$index" "$latest_type" "$index_type" >/dev/null 2>&1 || true
    if [[ "$lua_copied" == true ]]; then
      docker exec "$redis_container" unlink "$lua_container" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

lua_csv() {
  redis --csv --eval "$lua_container" "$latest" "$index" , \
    "$1" "$member" 600 "$channel"
}

status_of() {
  lua_csv "$1" | cut -d, -f1
}

assert_status() {
  local expected="$1" payload="$2" label="$3" actual
  actual="$(status_of "$payload")"
  [[ "$actual" == "$expected" ]] || fail "$label expected=$expected actual=$actual"
  echo "PASS: $label outcome=$actual"
}

assert_json_subset() {
  local actual="$1" expected="$2" label="$3"
  ruby -rjson -e '
    actual = JSON.parse(ARGV.fetch(0)); expected = JSON.parse(ARGV.fetch(1))
    missing = expected.reject { |key, value| actual.key?(key) && actual[key] == value }
    abort("#{ARGV.fetch(2)} mismatch=#{missing.inspect} actual=#{actual.inspect}") unless missing.empty?
  ' "$actual" "$expected" "$label" || fail "$label"
  echo "PASS: $label"
}

assert_json_absent() {
  local actual="$1" fields_csv="$2" label="$3"
  ruby -rjson -e '
    actual = JSON.parse(ARGV.fetch(0)); fields = ARGV.fetch(1).split(",")
    present = fields.select { |field| actual.key?(field) }
    abort("#{ARGV.fetch(2)} unexpectedly present=#{present.inspect}") unless present.empty?
  ' "$actual" "$fields_csv" "$label" || fail "$label"
  echo "PASS: $label"
}

assert_change_fields() {
  local actual="$1" expected_change="$2" expected_percent="$3" label="$4"
  ruby -rjson -e '
    actual = JSON.parse(ARGV.fetch(0))
    expected_change = Float(ARGV.fetch(1)); expected_percent = Float(ARGV.fetch(2))
    change = actual.fetch("priceChange"); percent = actual.fetch("changePercent")
    abort("#{ARGV.fetch(3)} change=#{change.inspect} percent=#{percent.inspect}") \
      unless (change - expected_change).abs <= 1e-12 && percent == expected_percent
  ' "$actual" "$expected_change" "$expected_percent" "$label" || fail "$label"
  echo "PASS: $label"
}

assert_error_unchanged() {
  local payload="$1" label="$2" before after before_members after_members before_ttl after_ttl output
  before="$(redis --raw GET "$latest")"
  before_members="$(redis --raw SMEMBERS "$index" | sort)"
  before_ttl="$(redis TTL "$latest")"
  output="$(lua_csv "$payload" 2>&1)"
  after="$(redis --raw GET "$latest")"
  after_members="$(redis --raw SMEMBERS "$index" | sort)"
  after_ttl="$(redis TTL "$latest")"
  [[ "$output" == ERR* ]] || fail "$label expected Redis error, got $output"
  [[ "$before" == "$after" && "$before_members" == "$after_members" ]] \
    || fail "$label mutated payload/index"
  (( after_ttl > 0 && after_ttl <= before_ttl )) || fail "$label reset/removed TTL"
  echo "PASS: $label error-zero-mutation"
}

docker inspect "$redis_container" >/dev/null 2>&1 \
  || fail "Redis container is not available: $redis_container"
[[ -f "$lua_host" ]] || fail "formal Lua resource is missing: $lua_host"
docker cp "$lua_host" "$redis_container:$lua_container" >/dev/null
lua_copied=true
redis PING | grep -qx PONG || fail "Redis PING failed"
redis DEL "$latest" "$index" "$latest_type" "$index_type" >/dev/null

live='{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":"Existing Name","openPrice":99,"highPrice":101,"lowPrice":98,"volume":1234,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00.000000000","closed":false,"quoteStatus":"LIVE"}'
assert_status 1 "$live" "initial LIVE"
csv="$(lua_csv "$live")"
fields="$(ruby -rcsv -e 'puts CSV.parse(STDIN.read).first.length' <<<"$csv")"
[[ "$fields" == 4 ]] || fail "Lua return must have four fields, got $csv"
echo "PASS: fixed four-field return"

before="$(redis --raw GET "$latest")"
before_members="$(redis --raw SMEMBERS "$index" | sort)"
before_ttl="$(redis TTL "$latest")"
assert_status 0 '{"stockCode":"TEST-T350","market":"台股","price":99,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' "lower rank with later time"
assert_status 0 '{"stockCode":"TEST-T350","market":"台股","price":101,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:04:59.999999999","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "higher rank with older time"
after="$(redis --raw GET "$latest")"
after_members="$(redis --raw SMEMBERS "$index" | sort)"
after_ttl="$(redis TTL "$latest")"
[[ "$before" == "$after" && "$before_members" == "$after_members" ]] \
  || fail "stale outcome mutated payload/index"
(( after_ttl > 0 && after_ttl <= before_ttl )) || fail "stale outcome reset/removed TTL"
echo "PASS: stale payload/index/TTL zero-mutation"

verified='{"stockCode":"TEST-T350","market":"台股","price":101,"openPrice":100,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00.000000000","closed":true,"quoteStatus":"VERIFIED_CLOSE"}'
assert_status 1 "$verified" "same-date verified metadata merge"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"price":101,"previousClose":98.5,"priceChange":2.5,"changePercent":2.538071,"stockName":"Existing Name","openPrice":100,"quoteStatus":"VERIFIED_CLOSE"}' "verified preserves previousClose/name and recomputes change fields"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":80,"previousClose":80,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
assert_status 1 '{"stockCode":"TEST-T350","market":"台股","price":80.987654,"priceChange":999,"changePercent":999,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "verified positive midpoint recompute"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"previousClose":80}' "verified positive midpoint preserves previousClose"
assert_change_fields "$stored" 0.987654 1.234568 "verified positive midpoint rounds away from zero"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":80,"previousClose":80,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
assert_status 1 '{"stockCode":"TEST-T350","market":"台股","price":79.012346,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "verified negative midpoint recompute"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"previousClose":80}' "verified negative midpoint preserves previousClose"
assert_change_fields "$stored" -0.987654 -1.234568 "verified negative midpoint rounds away from zero"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":"98.5","stockName":7,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
assert_status 1 '{"stockCode":"TEST-T350","market":"台股","price":101,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "verified rejects wrong metadata types"
stored="$(redis --raw GET "$latest")"
assert_json_absent "$stored" 'previousClose,priceChange,changePercent,stockName' "verified does not carry string previousClose or numeric name"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":-1,"stockName":"   ","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
assert_status 1 '{"stockCode":"TEST-T350","market":"台股","price":99,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "verified rejects nonpositive previousClose and blank name"
stored="$(redis --raw GET "$latest")"
assert_json_absent "$stored" 'previousClose,priceChange,changePercent,stockName' "verified invalid metadata stays absent"

for invalid_previous in 0 NaN Infinity; do
  redis SET "$latest" "{\"stockCode\":\"TEST-T350\",\"market\":\"台股\",\"price\":100,\"previousClose\":$invalid_previous,\"tradingDate\":\"2026-08-21\",\"updatedAt\":\"2026-08-21T12:05:00\",\"closed\":true,\"quoteStatus\":\"VERIFIED_CLOSE\"}" EX 600 >/dev/null
  assert_status 1 '{"stockCode":"TEST-T350","market":"台股","price":99,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' "verified rejects previousClose domain $invalid_previous"
  assert_json_absent "$(redis --raw GET "$latest")" 'previousClose,priceChange,changePercent' "verified does not preserve previousClose $invalid_previous"
done

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":"Old Name","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
verified_authoritative='{"stockCode":"TEST-T350","market":"台股","price":101,"previousClose":100,"priceChange":1,"changePercent":1,"stockName":"Incoming Name","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}'
assert_status 1 "$verified_authoritative" "verified incoming metadata remains authoritative"
assert_json_subset "$(redis --raw GET "$latest")" '{"previousClose":100,"priceChange":1,"changePercent":1,"stockName":"Incoming Name"}' "verified never replaces nonnull incoming metadata"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":"Existing Name","openPrice":99,"highPrice":101,"lowPrice":98,"volume":1234,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
db_same_date='{"stockCode":"TEST-T350","market":"台股","price":102,"previousClose":97,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:06:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}'
assert_status 1 "$db_same_date" "same-date DB metadata merge"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"price":102,"previousClose":97,"stockName":"Existing Name","openPrice":99,"highPrice":101,"lowPrice":98,"volume":1234}' "DB keeps authoritative previousClose and preserves same-date OHLCV/name"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":7,"openPrice":"99","highPrice":{},"lowPrice":null,"volume":"1234","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
assert_status 1 "$db_same_date" "DB rejects nonnumeric preservation metadata"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"previousClose":97}' "DB previousClose remains incoming authority with malformed existing metadata"
assert_json_absent "$stored" 'stockName,openPrice,highPrice,lowPrice,volume' "DB does not carry nonnumeric OHLCV/name"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"openPrice":0,"highPrice":-1,"lowPrice":0,"volume":-1,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
assert_status 1 "$db_same_date" "DB rejects nonpositive OHLC and negative volume"
assert_json_absent "$(redis --raw GET "$latest")" 'openPrice,highPrice,lowPrice,volume' "DB nonpositive numeric metadata stays absent"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"openPrice":NaN,"highPrice":Infinity,"lowPrice":-Infinity,"volume":NaN,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
assert_status 1 "$db_same_date" "DB rejects nonfinite OHLCV"
assert_json_absent "$(redis --raw GET "$latest")" 'openPrice,highPrice,lowPrice,volume' "DB nonfinite numeric metadata stays absent"

for invalid_volume in 1.5 9007199254740992 Infinity; do
  redis SET "$latest" "{\"stockCode\":\"TEST-T350\",\"market\":\"台股\",\"price\":100,\"volume\":$invalid_volume,\"tradingDate\":\"2026-08-21\",\"updatedAt\":\"2026-08-21T12:05:00\",\"closed\":true,\"quoteStatus\":\"PREVIOUS_CLOSE\"}" EX 600 >/dev/null
  assert_status 1 "$db_same_date" "DB rejects volume domain $invalid_volume"
  assert_json_absent "$(redis --raw GET "$latest")" 'volume' "DB does not preserve volume $invalid_volume"
done

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"openPrice":0.01,"highPrice":0.02,"lowPrice":0.03,"volume":0,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
assert_status 1 "$db_same_date" "DB preserves positive OHLC and zero volume boundary"
assert_json_subset "$(redis --raw GET "$latest")" '{"openPrice":0.01,"highPrice":0.02,"lowPrice":0.03,"volume":0}' "DB accepts positive OHLC and zero volume"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"volume":9007199254740991,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
assert_status 1 "$db_same_date" "DB preserves max safe integer volume boundary"
assert_json_subset "$(redis --raw GET "$latest")" '{"volume":9007199254740991}' "DB keeps max safe integer volume exact"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":"Cross Date Name","openPrice":99,"highPrice":101,"lowPrice":98,"volume":1234,"tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}' EX 600 >/dev/null
db_new_date='{"stockCode":"TEST-T350","market":"台股","price":103,"previousClose":102,"tradingDate":"2026-08-22","updatedAt":"2026-08-22T09:00:00","closed":true,"quoteStatus":"PREVIOUS_CLOSE"}'
assert_status 1 "$db_new_date" "new-date DB write"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"previousClose":102,"stockName":"Cross Date Name"}' "cross-date DB preserves name and incoming previousClose"
assert_json_absent "$stored" 'openPrice,highPrice,lowPrice,volume' "cross-date DB does not preserve old OHLCV"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"previousClose":98.5,"stockName":"Cross Date Verified","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}' EX 600 >/dev/null
verified_new_date='{"stockCode":"TEST-T350","market":"台股","price":104,"tradingDate":"2026-08-22","updatedAt":"2026-08-22T13:30:00","closed":true,"quoteStatus":"VERIFIED_CLOSE"}'
assert_status 1 "$verified_new_date" "new-date verified write"
stored="$(redis --raw GET "$latest")"
assert_json_subset "$stored" '{"stockName":"Cross Date Verified"}' "cross-date verified preserves name"
assert_json_absent "$stored" 'previousClose' "cross-date verified does not preserve old previousClose"

redis SET "$latest" '{"stockCode":"TEST-T350","market":"台股","price":100,"stockName":"Must Not Merge","tradingDate":"2026-08-23","updatedAt":"2026-08-23T10:00:00","closed":false,"quoteStatus":"LIVE"}' EX 600 >/dev/null
live_without_name='{"stockCode":"TEST-T350","market":"台股","price":101,"tradingDate":"2026-08-23","updatedAt":"2026-08-23T10:01:00","closed":false,"quoteStatus":"LIVE"}'
assert_status 1 "$live_without_name" "LIVE write does not merge metadata"
assert_json_absent "$(redis --raw GET "$latest")" 'stockName' "LIVE keeps incoming payload exact"

redis SET "$latest" '{broken-json' EX 600 >/dev/null
assert_status 1 "$verified_new_date" "malformed existing repair"
assert_json_absent "$(redis --raw GET "$latest")" 'previousClose,stockName' "malformed existing contributes no metadata"

for invalid in \
  '{"tradingDate":"2026-08-22","updatedAt":"2026-08-22T12:00:00"}' \
  '{"tradingDate":"2026-08-22","updatedAt":"2026-08-22T12:00:00","quoteStatus":null}' \
  '{"tradingDate":"2026-08-22","updatedAt":"2026-08-22T12:00:00","quoteStatus":7}' \
  '{"tradingDate":"2026-08-22","updatedAt":"2026-08-22T12:00:00","quoteStatus":"   "}'; do
  assert_error_unchanged "$invalid" "invalid incoming status"
done
assert_error_unchanged '{"tradingDate":"2026-02-29","updatedAt":"2026-02-29T12:00:00","quoteStatus":"LIVE"}' "strict Gregorian date"

for legacy in \
  '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:05"}' \
  '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:05:00","quoteStatus":null}' \
  '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:05:00.1","quoteStatus":7}' \
  '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:05:00.123456789","quoteStatus":"   "}'; do
  redis SET "$latest" "$legacy" EX 600 >/dev/null
  assert_status 0 '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:04:59.999999999","quoteStatus":"LIVE"}' "legacy status still rejects older time"
  case "$legacy" in
    *'12:05:00.123456789'*) equal_time='2026-08-24T12:05:00.123456789' ;;
    *'12:05:00.1'*) equal_time='2026-08-24T12:05:00.100000000' ;;
    *) equal_time='2026-08-24T12:05:00.000000000' ;;
  esac
  assert_status 1 "{\"tradingDate\":\"2026-08-24\",\"updatedAt\":\"$equal_time\",\"quoteStatus\":\"LIVE\"}" "legacy status repairs equal normalized time"
  redis SET "$latest" "$legacy" EX 600 >/dev/null
  assert_status 1 '{"tradingDate":"2026-08-24","updatedAt":"2026-08-24T12:06:00","quoteStatus":"LIVE"}' "legacy status repairs newer time"
done

redis DEL "$latest_type" "$index_type" >/dev/null
redis LPUSH "$latest_type" x >/dev/null
typed="$(redis --csv --eval "$lua_container" "$latest_type" "$index_type" , "$live" "$member" 600 "$channel" 2>&1)"
[[ "$typed" == ERR* && "$(redis LLEN "$latest_type")" == 1 ]] \
  || fail "latest key type guard mutated data"
echo "PASS: latest key type guard"
redis DEL "$latest_type" "$index_type" >/dev/null
redis SET "$index_type" sentinel >/dev/null
typed="$(redis --csv --eval "$lua_container" "$latest_type" "$index_type" , "$live" "$member" 600 "$channel" 2>&1)"
[[ "$typed" == ERR* && "$(redis --raw GET "$index_type")" == sentinel \
    && "$(redis EXISTS "$latest_type")" == 0 ]] || fail "index key type guard mutated data"
echo "PASS: index key type guard"

redis DEL "$latest" "$index" >/dev/null
accepted='{"stockCode":"TEST-T350","market":"台股","price":100,"tradingDate":"2026-08-25","updatedAt":"2026-08-25T12:05:00","closed":true,"quoteStatus":"SYNTHETIC_TEST"}'
stale='{"stockCode":"TEST-T350","market":"台股","price":99,"tradingDate":"2026-08-25","updatedAt":"2026-08-25T12:04:59","closed":true,"quoteStatus":"SYNTHETIC_TEST"}'
invalid='{"stockCode":"TEST-T350","market":"台股","price":98,"tradingDate":"2026-08-25","updatedAt":"2026-08-25T12:06:00","closed":true}'
exec 9< <(docker exec "$redis_container" sh -c 'timeout 2 redis-cli MONITOR')
IFS= read -r monitor_ready <&9
[[ "$monitor_ready" == OK ]] || fail "Redis MONITOR did not become ready"
lua_csv "$accepted" >/dev/null 2>&1
lua_csv "$stale" >/dev/null 2>&1
lua_csv "$invalid" >/dev/null 2>&1
monitor_output="$monitor_ready"$'\n'"$(cat <&9)"
exec 9<&-
publish_count="$(grep -c "\"PUBLISH\" \"$channel\"" <<<"$monitor_output")"
set_count="$(grep -c "\"SET\" \"$latest\"" <<<"$monitor_output")"
sadd_count="$(grep -c "\"SADD\" \"$index\"" <<<"$monitor_output")"
[[ "$publish_count" == 1 && "$set_count" == 1 && "$sadd_count" == 1 ]] \
  || fail "accepted/stale/error mutation counts publish=$publish_count set=$set_count sadd=$sadd_count"
[[ "$(redis --raw GET "$latest")" == "$accepted" ]] || fail "stale/error changed accepted payload"
[[ "$(redis SISMEMBER "$index" "$member")" == 1 ]] || fail "accepted member missing from index"
(( $(redis TTL "$latest") > 0 && $(redis TTL "$index") > 0 )) || fail "accepted TTL missing"
echo "PASS: accepted SET/SADD/PUBLISH once; stale/error zero mutation/publish; TTL/index valid"

echo "PASS: formal price-cache monotonic Redis contract ($run_id)"
