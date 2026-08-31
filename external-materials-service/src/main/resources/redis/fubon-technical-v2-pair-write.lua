-- Task408 v2: dual-key CAS, Redis-TIME absolute expiry, and full-capture fence.
-- ARGV: expected D generation/absence, expected W generation/absence, D document, W document,
--       freshUntil epoch millis, forceFubon (0/1).
local absent = '__ABSENT__'
local rawD = redis.call('GET', KEYS[1])
local rawW = redis.call('GET', KEYS[2])
local function generation(raw)
  if not raw then return absent end
  local ok, doc = pcall(cjson.decode, raw)
  if not ok or not doc.bundleGeneration then return '__CORRUPT__' end
  return doc.bundleGeneration
end
if generation(rawD) ~= ARGV[1] or generation(rawW) ~= ARGV[2] then return 'CAS_MISS' end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local freshUntil = tonumber(ARGV[5])
if not freshUntil or now >= freshUntil then return 'EXPIRED' end
local okD, incomingD = pcall(cjson.decode, ARGV[3])
local okW, incomingW = pcall(cjson.decode, ARGV[4])
if not okD or not okW then return 'REJECT_INVALID_INCOMING' end

-- `Instant.toString()` is a UTC RFC3339 instant with an optional fractional
-- component.  Do not compare those strings lexically: `...00Z` sorts after
-- `...00.001Z`, which would let an old same-vector capture defeat C2.  The
-- cache protocol uses millisecond PEXPIREAT, so a millisecond epoch is the
-- exact comparison domain here.
local function epoch_millis(value)
  if type(value) ~= 'string' then return nil end
  local year, month, day, hour, minute, second, fraction =
      string.match(value, '^(%d%d%d%d)%-(%d%d)%-(%d%d)T(%d%d):(%d%d):(%d%d)%.?(%d*)Z$')
  if not year then return nil end
  year = tonumber(year); month = tonumber(month); day = tonumber(day)
  hour = tonumber(hour); minute = tonumber(minute); second = tonumber(second)
  if month < 1 or month > 12 or day < 1 or hour > 23 or minute > 59 or second > 59 then return nil end
  local month_days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}
  local leap = (year % 4 == 0 and year % 100 ~= 0) or year % 400 == 0
  if month == 2 and leap then month_days[2] = 29 end
  if day > month_days[month] then return nil end
  local days = 365 * (year - 1970) + math.floor((year - 1969) / 4)
      - math.floor((year - 1901) / 100) + math.floor((year - 1601) / 400)
  for index = 1, month - 1 do days = days + month_days[index] end
  days = days + day - 1
  local millis = 0
  if fraction and #fraction > 0 then
    if #fraction >= 3 then millis = tonumber(string.sub(fraction, 1, 3))
    else millis = tonumber(fraction) * (10 ^ (3 - #fraction)) end
  end
  return (((days * 24 + hour) * 60 + minute) * 60 + second) * 1000 + millis
end

local function valid_pair_document(doc, timeframe, expectedProfiles)
  if type(doc) ~= 'table' or doc.schemaVersion ~= 2 or doc.timeframe ~= timeframe
      or type(doc.bundleGeneration) ~= 'string' or type(doc.captureId) ~= 'string'
      or type(doc.origin) ~= 'string' or type(doc.binding) ~= 'string'
      or type(doc.profiles) ~= 'table' then return false end
  local oldest = epoch_millis(doc.oldestObservedAt)
  local until = epoch_millis(doc.freshUntil)
  if not oldest or not until or until ~= oldest + 100000 then return false end
  if doc.origin == 'LOCAL_CALCULATED' then
    -- Local calculation is carried exclusively in localSnapshot.  Empty
    -- provider profiles are intentional: inventing synthetic Fubon facts here
    -- would contaminate provenance and source-vector fencing.
    return doc.binding == 'BOUND_CONTEXT' and type(doc.localSnapshot) == 'table' and #doc.profiles == 0
  end
  if doc.origin ~= 'FUBON_SDK' or #doc.profiles ~= expectedProfiles or doc.localSnapshot ~= cjson.null then return false end
  for i, profile in ipairs(doc.profiles) do
    if type(profile) ~= 'table' or type(profile.profileId) ~= 'string'
        or type(profile.sourceDate) ~= 'string' or type(profile.contentHash) ~= 'string' then return false end
  end
  return true
end
if not valid_pair_document(incomingD, 'D', 10) or not valid_pair_document(incomingW, 'W', 7)
    or incomingD.bundleGeneration ~= incomingW.bundleGeneration
    or incomingD.captureId ~= incomingW.captureId
    or incomingD.origin ~= incomingW.origin
    or incomingD.binding ~= incomingW.binding
    or incomingD.contextFingerprint ~= incomingW.contextFingerprint
    or incomingD.oldestObservedAt ~= incomingW.oldestObservedAt
    or incomingD.freshUntil ~= incomingW.freshUntil then return 'REJECT_INVALID_INCOMING' end
if ARGV[6] == '1' and (incomingD.origin ~= 'FUBON_SDK' or incomingD.binding ~= 'UNBOUND_FUBON_SOURCE') then
  return 'REJECT_INVALID_INCOMING'
end
local currentD, currentW = nil, nil
if rawD then
  local valid, decoded = pcall(cjson.decode, rawD)
  if not valid then return 'REJECT_CORRUPT_PAIR' end
  currentD = decoded
end
if rawW then
  local valid, decoded = pcall(cjson.decode, rawW)
  if not valid then return 'REJECT_CORRUPT_PAIR' end
  currentW = decoded
end
if (currentD and not currentW) or (currentW and not currentD) then return 'REJECT_CORRUPT_PAIR' end
if currentD and (not valid_pair_document(currentD, 'D', 10) or not valid_pair_document(currentW, 'W', 7)
    or currentD.bundleGeneration ~= currentW.bundleGeneration
    or currentD.captureId ~= currentW.captureId
    or currentD.origin ~= currentW.origin
    or currentD.binding ~= currentW.binding
    or currentD.contextFingerprint ~= currentW.contextFingerprint
    or currentD.oldestObservedAt ~= currentW.oldestObservedAt
    or currentD.freshUntil ~= currentW.freshUntil) then return 'REJECT_CORRUPT_PAIR' end
local function current_bound_fubon_fresh(current)
  -- PEXPIREAT should remove the key at freshUntil, but an independently
  -- corrupted/manual key must not make a stale source block a local result.
  local until = epoch_millis(current.freshUntil)
  return current and current.origin == 'FUBON_SDK' and current.binding == 'BOUND_CONTEXT'
      and until and now < until
      and redis.call('PTTL', KEYS[1]) > 0 and redis.call('PTTL', KEYS[2]) > 0
end
local function dominates(incoming, current)
  if not current then return true end
  if incoming.origin == 'LOCAL_CALCULATED' and current_bound_fubon_fresh(current) then
    return false
  end
  if incoming.origin == 'FUBON_SDK' and current.origin == 'LOCAL_CALCULATED' and ARGV[6] ~= '1' then
    return false
  end
  if incoming.origin ~= 'FUBON_SDK' or current.origin ~= 'FUBON_SDK' then return true end
  local same = true
  for i, value in ipairs(incoming.profiles) do
    local old = current.profiles[i]
    if not old or value.profileId ~= old.profileId then return false end
    if value.sourceDate < old.sourceDate then return false end
    if value.sourceDate == old.sourceDate and value.contentHash ~= old.contentHash then return false end
    if value.sourceDate ~= old.sourceDate or value.contentHash ~= old.contentHash then same = false end
  end
  if same then
    -- Same source-vector is not sufficient when an older delayed C1 changes
    -- only binding/context: compare capture identity, min observed time and
    -- both document and Redis absolute deadlines before accepting it.  A
    -- same-capture reproject is legitimate because it preserves the exact
    -- deadline; a new capture must prove a strictly newer oldest observation.
    local incomingOldest = epoch_millis(incoming.oldestObservedAt)
    local currentOldest = epoch_millis(current.oldestObservedAt)
    local incomingUntil = epoch_millis(incoming.freshUntil)
    local currentUntil = epoch_millis(current.freshUntil)
    if not incomingOldest or not currentOldest or not incomingUntil or not currentUntil then return false end
    local remainingD = redis.call('PTTL', KEYS[1])
    local remainingW = redis.call('PTTL', KEYS[2])
    if remainingD < 0 or remainingW < 0 then return false end
    local residentDeadline = now + math.min(remainingD, remainingW)
    if incoming.captureId == current.captureId then
      if incomingOldest ~= currentOldest or incomingUntil ~= currentUntil then return false end
      -- A BOUND reproject may change its matching fingerprint without
      -- extending freshness.  A delayed scheduler UNBOUND projection of the
      -- same capture must not erase that more specific context binding.
      return not (current.binding == 'BOUND_CONTEXT' and incoming.binding == 'UNBOUND_FUBON_SOURCE')
    end
    if incomingOldest <= currentOldest or incomingUntil <= currentUntil then return false end
    -- Redis PTTL is the physical absolute-deadline fence.  It prevents a
    -- corrupted/manual TTL from being shortened by a nominally newer document.
    return incomingUntil > residentDeadline
  end
  return true
end
if not dominates(incomingD, currentD) or not dominates(incomingW, currentW) then return 'REJECTED_FENCE' end
redis.call('SET', KEYS[1], ARGV[3])
redis.call('SET', KEYS[2], ARGV[4])
redis.call('PEXPIREAT', KEYS[1], freshUntil)
redis.call('PEXPIREAT', KEYS[2], freshUntil)
return 'WRITTEN'
