-- KEYS[1] = price:fubon-live-response:{market}:{code}; never a generic price or quote-detail key.
-- ARGV[1] = DB-canonical JSON envelope; ARGV[2] = exact nonnegative receipt epoch micros;
-- ARGV[3] = TTL seconds; ARGV[4] = market; ARGV[5] = stock code.
local function valid_micros(value)
  return type(value) == 'string' and string.match(value, '^[0-9]+$') ~= nil
end

local function strictly_newer(incoming, existing)
  if #incoming ~= #existing then return #incoming > #existing end
  return incoming > existing
end

local ttl = tonumber(ARGV[3])
if not valid_micros(ARGV[2]) or ttl == nil or ttl <= 0 or ttl ~= math.floor(ttl)
    or ARGV[4] == nil or ARGV[4] == '' or ARGV[5] == nil or ARGV[5] == '' then
  return 'FAILED'
end

local incoming_ok, incoming = pcall(cjson.decode, ARGV[1])
if not incoming_ok or type(incoming) ~= 'table' or incoming.receivedEpochMicros ~= ARGV[2]
    or type(incoming.counters) ~= 'table' or type(incoming.responseRow) ~= 'table'
    or incoming.responseRow.stockCode ~= ARGV[5] then
  return 'FAILED'
end

local current_payload = redis.call('GET', KEYS[1])
if current_payload then
  local current_ok, current = pcall(cjson.decode, current_payload)
  if current_ok and type(current) == 'table' and valid_micros(current.receivedEpochMicros) then
    if not strictly_newer(ARGV[2], current.receivedEpochMicros) then return 'STALE' end
  end
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
return 'WRITTEN'
