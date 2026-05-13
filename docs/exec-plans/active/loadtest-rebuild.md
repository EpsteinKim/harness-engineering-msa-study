# 부하테스트 시나리오·메트릭 재구축 (P4)

> 트래픽 증대 분리 plan 모음 중 **가장 먼저 실행**. 이후 모든 plan(P1·P2·P3)의 효과 검증 기준이 된다. 본 plan 자체는 시스템 동작을 바꾸지 않는다.

## 목적

930 RPS 측정(2026-04-10) 이후 enqueue 선차감, Saga afterCommit, K8s 전환, 중복 예약 Redis 이관, gateway 튜닝 등 구조 변화가 누적되어 현 천장 위치가 불명. 시나리오에 폴링 사용자 모델이 없어 10만 명 동시 폴링 부하를 재현하지 못한다.

## 변경 범위

- `reserve-service/loadtest/locustfile.py` 또는 신규 `loadtest/k6/` 스크립트
- `reserve-service/loadtest/results/{YYYY-MM-DD}-{tag}.md` 결과 표준 양식
- (선택) Prometheus + Grafana 컴포즈 설정. 없으면 우선 애플리케이션 actuator + 컨테이너 metric을 CSV로 떨어뜨리는 수준으로 시작
- 코드 변경 없음 (계측 endpoint가 부족하면 actuator 노출만 추가)

## 핵심 결정

1. User class 재구성:
   - `SectionSelectUser` / `SeatPickUser` 비율 재산정(현 트래픽 구조 반영)
   - **`PollingUser` 신설**: 대기열 진입 후 응답의 `nextPollAfterMs`(P1 도입 전엔 고정 5초)를 따르되, **프론트엔드와 동일하게 클라이언트 측 ±20% jitter 적용**(서버는 jitter 안 함). 실제 프론트 트래픽 패턴 재현 목적
   - `BurstUser` 유지(이벤트 오픈 직후 동시 진입 시뮬)
2. 램프업: 1만 → 5만 → 10만, 각 단계 3분 유지 + warm-up 30초
3. 측정 항목 표준화:
   - 애플리케이션: RPS, p50/p95/p99, 에러율, endpoint별 분리
   - DB: HikariCP active/idle/wait, slow query
   - Redis: ops/sec, p99 latency, evicted_keys
   - Kafka: producer record-send-rate, consumer lag(토픽별)
   - JVM: GC pause, heap used, thread count
   - Container: CPU throttle, RSS, network
4. 결과 양식: 시나리오·실행 환경(스펙·replicas)·KPI 표·병목 지표 그래프·결론.
5. **baseline 1회 측정**을 본 plan의 산출물에 포함. 이후 plan 효과는 baseline 대비 변동값으로 보고.

## 검증

- 동일 시나리오 2회 연속 실행 시 RPS·p95가 ±10% 이내로 재현되어야 baseline으로 인정.
- 시나리오 결과 파일이 깃에 커밋되어 있고 이후 plan에서 비교 가능한 형식.

## 롤백 절차

- 코드/배포 변경이 없으므로 롤백 불필요. 시나리오 파일만 사용.

## 예상 작업 분량

- Locust 시나리오 확장만이면 0.5일. Prometheus/Grafana까지면 1.5일.

## 의존성

- 없음. 단독 실행.

## 후속

- 본 plan 완료 후 baseline 결과를 기준으로 P1(폴링 정책) → P2(Kafka 파티션) → P3(컨테이너 리소스) 순으로 진행.
