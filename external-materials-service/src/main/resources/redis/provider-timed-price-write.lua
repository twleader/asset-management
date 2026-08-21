-- KEYS[1] price key; KEYS[2] market index set.
-- ARGV: authorized, allowTakeover, incomingJson, code, market, tradingDate,
--       updatedAt, ttlSeconds, publishChannel.
-- Authorization must stay ahead of every Redis operation, including TYPE and GET.
if ARGV[1] ~= '1' then
    return 'MARKET_CLOSED'
end

-- Every remaining failure is a stable, mutation-free WRITE_FAILED outcome.
if #KEYS ~= 2 or #ARGV ~= 9 then
    return 'WRITE_FAILED'
end

local function trim(value)
    if type(value) ~= 'string' then
        return nil
    end
    return string.match(value, '^%s*(.-)%s*$')
end

local function nonBlank(value)
    local normalized = trim(value)
    return normalized ~= nil and normalized ~= ''
end

local function validDate(value)
    if type(value) ~= 'string' then
        return false
    end
    local yearText, monthText, dayText = string.match(
        value, '^(%d%d%d%d)%-(%d%d)%-(%d%d)$')
    if not yearText then
        return false
    end
    local year = tonumber(yearText)
    local month = tonumber(monthText)
    local day = tonumber(dayText)
    if year < 1 or month < 1 or month > 12 or day < 1 then
        return false
    end
    local days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}
    if month == 2 and (year % 400 == 0 or (year % 4 == 0 and year % 100 ~= 0)) then
        days[2] = 29
    end
    return day <= days[month]
end

-- Return a fixed-width lexical key so equivalent Java LocalDateTime spellings
-- such as 13:00 and 13:00:00 compare equal. Only ISO local time with at most
-- nanosecond precision is accepted; offsets, Z and trailing text are malformed.
local function localDateTimeKey(value, date)
    if type(value) ~= 'string' or string.sub(value, 1, 10) ~= date then
        return nil
    end
    local hourText, minuteText, rest = string.match(
        value, '^%d%d%d%d%-%d%d%-%d%dT(%d%d):(%d%d)(.*)$')
    if not hourText then
        return nil
    end
    local hour = tonumber(hourText)
    local minute = tonumber(minuteText)
    if hour > 23 or minute > 59 then
        return nil
    end

    local secondText = '00'
    local fraction = ''
    if rest ~= '' then
        local parsedSecond, suffix = string.match(rest, '^:(%d%d)(.*)$')
        if not parsedSecond or tonumber(parsedSecond) > 59 then
            return nil
        end
        secondText = parsedSecond
        if suffix ~= '' then
            fraction = string.match(suffix, '^%.(%d+)$')
            if not fraction or string.len(fraction) > 9 then
                return nil
            end
        end
    end
    fraction = fraction .. string.rep('0', 9 - string.len(fraction))
    return string.gsub(date, '%-', '') .. hourText .. minuteText .. secondText .. fraction
end

local latestKey = KEYS[1]
local indexKey = KEYS[2]
local allowTakeover = ARGV[2]
local incomingJson = ARGV[3]
local code = ARGV[4]
local market = ARGV[5]
local tradingDate = ARGV[6]
local updatedAt = ARGV[7]
local ttlText = ARGV[8]
local publishChannel = ARGV[9]

if not nonBlank(latestKey) or not nonBlank(indexKey) or latestKey == indexKey
    or (allowTakeover ~= '0' and allowTakeover ~= '1')
    or not nonBlank(code) or not nonBlank(market) or not nonBlank(publishChannel) then
    return 'WRITE_FAILED'
end

local ttl = type(ttlText) == 'string' and string.match(ttlText, '^%d+$')
    and tonumber(ttlText) or nil
if not ttl or ttl <= 0 or ttl > 9007199254740991 or ttl ~= math.floor(ttl) then
    return 'WRITE_FAILED'
