# Phase 3: Kubernetes (minikube) 전환

## 요약

Phase 2 (Docker Compose MSA) → Phase 3 (Kubernetes) 전환. `docker` 프로파일 재사용으로 코드 변경 최소화.

## 완료 항목

### 코드 변경
- Spring Boot Actuator 의존성 추가 (4개 서비스)
- `.dockerignore` 생성 (`database.properties` 이미지 제외)
- `.gitignore`에 `kubernetes/config/db-secret.yaml` 추가

### K8s 매니페스트
- `kubernetes/config/` — ConfigMap (`docker` 프로파일 + actuator probe), Secret (NeonDB 크레덴셜)
- `kubernetes/infrastructure/` — Redis Deployment+PVC, Kafka StatefulSet+PVC (KRaft)
- `kubernetes/apps/` — gateway, core(Recreate), reserve(HPA 2~5), payment(HPA 1~3)
- `kubernetes/ingress/` — `harness.local` → gateway
- `kubernetes/scripts/` — deploy.sh, teardown.sh

### 설계 결정
- `docker` 프로파일 재사용: K8s Service DNS 이름 = Docker Compose 서비스 이름
- core-service `Recreate` 전략: 스케줄러 중복 실행 방지
- DB 크레덴셜: K8s Secret → env var 주입 (Spring Boot property 우선순위로 오버라이드)
- 이미지: `minikube docker-env`로 직접 빌드, `imagePullPolicy: Never`

### 문서 갱신
- ARCHITECTURE.md: Phase 3 K8s 섹션 추가
- README.md: Phase 상태, Quick Start, Project Structure 갱신
