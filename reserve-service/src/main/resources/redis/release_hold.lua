-- KEYS[1] = seat:{eventId}
-- ARGV[1] = seatId, ARGV[2] = userId
local cur = redis.call('HGET', KEYS[1], ARGV[1])
if not cur then return 0 end
local parts = {}
for w in string.gmatch(cur, "([^:]+)") do parts[#parts+1] = w end
if #parts < 5 then return 0 end
if parts[3] == 'HELD' and parts[4] == ARGV[2] then
    redis.call('HSET', KEYS[1], ARGV[1], parts[1]..':'..parts[2]..':AVAILABLE')
    return 1
end
return 0
