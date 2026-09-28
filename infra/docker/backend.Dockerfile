FROM maven:3.9.12-eclipse-temurin-11@sha256:f39c21c3fef9ec69a85fa024c5513fb28dbcd2f282a27a11dae4568b7547f535 AS source

WORKDIR /workspace
COPY apps/api/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY apps/api/src ./src
COPY datasets/official /datasets/official
COPY docs/contracts /docs/contracts

FROM source AS test
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify

FROM source AS build
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests

FROM eclipse-temurin:11.0.28_6-jre-jammy@sha256:fc451894669bc656f81082ced9daddfd8df0d8e8fea659dc26a232f0e0124837

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 heatroute \
    && useradd --uid 10001 --gid heatroute --no-create-home --shell /usr/sbin/nologin heatroute \
    && mkdir -p /var/lib/heatroute/artifacts /var/lib/heatroute/tmp \
    && chown -R heatroute:heatroute /var/lib/heatroute

WORKDIR /app
COPY --from=build --chown=heatroute:heatroute /workspace/target/heatroute-backend.jar /app/heatroute-backend.jar

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/var/lib/heatroute/tmp -Djna.tmpdir=/var/lib/heatroute/tmp"
USER heatroute
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/heatroute-backend.jar"]
