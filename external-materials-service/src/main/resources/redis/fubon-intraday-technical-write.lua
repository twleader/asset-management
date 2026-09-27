local incoming = cjson.decode(ARGV[1])
local previous_raw = redis.call('GET', KEYS[1])

local function source_time(frame)
  local value = frame.sourceTimestamp
  if value == nil or value == cjson.null then value = frame.observedAt end
  if type(value) ~= 'string' then return nil end
  return value
end

if previous_raw then
  local decoded, previous = pcall(cjson.decode, previous_raw)
  if not decoded or type(previous) ~= 'table'
      or previous.symbol ~= incoming.symbol or previous.market ~= incoming.market or previous.provider ~= incoming.provider
      or type(previous.oneMinute) ~= 'table' or type(previous.fiveMinute) ~= 'table'
      or type(incoming.oneMinute) ~= 'table' or type(incoming.fiveMinute) ~= 'table' then
    return 'FAILED'
  end

  local old_one_date = previous.oneMinute.sourceDate
  local old_five_date = previous.fiveMinute.sourceDate
  local new_one_date = incoming.oneMinute.sourceDate
  local new_five_date = incoming.fiveMinute.sourceDate
  if type(old_one_date) ~= 'string' or type(old_five_date) ~= 'string'
      or type(new_one_date) ~= 'string' or type(new_five_date) ~= 'string'
      or old_one_date ~= old_five_date or new_one_date ~= new_five_date then
    return 'FAILED'
  end

  if new_one_date < old_one_date then return 'REJECTED_STALE' end
  if new_one_date == old_one_date then
    local old_one = source_time(previous.oneMinute)
    local new_one = source_time(incoming.oneMinute)
    local old_five = source_time(previous.fiveMinute)
    local new_five = source_time(incoming.fiveMinute)
    if old_one == nil or new_one == nil or old_five == nil or new_five == nil then return 'FAILED' end
    if new_one < old_one or new_five < old_five then return 'REJECTED_STALE' end
  end
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 'WRITTEN'
