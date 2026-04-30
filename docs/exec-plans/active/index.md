# Active Execution Plans

진행 중인 실행 계획 목록.

| 시작일 | 계획명 | 파일 | 상태 |
|--------|--------|------|------|
| 2026-04-30 | 부하테스트 시나리오·메트릭 재구축 | [loadtest-rebuild.md](loadtest-rebuild.md) | 계획 수립 |
| 2026-04-30 | Outbox 비동기화 + Producer 압축 (멱등성과 함께) | [kafka-partitioning.md](kafka-partitioning.md) | 다음 묶음 |
| 2026-04-30 | 컨테이너 limits / JVM heap / GC | [container-resource-baseline.md](container-resource-baseline.md) | 계획 수립 |

## 트래픽 증대 plan 모음

10만 명 동시 폴링 트래픽 수용을 목표로 분리된 plan들.

권장 실행 순서:

1. **부하테스트 재구축 + 부하기 분리** (baseline 확립, 단일 박스 측정 한계 우회)
2. ~~Dispatch 분산 + payment 컨슈머 정상화 + Kafka 토폴로지 강건화~~ ✅ 완료 (2026-04-30)
3. **Outbox 비동기화 + Producer 압축** (멱등성 도입과 함께)
4. **컨테이너 limits / JVM heap / GC** (측정 재현성)

각 단계 사이에 부하 측정으로 효과 확인.
