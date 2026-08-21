-- KEYS[1] price key; KEYS[2] market index set.
-- ARGV: authorized, allowTakeover, incomingJson, code, market, tradingDate,
--       updatedAt, ttlSeconds, publishChannel.
-- The authorization check must remain the first Redis operation, including before GET.
if ARGV[1] ~= '1' then
    return 'MARKET_CLOSED'
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
    if month < 1 or month > 12 or day < 1 then
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

local incomingTuple = validDate(ARGV[6]) and localDateTimeKey(ARGV[7], ARGV[6]) or nil
if not incomingTuple then
    return 'WRITE_FAILED'
end

local currentJson = redis.call('GET', KEYS[1])
local outcome = 'WRITTEN'

if currentJson then
    local decodedOk, current = pcall(cjson.decode, currentJson)
    local currentTuple = decodedOk and type(current) == 'table'
        and validDate(current.tradingDate)
        and localDateTimeKey(current.updatedAt, current.tradingDate) or nil
    if not decodedOk or type(current) ~= 'table'
        or type(current.stockCode) ~= 'string' or current.stockCode ~= ARGV[4]
        or type(current.market) ~= 'string' or current.market ~= ARGV[5]
        or type(current.source) ~= 'string' or current.source == ''
        or type(current.closed) ~= 'boolean'
        or type(current.quoteStatus) ~= 'string' or current.quoteStatus == ''
        or not currentTuple then
        return 'CURRENT_MALFORMED'
    end

    if current.source == 'FUBON_INTRADAY' then
        if ARGV[6] < current.tradingDate
            or (ARGV[6] == current.tradingDate and incomingTuple <= currentTuple) then
            return 'STALE_OR_EQUAL'
        end
    else
        if ARGV[2] ~= '1' then
            return 'STALE_OR_EQUAL'
        end
        local newerDate = ARGV[6] > current.tradingDate
        local sameDayMisLive = ARGV[6] == current.tradingDate
            and current.source == 'TWSE'
            and current.closed == false
            and current.quoteStatus == 'LIVE'
        if not newerDate and not sameDayMisLive then
            return 'STALE_OR_EQUAL'
        end
        outcome = 'PROVIDER_TAKEOVER'
    end
end

redis.call('SET', KEYS[1], ARGV[3], 'EX', tonumber(ARGV[8]))
redis.call('SADD', KEYS[2], ARGV[4])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[8]))
redis.call('PUBLISH', ARGV[9], ARGV[3])
return outcome
