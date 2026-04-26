#!/bin/bash
# reserve-service에 SYNC tick을 발행하여 좌석 카운트를 DB 기준으로 보정
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic system.ticks \
  --property 'parse.headers=true' \
  --property 'headers.delimiter=|' \
  <<< '__TypeId__:com.epstein.practice.common.event.EventLifecycleTick|{"phase":"SYNC","tickedAt":'$(date +%s000)'}'

echo "SYNC tick sent"
