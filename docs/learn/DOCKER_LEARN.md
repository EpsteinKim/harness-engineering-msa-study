# Docker 학습 가이드

> 이 프로젝트(harness-back)의 Phase 1 ~ Phase 2 진행에 필요한 Docker 핵심 개념을 실습 중심으로 정리한 문서.

---

## 목차

1. [Docker란?](#1-docker란)
2. [핵심 개념](#2-핵심-개념)
3. [이미지와 컨테이너](#3-이미지와-컨테이너)
4. [Dockerfile 작성법](#4-dockerfile-작성법)
5. [자주 쓰는 명령어](#5-자주-쓰는-명령어)
6. [네트워킹](#6-네트워킹)
7. [볼륨과 데이터 영속성](#7-볼륨과-데이터-영속성)
8. [Docker Compose](#8-docker-compose)
9. [실전: 이 프로젝트 적용](#9-실전-이-프로젝트-적용)
10. [트러블슈팅](#10-트러블슈팅)
11. [다음 단계](#11-다음-단계)

---

## 1. Docker란?

**컨테이너** 기술을 이용해 애플리케이션을 격리된 환경에서 실행하는 플랫폼.

### VM vs Container

```
VM:                              Container:
┌─────────┐ ┌─────────┐         ┌─────────┐ ┌─────────┐
│  App A  │ │  App B  │         │  App A  │ │  App B  │
├─────────┤ ├─────────┤         ├─────────┤ ├─────────┤
│ Guest OS│ │ Guest OS│         │ Bins/Libs│ │ Bins/Libs│
├─────────┴─┴─────────┤         ├─────────┴─┴─────────┤
│     Hypervisor       │         │    Docker Engine     │
├──────────────────────┤         ├──────────────────────┤
│      Host OS         │         │      Host OS         │
└──────────────────────┘         └──────────────────────┘
```

| 비교 | VM | Container |
|------|-----|-----------|
| 크기 | GB 단위 | MB 단위 |
| 시작 시간 | 분 | 초 |
| 격리 수준 | 완전 (OS 분리) | 프로세스 수준 |
| 리소스 | 무겁다 | 가볍다 |

### 왜 Docker를 쓰는가?

- **"내 컴퓨터에서는 되는데?"** 문제 해결 - 환경 일관성 보장
- **MSA에서 필수** - 서비스마다 독립적으로 빌드/배포/스케일링
- **이 프로젝트에서**: queue-service, gateway, redis 등을 각각 컨테이너로 분리 운영

---

## 2. 핵심 개념

### 용어 정리

```
Dockerfile  →(build)→  Image  →(run)→  Container
 (레시피)              (빵틀)           (빵)
```

| 용어 | 설명 | 비유 |
|------|------|------|
| **Image** | 읽기 전용 실행 환경 템플릿 | 클래스 |
| **Container** | 이미지를 실행한 인스턴스 | 객체 |
| **Dockerfile** | 이미지를 만드는 명세서 | 소스 코드 |
| **Registry** | 이미지 저장소 (Docker Hub 등) | Maven Central |
| **Volume** | 컨테이너 외부 데이터 저장소 | 외장 하드 |
| **Network** | 컨테이너 간 통신 채널 | VPC |

### 레이어 시스템

Docker 이미지는 **레이어** 단위로 구성된다. 각 Dockerfile 명령이 하나의 레이어.

```
Layer 4: ENTRYPOINT ["java", "-jar", "app.jar"]   ← 실행 명령
Layer 3: COPY app.jar /app/                        ← 앱 파일
Layer 2: RUN apt-get install -y curl               ← 패키지 설치
Layer 1: FROM eclipse-temurin:21-jre-alpine        ← 베이스 이미지
```

**왜 중요한가?**
- 변경된 레이어부터 다시 빌드 → 변경 없는 레이어는 **캐시** 사용
- 의존성 설치 레이어를 소스 코드보다 먼저 두면 빌드 속도 향상

---

## 3. 이미지와 컨테이너

### 이미지 생명주기

```
Dockerfile
    │
    ▼ docker build
  Image (로컬)
    │
    ├─▶ docker push ──▶ Registry (Docker Hub, ECR, GCR...)
    │                        │
    │                   docker pull
    │                        │
    ▼ docker run             ▼
  Container (실행 중)    Image (다른 머신)
    │
    ├─▶ docker stop  ──▶ Container (중지)
    ├─▶ docker start ──▶ Container (재시작)
    └─▶ docker rm    ──▶ 삭제
```

### 이미지 태그

```bash
# 형식: 이름:태그
eclipse-temurin:21-jdk-alpine    # JDK 21, Alpine Linux 기반
redis:7-alpine                   # Redis 7, Alpine 기반
queue-service:latest             # 우리 서비스, latest 태그

# latest는 "최신"이 아니라 "기본 태그"일 뿐 → 운영에서는 명시적 버전 사용
queue-service:1.0.0              # 이렇게 쓰자
```

### Alpine Linux?

일반 Linux 이미지는 ~100MB+, Alpine은 ~5MB. 컨테이너 이미지 크기를 줄이기 위해 사용.
단, 일부 네이티브 라이브러리 호환 문제가 있을 수 있다 (glibc vs musl).

---

## 4. Dockerfile 작성법

### 기본 명령어

| 명령 | 역할 | 예시 |
|------|------|------|
| `FROM` | 베이스 이미지 지정 | `FROM eclipse-temurin:21-jdk-alpine` |
| `WORKDIR` | 작업 디렉토리 설정 | `WORKDIR /app` |
| `COPY` | 호스트 → 컨테이너 파일 복사 | `COPY build/libs/*.jar app.jar` |
| `ADD` | COPY + URL 다운로드 + tar 자동 해제 | `ADD https://... /app/` |
| `RUN` | 빌드 시점에 명령 실행 | `RUN ./gradlew bootJar` |
| `CMD` | 컨테이너 시작 시 기본 명령 (덮어쓰기 가능) | `CMD ["java", "-jar", "app.jar"]` |
| `ENTRYPOINT` | 컨테이너 시작 시 고정 명령 | `ENTRYPOINT ["java", "-jar", "app.jar"]` |
| `EXPOSE` | 문서화 목적의 포트 선언 (실제 포트 열지 않음) | `EXPOSE 8080` |
| `ENV` | 환경 변수 설정 | `ENV SPRING_PROFILES_ACTIVE=prod` |
| `ARG` | 빌드 시점 변수 (런타임 X) | `ARG JAR_FILE=app.jar` |

### CMD vs ENTRYPOINT

```dockerfile
# CMD: 기본 명령. docker run 시 덮어쓸 수 있음
CMD ["java", "-jar", "app.jar"]
# docker run myimage echo hello  → echo hello 실행 (CMD 무시됨)

# ENTRYPOINT: 고정 명령. docker run 인자가 뒤에 붙음
ENTRYPOINT ["java", "-jar", "app.jar"]
# docker run myimage --spring.profiles.active=prod  → java -jar app.jar --spring.profiles.active=prod

# 조합: ENTRYPOINT가 기본, CMD가 기본 인자
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--spring.profiles.active=default"]
```

### 현재 프로젝트 Dockerfile 분석

```dockerfile
# Build stage - 빌드 전용 환경 (JDK 필요)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

# Run stage - 실행 전용 환경 (JRE만 필요)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

이것이 **멀티 스테이지 빌드**:

```
Stage 1 (build):                    Stage 2 (run):
┌──────────────────────┐            ┌──────────────────────┐
│ JDK (큼)             │            │ JRE (작음)           │
│ 소스 코드            │──jar──▶    │ app.jar만 복사       │
│ Gradle               │            │                      │
│ 빌드 산출물          │            │ 최종 이미지          │
└──────────────────────┘            └──────────────────────┘
  이 스테이지는 버려짐                 이것만 배포됨
```

**장점**: 최종 이미지에 빌드 도구(JDK, Gradle)가 없으므로 이미지 크기가 작아짐.

### 최적화된 Dockerfile (개선안)

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Gradle 래퍼와 설정 먼저 복사 (캐시 활용)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# 서브모듈 빌드 파일도 복사
COPY queue-service/build.gradle.kts ./queue-service/
COPY gateway/build.gradle.kts ./gateway/

# 의존성만 먼저 다운로드 (소스 변경 시 이 레이어는 캐시됨)
RUN ./gradlew dependencies --no-daemon || true

# 소스 코드 복사 후 빌드
COPY . .
RUN ./gradlew :queue-service:bootJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안: root가 아닌 별도 사용자로 실행
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/queue-service/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**개선점:**
1. **의존성 캐시 분리**: Gradle 설정 파일만 먼저 복사 → 소스만 바뀌면 의존성 다운로드 스킵
2. **non-root 실행**: 보안 모범 사례
3. **EXPOSE**: 문서화 목적, 어떤 포트를 사용하는지 명시
4. **--no-daemon**: 컨테이너 빌드에서는 Gradle 데몬 불필요

### .dockerignore

빌드 컨텍스트에서 불필요한 파일 제외. `.gitignore`와 유사.

```
# .dockerignore
.git
.gradle
build
**/build
*.md
.idea
```

---

## 5. 자주 쓰는 명령어

### 이미지

```bash
# 빌드
docker build -t queue-service:1.0.0 .           # 현재 디렉토리의 Dockerfile로 빌드
docker build -t queue-service:1.0.0 -f Dockerfile.dev .  # 특정 Dockerfile 지정
docker build --no-cache -t queue-service:1.0.0 .  # 캐시 없이 빌드

# 조회/삭제
docker images                     # 이미지 목록
docker image ls                   # 같은 명령
docker rmi queue-service:1.0.0    # 이미지 삭제
docker image prune                # 미사용 이미지 정리
docker image prune -a             # 컨테이너에서 사용되지 않는 모든 이미지 삭제
```

### 컨테이너

```bash
# 실행
docker run queue-service:1.0.0                     # 포그라운드 실행
docker run -d queue-service:1.0.0                   # 백그라운드(detached) 실행
docker run -d -p 8080:8080 queue-service:1.0.0      # 포트 매핑 (호스트:컨테이너)
docker run -d --name my-queue queue-service:1.0.0    # 이름 지정
docker run -d -e SPRING_PROFILES_ACTIVE=prod queue-service:1.0.0  # 환경 변수 전달
docker run --rm queue-service:1.0.0                  # 종료 시 자동 삭제

# 상태 확인
docker ps                   # 실행 중인 컨테이너
docker ps -a                # 모든 컨테이너 (중지 포함)
docker logs my-queue        # 로그 출력
docker logs -f my-queue     # 로그 실시간 follow
docker stats                # 리소스 사용량 (CPU, 메모리)
docker inspect my-queue     # 상세 정보 (JSON)

# 접속
docker exec -it my-queue /bin/sh   # 컨테이너 쉘 접속 (Alpine은 bash 없음, sh 사용)
docker exec my-queue env           # 환경 변수 확인

# 중지/삭제
docker stop my-queue        # 정상 종료 (SIGTERM → SIGKILL)
docker kill my-queue        # 강제 종료 (SIGKILL)
docker rm my-queue          # 컨테이너 삭제 (중지 상태여야 함)
docker rm -f my-queue       # 강제 삭제 (실행 중이어도)

# 정리
docker system prune         # 미사용 컨테이너, 네트워크, 이미지 정리
docker system prune -a      # 더 적극적으로 정리 (모든 미사용 이미지 포함)
docker system df            # Docker 디스크 사용량 확인
```

### 포트 매핑 (-p)

```bash
docker run -p 8080:8080 myapp   # 호스트 8080 → 컨테이너 8080
docker run -p 9090:8080 myapp   # 호스트 9090 → 컨테이너 8080
docker run -p 127.0.0.1:8080:8080 myapp  # localhost에서만 접근 가능

# 형식: -p [호스트IP:]호스트포트:컨테이너포트
```

---

## 6. 네트워킹

### 네트워크 종류

| 드라이버 | 용도 | 특징 |
|----------|------|------|
| **bridge** (기본) | 단일 호스트 컨테이너 간 통신 | 기본 bridge에서는 IP로만 통신. 사용자 정의 bridge에서는 이름으로 통신 가능 |
| **host** | 호스트 네트워크 직접 사용 | 포트 매핑 불필요, 격리 없음 |
| **none** | 네트워크 차단 | 보안 목적 |

### 컨테이너 간 통신 (MSA에서 핵심)

```bash
# 사용자 정의 네트워크 생성
docker network create harness-net

# 같은 네트워크에 컨테이너 연결
docker run -d --name queue-service --network harness-net queue-service:1.0.0
docker run -d --name redis --network harness-net redis:7-alpine

# queue-service 내부에서 redis 접근:
# redis:6379  (컨테이너 이름이 DNS 호스트명이 됨)
```

**기본 bridge vs 사용자 정의 bridge:**

```
기본 bridge:                         사용자 정의 bridge:
- IP로만 통신 가능                    - 컨테이너 이름으로 통신 (DNS 자동 등록)
- 모든 컨테이너가 같은 bridge         - 네트워크별 격리
- 레거시                             - 권장

# 즉, Docker Compose를 쓰면 자동으로 사용자 정의 bridge가 만들어짐
```

### 이 프로젝트에서의 네트워크 (Phase 2 예상)

```
harness-net (bridge)
    │
    ├── gateway:8000        → 외부 노출
    ├── queue-service:8080  → 내부 통신만
    ├── user-service:8081   → 내부 통신만
    └── redis:6379          → 내부 통신만
```

외부에서는 gateway:8000만 접근 가능. 나머지는 내부 네트워크에서만 통신.

---

## 7. 볼륨과 데이터 영속성

컨테이너는 **일시적(ephemeral)**. 삭제하면 내부 데이터도 사라진다.
데이터를 유지하려면 **볼륨**을 사용.

### 볼륨 종류

```bash
# 1. Named Volume (Docker가 관리, 권장)
docker volume create queue-data
docker run -v queue-data:/data redis:7-alpine
# → /var/lib/docker/volumes/queue-data/_data 에 저장

# 2. Bind Mount (호스트 경로 직접 매핑)
docker run -v $(pwd)/config:/app/config queue-service:1.0.0
# → 호스트의 ./config 디렉토리가 컨테이너의 /app/config에 마운트

# 3. tmpfs Mount (메모리에만 존재, 컨테이너 종료 시 사라짐)
docker run --tmpfs /tmp queue-service:1.0.0
```

### 사용 시나리오

| 볼륨 타입 | 언제 쓰는가 | 이 프로젝트 예시 |
|-----------|-------------|------------------|
| Named Volume | DB 데이터 영속화 | Redis 데이터, RDB 데이터 |
| Bind Mount | 개발 시 소스 마운트, 설정 파일 주입 | application.yml 외부 주입 |
| tmpfs | 민감 데이터 임시 저장 | 세션 토큰 캐시 |

```bash
# 볼륨 관리
docker volume ls                  # 목록
docker volume inspect queue-data  # 상세 정보
docker volume rm queue-data       # 삭제
docker volume prune               # 미사용 볼륨 정리
```

---

## 8. Docker Compose

여러 컨테이너를 **하나의 YAML 파일**로 정의하고 관리하는 도구.
MSA에서는 서비스가 많으므로 필수.

### 기본 구조

```yaml
# docker-compose.yml (또는 compose.yml)

services:
  서비스이름:
    image: 이미지명          # 또는 build: ./경로
    ports:
      - "호스트:컨테이너"
    environment:
      - KEY=VALUE
    volumes:
      - 볼륨:경로
    depends_on:
      - 다른서비스
    networks:
      - 네트워크명

volumes:
  볼륨명:

networks:
  네트워크명:
```

### 이 프로젝트 Phase 2 예시

```yaml
# docker-compose.yml

services:
  gateway:
    build: ./gateway
    ports:
      - "8000:8000"
    environment:
      - QUEUE_SERVICE_URL=http://queue-service:8080
      - USER_SERVICE_URL=http://user-service:8081
    depends_on:
      - queue-service
      - user-service
    networks:
      - harness-net

  queue-service:
    build: ./queue-service
    ports:
      - "8080:8080"          # 개발 시 직접 접근용. 운영에서는 제거
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATA_REDIS_HOST=redis
    depends_on:
      redis:
        condition: service_healthy
    networks:
      - harness-net

  user-service:
    build: ./user-service
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    networks:
      - harness-net

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"          # 개발 시 직접 접근용
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 3
    networks:
      - harness-net

volumes:
  redis-data:

networks:
  harness-net:
    driver: bridge
```

### Compose 명령어

```bash
# 기본
docker compose up              # 모든 서비스 시작 (포그라운드)
docker compose up -d           # 백그라운드 실행
docker compose down            # 모든 서비스 중지 + 컨테이너/네트워크 삭제
docker compose down -v         # 볼륨까지 삭제 (데이터 날아감, 주의!)

# 빌드
docker compose build           # 모든 서비스 이미지 빌드
docker compose up --build      # 빌드 + 실행 (코드 변경 후 자주 사용)

# 개별 서비스
docker compose up queue-service         # 특정 서비스만 실행
docker compose logs queue-service       # 특정 서비스 로그
docker compose logs -f                  # 전체 로그 실시간 follow
docker compose restart queue-service    # 특정 서비스 재시작

# 상태 확인
docker compose ps              # 서비스 상태
docker compose top             # 프로세스 목록

# 스케일링
docker compose up -d --scale queue-service=3  # 인스턴스 3개로 확장
```

### depends_on과 healthcheck

`depends_on`만으로는 "서비스가 준비됐는지"를 보장하지 않는다. 컨테이너 시작 ≠ 애플리케이션 준비.

```yaml
# 단순 depends_on: 컨테이너 시작 순서만 보장
depends_on:
  - redis

# condition 사용: 건강 상태 확인 후 시작 (권장)
depends_on:
  redis:
    condition: service_healthy

# redis에 healthcheck 정의 필요
redis:
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 5s
    retries: 3
```

### 환경별 Compose 파일 분리

```bash
# 개발용
docker compose -f docker-compose.yml -f docker-compose.dev.yml up

# 운영용
docker compose -f docker-compose.yml -f docker-compose.prod.yml up
```

```yaml
# docker-compose.dev.yml (오버라이드)
services:
  queue-service:
    ports:
      - "8080:8080"        # 개발 시 직접 접근
      - "5005:5005"        # 디버거 포트
    environment:
      - JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
    volumes:
      - ./queue-service/src:/app/src  # 소스 마운트 (핫 리로드용)
```

---

## 9. 실전: 이 프로젝트 적용

### Phase 1: 지금 바로 해보기

```bash
# 1. 이미지 빌드
docker build -t queue-service:0.1.0 .

# 2. 컨테이너 실행
docker run -d \
  --name queue-service \
  -p 8080:8080 \
  queue-service:0.1.0

# 3. 동작 확인
curl http://localhost:8080/actuator/health

# 4. 로그 확인
docker logs -f queue-service

# 5. 정리
docker stop queue-service && docker rm queue-service
```

### Phase 2로 넘어갈 때 체크리스트

- [ ] 각 서비스별 Dockerfile 작성
- [ ] docker-compose.yml 작성
- [ ] Spring 프로필 `docker`용 application 설정 추가 (DB 호스트 = 컨테이너 이름)
- [ ] Redis healthcheck 설정
- [ ] .dockerignore 파일 작성
- [ ] 네트워크 격리 (gateway만 외부 노출)
- [ ] 볼륨 설정 (Redis 데이터 영속화)

### Spring Boot + Docker 설정 팁

```yaml
# application-docker.yml (Docker 환경 전용 프로필)
spring:
  data:
    redis:
      host: redis           # 컨테이너 이름 = 호스트명
      port: 6379
  r2dbc:
    url: r2dbc:postgresql://db:5432/queue  # db도 컨테이너 이름
```

```bash
# 프로필 활성화
docker run -e SPRING_PROFILES_ACTIVE=docker queue-service:1.0.0
```

---

## 10. 트러블슈팅

### 자주 만나는 에러

**포트 충돌**
```
Error: Bind for 0.0.0.0:8080 failed: port is already allocated
```
→ 호스트의 8080 포트를 이미 다른 프로세스가 사용 중.
```bash
lsof -i :8080              # 어떤 프로세스가 사용 중인지 확인
docker run -p 9090:8080 ... # 다른 호스트 포트 사용
```

**컨테이너 즉시 종료**
```bash
docker logs <container>     # 에러 로그 확인
docker run -it myimage /bin/sh  # 직접 쉘로 들어가서 디버깅
```

**이미지 빌드 실패 - 캐시 문제**
```bash
docker build --no-cache -t myimage .  # 캐시 무시하고 빌드
```

**컨테이너 간 통신 실패**
```bash
# 같은 네트워크에 있는지 확인
docker network inspect harness-net

# 컨테이너 내부에서 DNS 확인
docker exec -it queue-service /bin/sh
nslookup redis
ping redis
```

**디스크 공간 부족**
```bash
docker system df            # 사용량 확인
docker system prune -a      # 미사용 리소스 정리
```

### 디버깅 명령어 모음

```bash
docker inspect <container>                 # 전체 설정 JSON
docker inspect -f '{{.NetworkSettings.IPAddress}}' <container>  # IP 확인
docker exec -it <container> /bin/sh        # 쉘 접속
docker cp <container>:/app/logs ./logs     # 컨테이너 → 호스트 파일 복사
docker diff <container>                    # 파일 시스템 변경 사항 확인
```

---

## 11. 다음 단계

### Phase 2 준비

이 가이드의 내용을 실습했다면, Phase 2(Docker Compose + MSA)로 진입할 준비가 된 것.

**Phase 2에서 추가로 학습할 것:**
- Docker Compose 환경에서의 서비스 디스커버리
- 환경 변수와 시크릿 관리 (.env 파일)
- 로그 수집 전략 (각 컨테이너 로그를 어떻게 모을 것인가)
- 이미지 최적화 (사이즈, 빌드 시간)

### Phase 3 (K8s) 준비

Docker 기본기가 탄탄해야 K8s가 쉽다. 다음 개념을 Docker 레벨에서 먼저 이해하기:

| Docker 개념 | K8s 대응 |
|-------------|----------|
| Container | Pod |
| docker-compose.yml | Deployment + Service YAML |
| docker network | K8s Service / Ingress |
| docker volume | PersistentVolume |
| docker compose up --scale=3 | ReplicaSet |
| healthcheck | livenessProbe / readinessProbe |

---

## 참고 자료

- [Docker 공식 문서](https://docs.docker.com/)
- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [Spring Boot Docker 가이드](https://spring.io/guides/topicals/spring-boot-docker)
- [Dockerfile 모범 사례](https://docs.docker.com/build/building/best-practices/)
