# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ergo && adduser -S ergo -G ergo
WORKDIR /app
COPY --from=build /build/target/ergo-connect-backend-*.jar app.jar
USER ergo
# Fails closed: a forgotten SPRING_PROFILES_ACTIVE in the deploy manifest hits a missing-secret
# startup failure (RequireMailInProd etc.) instead of silently booting with dev's public JWT
# secret, seeded admin account and Swagger left on (security-reviewer H1, Phase 8j).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
