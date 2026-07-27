# syntax=docker/dockerfile:1.7

ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-26-noble
ARG RUNTIME_IMAGE=eclipse-temurin:26-jre-noble

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipITs verify

FROM ${RUNTIME_IMAGE} AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cilexec \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin cilexec \
    && install --directory --owner=10001 --group=10001 \
        /opt/cilexec /var/cache/cilexec/packages /tmp/cilexec

COPY --from=build --chown=10001:10001 /workspace/target/cilexec-app.jar /opt/cilexec/cilexec-app.jar
COPY --chown=10001:10001 docker/healthcheck.sh /opt/cilexec/healthcheck.sh
RUN chmod 0555 /opt/cilexec/healthcheck.sh \
    && chmod 0444 /opt/cilexec/cilexec-app.jar

USER 10001:10001
WORKDIR /opt/cilexec
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD ["/opt/cilexec/healthcheck.sh", "ready"]

ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/cilexec/cilexec-app.jar"]
CMD ["terminal"]
