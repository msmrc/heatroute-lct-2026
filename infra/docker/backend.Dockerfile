FROM maven:3.9.12-eclipse-temurin-11-alpine@sha256:f66d7a8e40ef1f9f4dcac12ff7b6a54bc5db9288878c679c3303378bb8bbe160 AS build

WORKDIR /workspace
COPY apps/api/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY apps/api/src ./src
COPY datasets/official /datasets/official
COPY docs/contracts /docs/contracts
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify

FROM eclipse-temurin:11.0.28_6-jre-alpine@sha256:6cde7e6ae3c23c3636f3fb4b92836d1323c13929d9ee27da1885cc231c086101

RUN addgroup -S -g 10001 heatroute \
    && adduser -S -D -H -u 10001 -G heatroute heatroute \
    && mkdir -p /var/lib/heatroute/artifacts /var/lib/heatroute/tmp \
    && chown -R heatroute:heatroute /var/lib/heatroute

WORKDIR /app
COPY --from=build --chown=heatroute:heatroute /workspace/target/heatroute-backend.jar /app/heatroute-backend.jar

USER heatroute
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/heatroute-backend.jar"]
