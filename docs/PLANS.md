# Plans & Roadmap

> 프로젝트 마일스톤과 실행 계획. 상세 실행 계획은 `exec-plans/active/`에서 관리.

---

## Phase 1: 단일 서비스 (Docker)

- [ ] Queue Service 핵심 API 구현
- [ ] Dockerfile 작성
- [ ] Redis 연동 (대기열 실시간 상태)
- [ ] RDB 연동 (메타데이터/이력)
- [ ] 기본 테스트 코드 작성
- [ ] Docker 단일 컨테이너 실행 확인

## Phase 2: MSA (Docker Compose)

- [ ] API Gateway (Spring Cloud Gateway) 구성
- [ ] User Service 분리
- [ ] docker-compose.yml 작성
- [ ] 서비스 간 통신 구현
- [ ] Redis 공유 상태 저장소 구성

## Phase 3: Kubernetes

- [ ] K8s 매니페스트 작성
- [ ] Service, Deployment, ConfigMap 구성
- [ ] Ingress 설정
- [ ] 모니터링/로깅 스택 구성
