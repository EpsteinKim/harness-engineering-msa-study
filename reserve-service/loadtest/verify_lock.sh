#!/bin/bash
# 부하 테스트 진행 중에 dispatch 락 키 패턴을 폴링.
# - 글로벌 락(`lock:queue-dispatch` 단독)이 잡히면 옛 코드 (변경 미적용)
# - per-event 락(`lock:queue-dispatch:{eventId}`)이 잡히면 새 코드 (변경 적용)
#
# 사용:
#   bash verify_lock.sh [반복횟수=20] [간격초=0.5]

ITERATIONS="${1:-20}"
SLEEP_SEC="${2:-0.5}"

echo "=== Redis lock:queue-dispatch* 폴링 (${ITERATIONS}회 × ${SLEEP_SEC}초 간격) ==="
echo "락 키 출현 패턴이 per-event 형태인지 확인."
echo

PER_EVENT_HITS=0
GLOBAL_HITS=0
EMPTY_HITS=0

for i in $(seq 1 "$ITERATIONS"); do
    KEYS=$(kubectl exec deploy/redis -- redis-cli KEYS "lock:queue-dispatch*" 2>/dev/null | tr -d '\r')
    if [ -z "$KEYS" ]; then
        EMPTY_HITS=$((EMPTY_HITS + 1))
        printf "[%2d] (idle)\n" "$i"
    elif echo "$KEYS" | grep -q ":"; then
        # lock:queue-dispatch:{N} 형태
        COUNT=$(echo "$KEYS" | wc -l | tr -d ' ')
        PER_EVENT_HITS=$((PER_EVENT_HITS + 1))
        printf "[%2d] per-event 락 %d개: %s\n" "$i" "$COUNT" "$(echo "$KEYS" | tr '\n' ' ')"
    else
        GLOBAL_HITS=$((GLOBAL_HITS + 1))
        printf "[%2d] 글로벌 락 (옛 코드): %s\n" "$i" "$KEYS"
    fi
    sleep "$SLEEP_SEC"
done

echo
echo "=== 요약 ==="
echo "per-event 락 발견: $PER_EVENT_HITS / $ITERATIONS"
echo "글로벌 락 발견:    $GLOBAL_HITS / $ITERATIONS"
echo "idle:              $EMPTY_HITS / $ITERATIONS"
echo
if [ "$PER_EVENT_HITS" -gt 0 ] && [ "$GLOBAL_HITS" -eq 0 ]; then
    echo "✓ 새 코드 적용됨 — per-event 락만 발견"
elif [ "$GLOBAL_HITS" -gt 0 ]; then
    echo "✗ 옛 코드로 보임 — 글로벌 락이 발견됨"
else
    echo "? 부하 부족 — idle만 관측됨. 부하 강도/시간을 늘려서 재시도"
fi
