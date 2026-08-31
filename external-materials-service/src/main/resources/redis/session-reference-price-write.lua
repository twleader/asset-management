-- KEYS[1] = price:session-reference:{market}:{code}:{tradingDate}
-- ARGV[1] = validated JSON payload, ARGV[2] = fixed-width observedAt ordering token,
-- ARGV[3] = absolute PEXPIREAT epoch milliseconds.
-- This script intentionally performs no SET/PEXPIREAT for equal, older, or malformed values.
local function valid_order(value)
  return type(value) == 'string' and #value == 28 and string.match(value, '^%d+$') ~= nil
end

if not valid_order(ARGV[2]) then return 'INVALID' end
local expires = tonumber(ARGV[3])
if expires == nil or expires <= 0 or expires ~= math.floor(expires) then return 'INVALID' end

local incoming_ok, incoming = pcall(cjson.decode, ARGV[1])
if not incoming_ok or type(incoming) ~= 'table'
    or incoming.observedAtOrder ~= ARGV[2]
    or incoming.source ~= 'TWSE_MIS_Y'
    or type(incoming.price) ~= 'string'
    or type(incoming.observedAt) ~= 'string' then
  return 'INVALID'
end

local existing = redis.call('GET', KEYS[1])
if existing then
  local existing_ok, decoded = pcall(cjson.decode, existing)
  if not existing_ok or type(decoded) ~= 'table' or not valid_order(decoded.observedAtOrder) then
    return 'INVALID'
  end
  if ARGV[2] <= decoded.observedAtOrder then
    return 'STALE'
  end
end

redis.call('SET', KEYS[1], ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
return 'WRITTEN'
