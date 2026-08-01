ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-26-noble
ARG RUNTIME_IMAGE=eclipse-temurin:26-jre-noble

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -Djava.net.preferIPv4Stack=true \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.http.connectionTimeout=30000 \
        dependency:go-offline

COPY src ./src
# Editor source is a distributable FCL package fixture used by package tests;
# the old market/sources tree no longer exists.
COPY dist/editor ./dist/editor
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -Djava.net.preferIPv4Stack=true \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.http.connectionTimeout=30000 \
        -DskipITs verify

FROM ${RUNTIME_IMAGE} AS runtime

LABEL com.follarce.cilexec=true

COPY docker/terminal-client.c /tmp/cilexec-terminal-client.c
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl gcc libc6-dev \
    && gcc -O2 -s -o /opt/cilexec-terminal-client /tmp/cilexec-terminal-client.c \
    && apt-get purge --yes --auto-remove gcc libc6-dev \
    && mv /opt/cilexec-terminal-client /usr/local/bin/cilexec-terminal-client \
    && rm -f /tmp/cilexec-terminal-client.c \
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

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/cilexec/cilexec-app.jar"]
CMD ["terminal"]