end

-- cjson represents both objects and arrays as tables. Requiring object delimiters
-- prevents an array from passing the table check before the fixed field contract.
if type(incomingJson) ~= 'string'
    or not string.match(incomingJson, '^%s*{')
    or not string.match(incomingJson, '}%s*$') then
    return 'WRITE_FAILED'
end
local incomingOk, incoming = pcall(cjson.decode, incomingJson)
if not incomingOk or type(incoming) ~= 'table'
    or incoming.stockCode ~= code
    or incoming.market ~= market
    or incoming.tradingDate ~= tradingDate
    or incoming.updatedAt ~= updatedAt
    or incoming.source ~= 'FUBON_INTRADAY'
    or incoming.quoteStatus ~= 'LIVE'
    or incoming.closed ~= false then
    return 'WRITE_FAILED'
end

local incomingTuple = validDate(tradingDate)
    and localDateTimeKey(updatedAt, tradingDate) or nil
if not incomingTuple then
    return 'WRITE_FAILED'
end

local function redisType(key)
    local value = redis.call('TYPE', key)
    if type(value) == 'table' then
        return value.ok
    end
    return value
end

-- Both types are checked before GET and, because the script is atomic, cannot change
-- between this preflight and SET/SADD. This prevents partial SET-then-WRONGTYPE writes.
local latestType = redisType(latestKey)
local indexType = redisType(indexKey)
if (latestType ~= 'none' and latestType ~= 'string')
    or (indexType ~= 'none' and indexType ~= 'set') then
    return 'WRITE_FAILED'
end

local function statusRank(value)
    if value == 'VERIFIED_CLOSE' then return 3 end
    if value == 'LIVE' then return 2 end
    if value == 'PREVIOUS_CLOSE' then return 1 end
    return 0
end

local currentJson = redis.call('GET', latestKey)
local outcome = 'WRITTEN'

if currentJson then
    local decodedOk, current = pcall(cjson.decode, currentJson)
    local currentStatus = decodedOk and type(current) == 'table'
        and current.quoteStatus or nil
    local currentTuple = decodedOk and type(current) == 'table'
        and validDate(current.tradingDate)
        and localDateTimeKey(current.updatedAt, current.tradingDate) or nil
    if not decodedOk or type(current) ~= 'table'
        or type(current.stockCode) ~= 'string' or current.stockCode ~= code
        or type(current.market) ~= 'string' or current.market ~= market
        or not nonBlank(current.source)
        or type(current.closed) ~= 'boolean'
        or not nonBlank(currentStatus)
        or not currentTuple then
        return 'CURRENT_MALFORMED'
    end

    if tradingDate < current.tradingDate then
        return 'STALE_OR_EQUAL'
    end
    if tradingDate == current.tradingDate then
        -- Date first, then evidence priority, then a strict time watermark for every source.
        -- Incoming is always LIVE/rank 2, so it cannot downgrade a same-day verified close.
        if statusRank('LIVE') < statusRank(currentStatus) or incomingTuple <= currentTuple then
            return 'STALE_OR_EQUAL'
        end
    end

    if current.source ~= 'FUBON_INTRADAY' then
        if allowTakeover ~= '1' then
            return 'STALE_OR_EQUAL'
        end
        local newerDate = tradingDate > current.tradingDate
        local sameDayNewerMisLive = tradingDate == current.tradingDate
            and current.source == 'TWSE'
            and current.closed == false
            and currentStatus == 'LIVE'
        if not newerDate and not sameDayNewerMisLive then
            return 'STALE_OR_EQUAL'
        end
        outcome = 'PROVIDER_TAKEOVER'
    end
end

redis.call('SET', latestKey, incomingJson, 'EX', ttlText)
redis.call('SADD', indexKey, code)
redis.call('EXPIRE', indexKey, ttlText)
redis.call('PUBLISH', publishChannel, incomingJson)
return outcome
