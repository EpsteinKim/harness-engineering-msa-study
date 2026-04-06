  # Build stage
  FROM eclipse-temurin:21-jdk-alpine AS build
  WORKDIR /app
  COPY . .
  RUN ./gradlew bootJar

  # Run stage
  FROM eclipse-temurin:21-jre-alpine
  WORKDIR /app
  COPY --from=build /app/build/libs/*.jar app.jar
  ENTRYPOINT ["java", "-jar", "app.jar"]