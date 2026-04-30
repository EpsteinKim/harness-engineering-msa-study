# NeonDB → 로컬 PostgreSQL 컨테이너 전환

## 배경

현재 3개 서비스가 NeonDB(싱가포르/시드니)를 사용 중. 한국에서 쿼리당 ~23ms 레이턴시 발생. 부하 테스트 시 DB 왕복이 병목. 로컬 PostgreSQL 컨테이너로 전환하면 ~0.1ms로 200배 개선.

NeonDB는 그대로 유지하고, 로컬 개발/부하 테스트용으로 컨테이너 DB를 추가하는 방향.

## 구조

PostgreSQL 1개 컨테이너 + DB 3개 (가볍고 실용적):

```
postgres (1 컨테이너)
  ├── reserve_db   ← reserve-service
  ├── core_db      ← core-service
  └── payment_db   ← payment-service
```

## 변경 사항

### 1. Docker Compose에 PostgreSQL 추가

```yaml
postgres:
  image: postgres:17-alpine
  environment:
    POSTGRES_USER: harness
    POSTGRES_PASSWORD: harness
  ports:
    - "5432:5432"
  volumes:
    - postgres-data:/data
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U harness"]
    interval: 10s
    timeout: 5s
    retries: 3
```

### 2. DB 초기화 스크립트

`docker-entrypoint-initdb.d/`에 마운트하면 첫 기동 시 자동 실행:

```sql
-- init.sql
CREATE DATABASE reserve_db;
CREATE DATABASE core_db;
CREATE DATABASE payment_db;
```

### 3. application-docker.properties 변경 (3개 서비스)

```properties
# reserve-service
spring.datasource.url=jdbc:postgresql://postgres:5432/reserve_db
spring.datasource.username=harness
spring.datasource.password=harness

# core-service
spring.datasource.url=jdbc:postgresql://postgres:5432/core_db
spring.datasource.username=harness
spring.datasource.password=harness

# payment-service
spring.datasource.url=jdbc:postgresql://postgres:5432/payment_db
spring.datasource.username=harness
spring.datasource.password=harness
```

### 4. K8s 매니페스트 추가

```
kubernetes/infrastructure/postgres-deployment.yaml
  - Deployment + Service + PVC
  - 초기화 스크립트 ConfigMap
  - Service name: postgres (DNS)
```

### 5. K8s Secret 변경

```yaml
# kubernetes/config/db-secret.yaml
RESERVE_DB_URL: jdbc:postgresql://postgres:5432/reserve_db
CORE_DB_URL: jdbc:postgresql://postgres:5432/core_db
PAYMENT_DB_URL: jdbc:postgresql://postgres:5432/payment_db
# username/password: harness/harness
```

### 6. .env 변경

```
RESERVE_DB_URL=jdbc:postgresql://postgres:5432/reserve_db
CORE_DB_URL=jdbc:postgresql://postgres:5432/core_db
PAYMENT_DB_URL=jdbc:postgresql://postgres:5432/payment_db
RESERVE_DB_USERNAME=harness
RESERVE_DB_PASSWORD=harness
# ... 동일
```

### 7. database.properties 유지 (로컬 개발)

```properties
# 로컬에서 직접 실행 시 (docker compose 없이)
spring.datasource.url=jdbc:postgresql://localhost:5432/reserve_db
spring.datasource.username=harness
spring.datasource.password=harness
```

### 8. 시드 데이터

DB가 비어있으므로 초기 데이터 필요:
- event 2건 (SEAT_PICK + SECTION_SELECT)
- user_account 200,000명
- seat: event_id=1 (400석), event_id=2 (50,000석)

init.sql에 포함하거나 별도 seed.sql 작성.

## NeonDB는 유지

- database.properties (로컬 직접 실행): NeonDB 그대로
- application-docker.properties (Docker/K8s): 로컬 postgres
- 프로파일로 전환 가능

## 수정 파일 목록

| 파일 | 변경 |
|------|------|
| **변경** `docker-compose.yml` | postgres 서비스 추가 |
| **신규** `db/init.sql` | DB 3개 생성 + 시드 데이터 |
| **변경** `reserve-service/.../application-docker.properties` | DB URL 변경 |
| **변경** `core-service/.../application-docker.properties` | DB URL 변경 |
| **변경** `payment-service/.../application-docker.properties` | DB URL 변경 |
| **변경** `.env` | DB URL 변경 |
| **변경** `.env.sample` | 템플릿 갱신 |
| **신규** `kubernetes/infrastructure/postgres-deployment.yaml` | K8s PostgreSQL |
| **변경** `kubernetes/config/db-secret.yaml` | 로컬 DB 크레덴셜 |
| **변경** `kubernetes/config/db-secret.yaml.sample` | 템플릿 갱신 |

## 구현 순서

1. db/init.sql 작성 (DB 생성 + 시드 데이터)
2. docker-compose.yml에 postgres 추가
3. application-docker.properties 3개 변경
4. .env 변경
5. kubernetes/infrastructure/postgres-deployment.yaml 작성
6. kubernetes/config/db-secret.yaml 변경
7. Docker Compose로 검증 (docker compose up)
8. K8s로 검증 (deploy.sh)

## 검증

- `docker compose up` → 서비스 기동 → API 정상 응답
- 부하 테스트: locust → DB 레이턴시 ~0.1ms 확인
- K8s 배포 → 동일 검증
