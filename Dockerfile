ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-26-noble@sha256:6206ae5e460fbc803743b53addc31c5caca04582cf6a99f0f91df29c54954b52
ARG TERMINAL_BUILD_IMAGE=gcc:15.2-bookworm@sha256:9ca91b05c7b07d2979f16413e8b2cd6ec8a7c80ffca4121ccab0aeba33f90460
ARG UBUNTU_IMAGE=ubuntu:noble@sha256:561618e2c15bf2397621dd04f96926663a3b5616c189cf7e38db7e82f5c538ea
# Adoptium Temurin 26.0.2+10 JRE ("jdk-26.0.2+10"). The official upstream
# eclipse-temurin:26-jre-noble image was still on 26.0.1 when the 2026-07 CPU
# advisories (CVE-2026-41254, CVE-2026-47063) landed, so the runtime JRE is
# pinned by tarball SHA-256 on Ubuntu noble instead. Revert to the official
# digest-pinned image once it ships 26.0.2.
ARG JRE_TARBALL_SHA256_X64=585c4cce5807ce5677289a123680a8648c84c9afac66727a0e3027298d8e32c7
ARG JRE_TARBALL_SHA256_AARCH64=3c689572d2ea7aa3e19db5e5bc4ee41e90b557593d15eefcec179a9b8abfff0e
ARG BUILD_VERSION=development
ARG BUILD_REVISION=development
ARG BUILD_SOURCE=https://github.com/SuHeling1212/cilexec

FROM ${BUILD_IMAGE} AS build
ARG BUILD_REVISION
WORKDIR /workspace

COPY pom.xml ./
COPY .mvn ./.mvn
COPY LICENSE ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -Djava.net.preferIPv4Stack=true \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.http.connectionTimeout=30000 \
        dependency:go-offline

COPY src ./src
# The FCL runtime test compiles this published example. Keep the build check independent
# from the runtime image while making the source available to Maven's test phase.
COPY docs/examples/fcl-oop-smoke-test.fcl ./docs/examples/fcl-oop-smoke-test.fcl
COPY docker/postgres/init/00-cilexec-bootstrap.sh ./docker/postgres/init/00-cilexec-bootstrap.sh
# Distributable FCL package sources are fixtures used by package tests;
# keep every tested package available inside the Maven build stage.
COPY dist/editor ./dist/editor
COPY dist/snake ./dist/snake
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

FROM ${UBUNTU_IMAGE} AS jre
ARG JRE_TARBALL_SHA256_X64
ARG JRE_TARBALL_SHA256_AARCH64
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64)  jarch="x64";     jre_sha256="${JRE_TARBALL_SHA256_X64}";; \
        arm64)  jarch="aarch64"; jre_sha256="${JRE_TARBALL_SHA256_AARCH64}";; \
        *) echo "unsupported architecture: $arch" >&2; exit 1;; \
    esac; \
    url="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2%2B10/OpenJDK26U-jre_${jarch}_linux_hotspot_26.0.2_10.tar.gz"; \
    curl -fsSL "$url" -o /tmp/jre.tar.gz; \
    echo "${jre_sha256}  /tmp/jre.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/java/openjdk; \
    tar -xzf /tmp/jre.tar.gz -C /opt/java/openjdk --strip-components=1; \
    rm -f /tmp/jre.tar.gz

FROM ${UBUNTU_IMAGE} AS runtime
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

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}"
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

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
