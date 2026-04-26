-- KEYS[1]: reservation:waiting:{eventId}
-- KEYS[2]: reservation:dispatch:{eventId}
-- KEYS[3]: reservation:processing:{eventId}
-- ARGV[1]: count
-- Returns: array of [userId, score, dispatchData, ...]

local entries = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
if #entries == 0 then
    return {}
end

local result = {}
for i = 1, #entries, 2 do
    local userId = entries[i]
    local score = entries[i + 1]
    local data = redis.call('HGET', KEYS[2], userId) or ''
    redis.call('HDEL', KEYS[2], userId)
    redis.call('HSET', KEYS[3], userId, score .. '|' .. data)
    table.insert(result, userId)
    table.insert(result, score)
    table.insert(result, data)
end
return result
