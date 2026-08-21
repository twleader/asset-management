-- Task 350: the sole production primitive for mutating a latest quote key.
-- KEYS[1] latest quote STRING, KEYS[2] market index SET
-- ARGV[1] complete JSON payload, ARGV[2] index member,
-- ARGV[3] positive TTL seconds, ARGV[4] publish channel

local latest_key = KEYS[1]
local index_key = KEYS[2]
local payload = ARGV[1]
local member = ARGV[2]
local ttl_text = ARGV[3]
local channel = ARGV[4]

local function redis_type(key)
  local value = redis.call('TYPE', key)
  if type(value) == 'table' then
    return value.ok
  end
  return value
end

local latest_type = redis_type(latest_key)
if latest_type ~= 'none' and latest_type ~= 'string' then
  return redis.error_reply('T350 latest key must be string or none')
end
local index_type = redis_type(index_key)
if index_type ~= 'none' and index_type ~= 'set' then
  return redis.error_reply('T350 index key must be set or none')
end

local ttl = tonumber(ttl_text)
if ttl == nil or ttl <= 0 or ttl ~= math.floor(ttl) then
  return redis.error_reply('T350 TTL must be a positive integer')
end
if type(member) ~= 'string' or member == '' then
  return redis.error_reply('T350 index member must be non-empty')
end
if type(channel) ~= 'string' or channel == '' then
  return redis.error_reply('T350 publish channel must be non-empty')
end

local function trim(value)
  return string.match(value, '^%s*(.-)%s*$')
end

local function leap_year(year)
  return (year % 4 == 0 and year % 100 ~= 0) or year % 400 == 0
end

local function valid_date(value)
  if type(value) ~= 'string' or string.len(value) ~= 10 then
    return false
  end
  local year_text, month_text, day_text =
      string.match(value, '^(%d%d%d%d)%-(%d%d)%-(%d%d)$')
  if year_text == nil then
    return false
  end
  local year = tonumber(year_text)
  local month = tonumber(month_text)
  local day = tonumber(day_text)
  if year < 1 or month < 1 or month > 12 or day < 1 then
    return false
  end
  local days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}
  if month == 2 and leap_year(year) then
    return day <= 29
  end
  return day <= days[month]
end

local function normalize_datetime(value)
  if type(value) ~= 'string' then
    return nil
  end
  local length = string.len(value)
  if length ~= 16 and length ~= 19 and (length < 21 or length > 29) then
    return nil
  end
  if string.sub(value, 11, 11) ~= 'T' or not valid_date(string.sub(value, 1, 10)) then
    return nil
  end
  if string.sub(value, 14, 14) ~= ':' then
    return nil
  end
  local hour_text = string.sub(value, 12, 13)
  local minute_text = string.sub(value, 15, 16)
  if not string.match(hour_text, '^%d%d$') or not string.match(minute_text, '^%d%d$') then
    return nil
  end
  local hour = tonumber(hour_text)
  local minute = tonumber(minute_text)
  if hour > 23 or minute > 59 then
    return nil
  end
  local second_text = '00'
  local fraction = '000000000'
  if length >= 19 then
    if string.sub(value, 17, 17) ~= ':' then
      return nil
    end
    second_text = string.sub(value, 18, 19)
    if not string.match(second_text, '^%d%d$') or tonumber(second_text) > 59 then
      return nil
    end
  end
  if length > 19 then
    if string.sub(value, 20, 20) ~= '.' then
      return nil
    end
    local raw_fraction = string.sub(value, 21)
    if not string.match(raw_fraction, '^%d+$') or string.len(raw_fraction) > 9 then
      return nil
    end
    fraction = raw_fraction .. string.rep('0', 9 - string.len(raw_fraction))
  end
  return string.sub(value, 1, 10) .. 'T' .. hour_text .. ':' .. minute_text
      .. ':' .. second_text .. '.' .. fraction
end

local function status_value(value)
  if type(value) ~= 'string' then
    return nil
  end
  local normalized = trim(value)
  if normalized == '' then
    return nil
  end
  return normalized
end

local function status_rank(value)
  if value == 'VERIFIED_CLOSE' then return 3 end
  if value == 'LIVE' then return 2 end
  if value == 'PREVIOUS_CLOSE' then return 1 end
  return 0
end

local incoming_ok, incoming = pcall(cjson.decode, payload)
if not incoming_ok or type(incoming) ~= 'table' then
  return redis.error_reply('T350 incoming payload must be a JSON object')
end
local incoming_date = incoming['tradingDate']
if not valid_date(incoming_date) then
  return redis.error_reply('T350 incoming tradingDate is invalid')
end
local incoming_time_raw = incoming['updatedAt']
local incoming_time = normalize_datetime(incoming_time_raw)
if incoming_time == nil then
  return redis.error_reply('T350 incoming updatedAt is invalid')
