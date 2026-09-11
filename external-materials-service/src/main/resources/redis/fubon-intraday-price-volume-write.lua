-- Task425: only same-Taipei-day and strictly newer observedAt may replace a dedicated snapshot.
local candidate = cjson.decode(ARGV[1])
local function fixed_utc_micros(value)
  return type(value) == 'string'
      and string.match(value, '^%d%d%d%d%-%d%d%-%d%dT%d%d:%d%d:%d%d%.%d%d%d%d%d%dZ$') ~= nil
end
if candidate.sourceDate ~= ARGV[3] or not fixed_utc_micros(candidate.observedAt) then return 'FAILED' end
local old = redis.call('GET', KEYS[1])
if old then
  local ok, previous = pcall(cjson.decode, old)
  if not ok or previous.sourceDate ~= ARGV[3] or not fixed_utc_micros(previous.observedAt) then return 'REJECTED_STALE' end
  -- Both Python and Java emit/validate fixed microsecond UTC ISO-8601 Z strings.
  -- Their lexical ordering is therefore their instant ordering; never tonumber a timestamp.
  if previous.observedAt >= candidate.observedAt then return 'REJECTED_STALE' end
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 'WRITTEN'
