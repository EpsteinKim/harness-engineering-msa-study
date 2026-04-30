# 컨테이너 limits / JVM heap / GC (P3)

> 수치 자체보다 **재현성**을 만드는 plan. 이 위에서 P1·P2 측정값이 신뢰 가능해진다.

## 목적

JVM heap 미지정·컨테이너 cpu/memory limits 미설정 상태로는 동일 부하에서도 측정값 분산이 크고, 한 파드의 GC/메모리 폭주가 노드 전체 워커에 영향을 줄 수 있다. payment-service HikariCP가 기본값인 점도 같이 정리.

## 변경 범위

- K8s `*-deployment.yaml` (모든 서비스)
- 각 서비스 `Dockerfile` 또는 `application*.properties`의 JVM 옵션
- `payment-service/src/main/resources/application*.properties` HikariCP 명시
- 각 서비스 `server.tomcat.threads.max`/`accept-count` 명시(virtual thread 활성화는 그대로)

## 핵심 결정

1. K8s requests/limits (시작값, 측정 후 조정):
   - gateway: req 0.5 vCPU / 512Mi, lim 1 vCPU / 1Gi
   - reserve: req 1 vCPU / 1Gi, lim 2 vCPU / 2Gi
   - core: req 0.5 vCPU / 512Mi, lim 1 vCPU / 1Gi
   - payment: req 0.5 vCPU / 512Mi, lim 1 vCPU / 1Gi
2. JVM:
   - `-Xms`/`-Xmx`를 컨테이너 limit의 60~70%로 고정(예: limit 2Gi → `-Xms1280m -Xmx1280m`)
   - GC: G1 기본 유지(JDK21). ZGC는 measurement 후 후속 plan으로.
   - `-XX:+UseContainerSupport` JDK21 기본이라 별도 명시 불필요. `-XX:MaxRAMPercentage`로 대체 가능.
3. Tomcat:
   - virtual thread 활성화 그대로(`spring.threads.virtual.enabled=true`)
   - `server.tomcat.threads.max` 명시(예: 200) + `accept-count`(예: 200) 명시 — virtual thread 환경에서도 max 미설정 시 운영 가시성 떨어짐
4. payment HikariCP: `maximumPoolSize=20, minimumIdle=5, connectionTimeout=5000` (reserve와 동일 시작값)

## 검증 (KPI)

- 동일 시나리오 2회 실행 시 p95 분산 ±5% 이하(baseline ±10% 대비 개선)
- GC pause p99 < 100ms
- CPU throttling 비율 < 5%
- payment-service의 connection wait 0

## 롤백 절차

- limit/heap 변경은 매니페스트 단위 revert로 롤백 가능. 단, 이미 한 번 설정한 뒤에는 "기본값으로 복귀"가 의미 없으므로 *값 조정* 방향으로만.
- payment HikariCP는 신규 명시이므로 revert 시 기본값으로.

## 예상 작업 분량

- 매니페스트 + JVM 옵션: 0.5일
- HikariCP / Tomcat 명시: 0.5일
- 부하테스트 재실행 + 비교: 0.5일

## 의존성

- P4(loadtest-rebuild) baseline 필요
- P1·P2와 독립적이지만 측정 신뢰성을 위해 P3 적용 후 baseline을 한 번 더 갱신하는 것이 이상적