end
local incoming_status = status_value(incoming['quoteStatus'])
if incoming_status == nil then
  return redis.error_reply('T350 incoming quoteStatus is invalid')
end

local existing_raw = redis.call('GET', latest_key)
local existing_date = ''
local existing_time_raw = ''
local existing_time = nil
local existing_status = ''
local existing_object = nil
local should_write = true

if existing_raw then
  local existing_ok, existing = pcall(cjson.decode, existing_raw)
  if existing_ok and type(existing) == 'table' then
    existing_object = existing
    if type(existing['tradingDate']) == 'string' then
      existing_date = existing['tradingDate']
    end
    if type(existing['updatedAt']) == 'string' then
      existing_time_raw = existing['updatedAt']
      existing_time = normalize_datetime(existing_time_raw)
    end
    local normalized_status = status_value(existing['quoteStatus'])
    if normalized_status ~= nil then
      existing_status = normalized_status
    end

    if valid_date(existing_date) then
      if existing_date > incoming_date then
        should_write = false
      elseif existing_date == incoming_date then
        local incoming_rank = status_rank(incoming_status)
        local existing_rank = status_rank(existing_status)
        if incoming_rank < existing_rank then
          should_write = false
        elseif existing_time ~= nil and incoming_time < existing_time then
          -- Time remains monotonic even when evidence status is upgraded.
          should_write = false
        end
      end
    end
  end
end

if not should_write then
  return {0, existing_date, existing_time_raw, existing_status}
end

-- Preserve legacy display metadata inside the same accepted atomic decision. Java must not
-- GET/compare/SET because that would reopen the stale-write race this script closes.
local function is_json_null(value)
  return value == nil or value == cjson.null
end

local function is_finite_number(value)
  return type(value) == 'number' and value == value
      and value ~= math.huge and value ~= -math.huge
end

local function is_positive_price(value)
  return is_finite_number(value) and value > 0
end

local function is_valid_volume(value)
  return is_finite_number(value) and value >= 0 and value <= 9007199254740991
      and value == math.floor(value)
end

local payload_changed = false

local function preserve_name()
  if existing_object == nil or type(existing_object['stockName']) ~= 'string'
      or trim(existing_object['stockName']) == '' then
    return
  end
  local incoming_name = incoming['stockName']
  if is_json_null(incoming_name)
      or (type(incoming_name) == 'string' and trim(incoming_name) == '') then
    incoming['stockName'] = existing_object['stockName']
    payload_changed = true
  end
end

local function preserve_same_date_price(field)
  if existing_object == nil or existing_date ~= incoming_date
      or not is_positive_price(existing_object[field])
      or not is_json_null(incoming[field]) then
    return
  end
  incoming[field] = existing_object[field]
  payload_changed = true
end

local function preserve_same_date_volume()
  if existing_object == nil or existing_date ~= incoming_date
      or not is_valid_volume(existing_object['volume'])
      or not is_json_null(incoming['volume']) then
    return
  end
  incoming['volume'] = existing_object['volume']
  payload_changed = true
end

if incoming_status == 'VERIFIED_CLOSE' then
  preserve_name()
  local existing_previous = existing_object ~= nil and existing_object['previousClose'] or nil
  if existing_date == incoming_date and is_json_null(incoming['previousClose'])
      and is_positive_price(existing_previous) and is_positive_price(incoming['price']) then
    incoming['previousClose'] = existing_previous
    local price_change = incoming['price'] - existing_previous
    incoming['priceChange'] = price_change
    local raw_percent = price_change * 100 / existing_previous
    if raw_percent >= 0 then
      incoming['changePercent'] = math.floor(raw_percent * 1000000 + 0.5) / 1000000
    else
      incoming['changePercent'] = math.ceil(raw_percent * 1000000 - 0.5) / 1000000
    end
    payload_changed = true
  end
elseif incoming_status == 'PREVIOUS_CLOSE' then
  -- previousClose intentionally is not preserved: the DB previous row is authoritative, even null.
  preserve_name()
  preserve_same_date_price('openPrice')
  preserve_same_date_price('highPrice')
  preserve_same_date_price('lowPrice')
  preserve_same_date_volume()
end

if payload_changed then
  payload = cjson.encode(incoming)
  -- Redis embeds Lua CJSON with at most 14 significant digits. Keep every accepted
  -- integer volume exact through JavaScript's safe-integer maximum after a merge.
  if is_valid_volume(incoming['volume']) then
    payload = string.gsub(payload, '("volume":)[%d%.eE%+%-]+',
        '%1' .. string.format('%.0f', incoming['volume']), 1)
  end
end

redis.call('SET', latest_key, payload, 'EX', ttl)
redis.call('SADD', index_key, member)
redis.call('EXPIRE', index_key, ttl)
redis.call('PUBLISH', channel, payload)
return {1, existing_date, existing_time_raw, existing_status}
