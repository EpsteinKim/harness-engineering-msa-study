# Infrastructure

> Docker, 네트워크, 배포 인프라 구성.

---

## 1. 현재: Docker (Phase 1)

### Dockerfile 전략

- Multi-stage build: 빌드 단계와 실행 단계 분리
- 베이스 이미지: `eclipse-temurin:21-jre-alpine`
- non-root 사용자 실행

### 예정 Dockerfile 구조

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

# Run stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 2. Phase 2: Docker Compose

### 네트워크 구성

```
harness-network (bridge)
├── gateway       (8000 → external)
├── queue-service (8080 → internal)
├── user-service  (internal)
├── redis         (6379 → internal)
└── db            (internal)
```

- 외부 접근: Gateway 포트만 노출
- 내부 통신: Docker 내부 네트워크 (서비스명으로 DNS 해석)

---

## 3. Phase 3: Kubernetes

> Docker Compose 검증 완료 후 전환. 상세 계획은 별도 exec-plan으로 관리.

- Deployment, Service, ConfigMap, Secret
- Ingress Controller (nginx 또는 traefik)
- HPA (Horizontal Pod Autoscaler) 검토
- Helm Chart 또는 Kustomize
