-- KEYS[1]: event:{eventId}
-- KEYS[2]: reservation:waiting:{eventId}
-- ARGV[1]: userId
-- Returns: [eventExists (0/1), inQueue (0/1), seatSelectionType]

local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then return {0, 0, ''} end

local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
local inQueue = (score ~= false) and 1 or 0

local selType = redis.call('HGET', KEYS[1], 'seatSelectionType') or 'SECTION_SELECT'
return {exists, inQueue, selType}
