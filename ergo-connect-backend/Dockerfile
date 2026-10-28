# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

# Application Insights Java agent (8m). Pinned, and checked against the sha256 Maven Central publishes for it.
# Bump both together. It is only switched on by the deploy (JAVA_TOOL_OPTIONS + APPLICATIONINSIGHTS_CONNECTION_STRING).
FROM alpine:3.22 AS agent
ARG AI_AGENT_VERSION=3.7.10
ARG AI_AGENT_SHA256=93a70c8f5d364c7e777f6c4d1b235dba91aef8448bd3fa94359f1d7f3e2eb0ec
RUN wget -q -O /applicationinsights-agent.jar       "https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar"  && echo "${AI_AGENT_SHA256}  /applicationinsights-agent.jar" | sha256sum -c -

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ergo && adduser -S ergo -G ergo
WORKDIR /app
COPY --from=build /build/target/ergo-connect-backend-*.jar app.jar
COPY --from=agent /applicationinsights-agent.jar applicationinsights-agent.jar
COPY applicationinsights.json applicationinsights.json
USER ergo
# Fails closed: a forgotten SPRING_PROFILES_ACTIVE in the deploy manifest hits a missing-secret
# startup failure (RequireMailInProd etc.) instead of silently booting with dev's public JWT
# secret, seeded admin account and Swagger left on (security-reviewer H1, Phase 8j).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
