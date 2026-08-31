-- Task408 non-TW market-safe LOCAL snapshot: one-key CAS with Redis-TIME
-- absolute expiry.  ARGV: expected raw/absence, normalized document,
-- freshUntil epoch millis.
local absent = '__ABSENT__'
local raw = redis.call('GET', KEYS[1])
if (not raw and ARGV[1] ~= absent) or (raw and raw ~= ARGV[1]) then return 'CAS_MISS' end

local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local freshUntil = tonumber(ARGV[3])
if not freshUntil or now >= freshUntil then return 'EXPIRED' end

local ok, incoming = pcall(cjson.decode, ARGV[2])
if not ok or type(incoming) ~= 'table' or incoming.schemaVersion ~= 1
    or incoming.origin ~= 'LOCAL_CALCULATED' or incoming.binding ~= 'BOUND_CONTEXT'
    or type(incoming.market) ~= 'string' or type(incoming.code) ~= 'string'
    or type(incoming.contextFingerprint) ~= 'string' or incoming.contextFingerprint == ''
    or type(incoming.freshUntilEpochMillis) ~= 'number'
    or incoming.freshUntilEpochMillis ~= freshUntil
    or type(incoming.localSnapshot) ~= 'table' then
  return 'REJECT_INVALID_INCOMING'
end

redis.call('SET', KEYS[1], ARGV[2])
redis.call('PEXPIREAT', KEYS[1], freshUntil)
return 'WRITTEN'
