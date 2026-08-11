ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-26-noble@sha256:6206ae5e460fbc803743b53addc31c5caca04582cf6a99f0f91df29c54954b52
ARG TERMINAL_BUILD_IMAGE=gcc:15.2-bookworm@sha256:9ca91b05c7b07d2979f16413e8b2cd6ec8a7c80ffca4121ccab0aeba33f90460
ARG RUNTIME_IMAGE=eclipse-temurin:26-jre-noble@sha256:d095323f078a68e0663f7bb7ff103f6275227a929c3ecc6f6fb626ebe6e4d51a
ARG BUILD_VERSION=1.0.0
ARG BUILD_REVISION=development
ARG BUILD_SOURCE=https://github.com/SuHeling1212/cilexec

FROM ${BUILD_IMAGE} AS build
ARG BUILD_REVISION
WORKDIR /workspace

COPY pom.xml ./
COPY LICENSE ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -Djava.net.preferIPv4Stack=true \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.http.connectionTimeout=30000 \
        dependency:go-offline

COPY src ./src
COPY docker/postgres/init/00-cilexec-bootstrap.sh ./docker/postgres/init/00-cilexec-bootstrap.sh
# Editor source is a distributable FCL package fixture used by package tests;
# the old market/sources tree no longer exists.
COPY dist/editor ./dist/editor
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -Djava.net.preferIPv4Stack=true \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.http.connectionTimeout=30000 \
        -Dbuild.revision=${BUILD_REVISION} \
        -DskipITs verify

FROM ${TERMINAL_BUILD_IMAGE} AS terminal-build
COPY docker/terminal-client.c /src/terminal-client.c
RUN gcc -std=c11 -Wall -Wextra -Werror -O2 -s \
    -o /cilexec-terminal-client /src/terminal-client.c

FROM ${RUNTIME_IMAGE} AS runtime
ARG BUILD_VERSION
ARG BUILD_REVISION
ARG BUILD_SOURCE

LABEL com.follarce.cilexec=true \
      org.opencontainers.image.title="CilExec" \
      org.opencontainers.image.description="Database-driven CilExec FCL runtime" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.source="${BUILD_SOURCE}" \
      org.opencontainers.image.licenses="MIT"

COPY --from=terminal-build /cilexec-terminal-client /usr/local/bin/cilexec-terminal-client
RUN groupadd --gid 10001 cilexec \
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

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/cilexec/cilexec-app.jar"]
CMD ["terminal"]

# Formal release builds replace the independently compiled image JAR with the
# exact artifact already verified and described by dist/release-manifest.json.
FROM runtime AS release
USER root
COPY --chown=10001:10001 dist/cilexec-app.jar /opt/cilexec/cilexec-app.jar
RUN chmod 0444 /opt/cilexec/cilexec-app.jar
USER 10001:10001

FROM runtime AS final
