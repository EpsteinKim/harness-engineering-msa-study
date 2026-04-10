-- 부하 테스트용 데이터 시드
-- 실행: psql -U <user> -d <db> -f seed_test_data.sql

-- 기존 테스트 데이터 정리
DELETE FROM seat WHERE event_id IN (SELECT id FROM event WHERE name LIKE 'LoadTest%');
DELETE FROM event WHERE name LIKE 'LoadTest%';

-- SECTION_SELECT 이벤트 (id=1에 맞추려면 기존 데이터 확인 필요)
INSERT INTO event (name, event_time, status, ticket_open_time, ticket_close_time, seat_selection_type, created_at)
VALUES (
    'LoadTest Concert',
    NOW() + INTERVAL '30 days',
    'OPEN',
    NOW() - INTERVAL '1 hour',
    NOW() + INTERVAL '29 days',
    'SECTION_SELECT',
    NOW()
);

-- 마지막 삽입된 이벤트 ID 가져오기
-- 좌석 생성: A~D 구역, 각 50석 = 총 200석
DO $$
DECLARE
    v_event_id BIGINT;
    v_section CHAR(1);
    v_sections CHAR(1)[] := ARRAY['A', 'B', 'C', 'D'];
    v_seat_num INT;
BEGIN
    SELECT id INTO v_event_id FROM event WHERE name = 'LoadTest Concert' LIMIT 1;

    FOREACH v_section IN ARRAY v_sections LOOP
        FOR v_seat_num IN 1..50 LOOP
            INSERT INTO seat (event_id, seat_number, section, status, version)
            VALUES (v_event_id, v_section || '-' || v_seat_num, v_section, 'AVAILABLE', 0);
        END LOOP;
    END LOOP;

    RAISE NOTICE 'Created 200 seats for event_id=%', v_event_id;
END $$;

-- 확인
SELECT e.id, e.name, e.status, e.seat_selection_type,
       COUNT(s.id) AS total_seats,
       COUNT(s.id) FILTER (WHERE s.status = 'AVAILABLE') AS available_seats
FROM event e
LEFT JOIN seat s ON s.event_id = e.id
WHERE e.name = 'LoadTest Concert'
GROUP BY e.id, e.name, e.status, e.seat_selection_type;
