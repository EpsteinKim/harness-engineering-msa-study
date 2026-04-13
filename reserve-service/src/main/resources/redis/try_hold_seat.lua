-- KEYS[1] = seat:{eventId}
-- ARGV[1] = seatId, ARGV[2] = userId, ARGV[3] = nowMs, ARGV[4] = ttlMs
local cur = redis.call('HGET', KEYS[1], ARGV[1])
if not cur then return 0 end
local parts = {}
for w in string.gmatch(cur, "([^:]+)") do parts[#parts+1] = w end
if #parts < 3 then return 0 end
local status = parts[3]
if status == 'RESERVED' then return 0 end
if status == 'AVAILABLE' or (status == 'HELD' and #parts >= 5 and tonumber(parts[5]) < tonumber(ARGV[3])) then
    local newVal = parts[1]..':'..parts[2]..':HELD:'..ARGV[2]..':'..(tonumber(ARGV[3]) + tonumber(ARGV[4]))
    redis.call('HSET', KEYS[1], ARGV[1], newVal)
    return 1
end
return 0
