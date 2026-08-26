-- KEYS[1] = dedicated price:quote-detail:{market}:{code}; no generic price side effects.
-- ARGV[1] = exact JSON envelope, ARGV[2] = positive PostgreSQL canonical revision as decimal text,
-- ARGV[3] = TTL seconds, ARGV[4] = market, ARGV[5] = code.
-- Source priority and source time are decided in PostgreSQL before this script is called.
local function valid_revision(value)
  return type(value) == 'string' and string.match(value, '^[1-9][0-9]*$') ~= nil
end

local function strictly_newer(incoming, existing)
  if #incoming ~= #existing then
    return #incoming > #existing
  end
  return incoming > existing
end

local ttl = tonumber(ARGV[3])
if not valid_revision(ARGV[2]) or ttl == nil or ttl <= 0 or ttl ~= math.floor(ttl)
    or ARGV[4] == nil or ARGV[4] == '' or ARGV[5] == nil or ARGV[5] == '' then
  return 'FAILED'
end

local decoded_ok, incoming = pcall(cjson.decode, ARGV[1])
if not decoded_ok or type(incoming) ~= 'table'
    or incoming.canonicalRevision ~= ARGV[2]
    or type(incoming.sourceUpdatedEpochMicros) ~= 'string'
    or type(incoming.snapshot) ~= 'table'
    or incoming.snapshot.market ~= ARGV[4]
    or incoming.snapshot.stockCode ~= ARGV[5]
    or incoming.snapshot.sourceTime == nil then
  return 'FAILED'
end

local existing_payload = redis.call('GET', KEYS[1])
if existing_payload then
  local existing_ok, existing = pcall(cjson.decode, existing_payload)
  -- A legacy cache payload is intentionally a reader miss and may be replaced by a DB-canonical one.
  if existing_ok and type(existing) == 'table' and valid_revision(existing.canonicalRevision) then
    if not strictly_newer(ARGV[2], existing.canonicalRevision) then
      return 'STALE'
    end
  end
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
return 'WRITTEN'
