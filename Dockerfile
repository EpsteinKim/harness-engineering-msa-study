# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build

ARG SERVICE_NAME

WORKDIR /app

# Gradle 래퍼와 설정 먼저 복사 (캐시 활용)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# 서브모듈 빌드 파일 복사
COPY common/build.gradle.kts ./common/
COPY gateway/build.gradle.kts ./gateway/
COPY user-service/build.gradle.kts ./user-service/
COPY reserve-service/build.gradle.kts ./reserve-service/

# 의존성만 먼저 다운로드 (소스 변경 시 이 레이어는 캐시됨)
RUN ./gradlew dependencies --no-daemon || true

# 소스 코드 복사 후 빌드
COPY . .
RUN ./gradlew :${SERVICE_NAME}:bootJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-alpine

ARG SERVICE_NAME
ARG SERVICE_PORT=8080

WORKDIR /app

# 보안: root가 아닌 별도 사용자로 실행
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/${SERVICE_NAME}/build/libs/*.jar app.jar

EXPOSE ${SERVICE_PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]