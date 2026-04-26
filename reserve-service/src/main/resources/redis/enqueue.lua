-- KEYS[1]: reservation:waiting:{eventId}
-- KEYS[2]: reservation:dispatch:{eventId}
-- KEYS[3]: event:{eventId}
-- ARGV[1]: userId
-- ARGV[2]: score (timestamp)
-- ARGV[3]: dispatchValue ("seatId|section")
-- ARGV[4]: section ("" if none)
-- Returns: 1 (success), 0 (already in queue), -1 (sold out), -2 (section full)

-- 1. 잔여석 확인
local remaining = tonumber(redis.call('HGET', KEYS[3], 'remainingSeats') or '0')
if remaining <= 0 then return -1 end

-- 2. 섹션별 잔여석 확인 (SECTION_SELECT)
if ARGV[4] ~= '' then
    local secKey = 'section:' .. ARGV[4] .. ':available'
    local secAvail = tonumber(redis.call('HGET', KEYS[3], secKey) or '0')
    if secAvail <= 0 then return -2 end
end

-- 3. 대기열 등록 (NX: 이미 있으면 실패)
local added = redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
if added == 0 then return 0 end

-- 4. 잔여석 선차감
redis.call('HINCRBY', KEYS[3], 'remainingSeats', -1)
if ARGV[4] ~= '' then
    redis.call('HINCRBY', KEYS[3], 'section:' .. ARGV[4] .. ':available', -1)
end

-- 5. 디스패치 데이터 저장
redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
return 1
