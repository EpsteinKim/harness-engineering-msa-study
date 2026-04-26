#!/bin/bash
echo "=== Kafka Consumer LAG ==="
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group reserve-service --describe 2>&1 | grep -v "^$"

echo ""
echo "=== Topic 메시지 수 ==="
for topic in reserve.queue seat.events payment.events payment.commands system.ticks event.lifecycle; do
  count=$(kubectl exec kafka-0 -- /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 --topic "$topic" 2>/dev/null | awk -F: '{sum+=$3} END {print sum}')
  printf "%-25s %s\n" "$topic" "${count:-0}"
done

echo ""
echo "=== Outbox 잔여 ==="
docker exec backend-postgres-1 psql -U harness -d reserve_db -t -c "SELECT count(*) FROM outbox" 2>/dev/null | tr -d ' '

echo ""
echo "=== Redis 대기열 ==="
REDIS_POD=$(kubectl get pod -l app=redis -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
if [ -n "$REDIS_POD" ]; then
  for key in $(kubectl exec "$REDIS_POD" -- redis-cli KEYS "reservation:waiting:*" 2>/dev/null); do
    count=$(kubectl exec "$REDIS_POD" -- redis-cli ZCARD "$key" 2>/dev/null)
    printf "%-35s %s\n" "$key" "$count"
  done
  echo ""
  echo "=== Redis Processing ==="
  for key in $(kubectl exec "$REDIS_POD" -- redis-cli KEYS "reservation:processing:*" 2>/dev/null); do
    count=$(kubectl exec "$REDIS_POD" -- redis-cli HLEN "$key" 2>/dev/null)
    printf "%-35s %s\n" "$key" "$count"
  done
fi
