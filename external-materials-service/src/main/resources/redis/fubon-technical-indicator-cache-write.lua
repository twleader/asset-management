-- t398: atomic independent-group merge. Java validates hashes and complete schemas; the
-- byte-exact CAS makes that validation apply to precisely the value being merged here.
-- Indicator decimals are strings throughout; only epoch milliseconds use Lua numbers.
local raw = redis.call('GET', KEYS[1])
if (ARGV[1] == '0' and raw) or (ARGV[1] == '1' and raw ~= ARGV[2]) then
    return '{"retry":true}'
end
local incoming = cjson.decode(ARGV[3])
local current = raw and cjson.decode(raw) or nil
local meta = cjson.decode(ARGV[4])
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local output = current or incoming
local outcomes = {}
local latestExpiry = 0
for _, name in ipairs({'kdj', 'macd', 'bb'}) do
    local candidate = incoming.groups[name]
    local old = current and current.groups[name] or nil
    local m = meta[name]
    local available = candidate.payload ~= cjson.null
    local oldAvailable = old and old.payload ~= cjson.null
    local selected = old
    local expiry = old and m.existingExpiry or 0
    local outcome
    if available and m.incomingExpiry > now + 604800000 then
        outcome = 'SCHEMA_INVALID'
    elseif available and m.incomingExpiry <= now then
        outcome = 'EXPIRED'
    elseif not available then
        outcome = candidate.lastAttempt.status
    elseif not oldAvailable or candidate.sourceDate > old.sourceDate then
        outcome = 'WRITTEN'
        selected = candidate
        expiry = m.incomingExpiry
    elseif candidate.sourceDate < old.sourceDate then
        outcome = 'REJECTED_STALE'
    elseif candidate.contentHash == old.contentHash then
        outcome = 'UNCHANGED'
    else
        outcome = 'CONFLICT_NO_SOURCE_REVISION'
    end
    if not selected then
        selected = candidate
        if outcome ~= 'WRITTEN' then
            selected.payload = cjson.null
            selected.sourceDate = cjson.null
            selected.sourceTimestamp = cjson.null
            selected.contentHash = cjson.null
            selected.observedAt = cjson.null
            selected.expiresAt = cjson.null
            expiry = 0
        end
    end
    if not old or m.incomingAttempt >= m.existingAttempt then
        selected.lastAttempt = candidate.lastAttempt
        if outcome == 'REJECTED_STALE' or outcome == 'CONFLICT_NO_SOURCE_REVISION' or outcome == 'EXPIRED' then
            selected.lastAttempt = {
                status = outcome, reason = outcome, observedAt = candidate.lastAttempt.observedAt
            }
        elseif outcome == 'SCHEMA_INVALID' then
            selected.lastAttempt = {
                status = outcome, reason = 'TECHNICAL_SCHEMA_INVALID', observedAt = candidate.lastAttempt.observedAt
            }
        elseif outcome == 'UNCHANGED' then
            selected.lastAttempt = {
                status = 'UNCHANGED', reason = cjson.null, observedAt = candidate.lastAttempt.observedAt
            }
        end
    elseif old then
        selected.lastAttempt = old.lastAttempt
    end
    output.groups[name] = selected
    outcomes[name] = outcome
    if expiry > now and expiry > latestExpiry then latestExpiry = expiry end
end
if latestExpiry == 0 then latestExpiry = now + 900000 end
redis.call('SET', KEYS[1], cjson.encode(output))
redis.call('PEXPIREAT', KEYS[1], latestExpiry)
return cjson.encode(outcomes)
