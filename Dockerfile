# One image, every process (ADR 0023): s6-overlay supervises hydra, faye, the Login UI and
# the Admin UI, and with FAYE_DEMO=true the demo's ip-reputation stub, bootstrap, seed and
# demo client. One volume at /var/lib/faye holds the SQLite database, the generated secrets
# and the demo's secrets.
#
#   docker run -d --name faye -v faye-data:/var/lib/faye -e FAYE_DEMO=true \
#     -p 4444:4444 -p 8080:8080 -p 3000:3000 -p 3001:3001 -p 127.0.0.1:3002:3002 faye

# Server build (same as server/Dockerfile): the Gradle build's repoRoot is /src's parent, so
# the schema lives at /schema for processResources.
FROM eclipse-temurin:21.0.13_9-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS server-build
WORKDIR /src
COPY server/ .
COPY schema/ /schema/
RUN ./gradlew --no-daemon :faye-app:bootJar

# Minimal JRE for faye.jar. Modules are jdeps --print-module-deps --ignore-missing-deps
# --multi-release base over the exploded boot jar, plus jdk.crypto.ec (TLS) and jdk.zipfs.
FROM eclipse-temurin:21.0.13_9-jdk-alpine@sha256:b78a1d385da875588e21207f07dddf9a1418849971ae857fb24c8f3a7d441a72 AS jre
RUN /opt/java/openjdk/bin/jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql.rowset,jdk.crypto.ec,jdk.jfr,jdk.unsupported,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=2 \
      --output /jre

# Web build (same as web/Dockerfile): the three Next.js apps' standalone output.
FROM node:24.22.2-slim@sha256:c86ecead9e55a5210ac1727de38a4ca840bf03dcaf34dc9749addbf383b1920c AS web-build
RUN npm install -g pnpm@11.20.0
WORKDIR /web
COPY web/ .
RUN pnpm install --frozen-lockfile && pnpm --filter "./apps/*" --workspace-concurrency=1 build
RUN for app in login admin demo-client; do \
      mkdir -p /out/$app && cp -r apps/$app/.next/standalone/. /out/$app/ \
      && cp -r apps/$app/.next/static /out/$app/apps/$app/.next/static; \
    done
# The three standalone outputs carry the same node_modules; dedupe identical files into
# hardlinks and carry them through a tar, which preserves them (ADR 0023; ~110 MB saved)
RUN hardlink /out && tar -C /out -cf /out.tar .

FROM node:24.22.2-alpine3.24@sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85 AS node

FROM oryd/hydra:v2.2.0@sha256:2c93beb5e5f260cb5ed5ab579d8a1c133b0ec503c933244370522a2a53695ce7 AS hydra

# Runtime: plain Alpine on the same minor as node:24.22.2-alpine3.24. No JDK, no pnpm,
# no Python.
FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
ARG TARGETARCH
ARG S6_OVERLAY_VERSION=3.2.3.2
RUN apk add --no-cache libstdc++ libgcc
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) s6arch=x86_64; s6sum=e6befcc96a437a3831386ecfc51808c5d3e939dc5fe3c02ae9284599e8aa2408 ;; \
      arm64) s6arch=aarch64; s6sum=b17f17a82e7a515c682a91edaf2ffdabb73f891981b6c1fd712115693a2f8b4c ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    base="https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}"; \
    cd /tmp; \
    wget -q "$base/s6-overlay-noarch.tar.xz" "$base/s6-overlay-${s6arch}.tar.xz"; \
    echo "5379750ed30a84bbd2e2dd74847ba6b5bd29cd0b2e3ea2ec58049b57eb2eda12  s6-overlay-noarch.tar.xz" | sha256sum -c -; \
    echo "$s6sum  s6-overlay-${s6arch}.tar.xz" | sha256sum -c -; \
    tar -C / -Jxpf "s6-overlay-noarch.tar.xz"; \
    tar -C / -Jxpf "s6-overlay-${s6arch}.tar.xz"; \
    rm -f /tmp/s6-overlay-*.tar.xz
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=hydra /usr/bin/hydra /usr/local/bin/hydra
COPY --from=jre /jre /opt/faye/jre
COPY --from=server-build /src/faye-app/build/libs/faye-app-0.1.0-SNAPSHOT.jar /opt/faye/faye.jar
RUN --mount=type=bind,from=web-build,source=/out.tar,target=/tmp/web.tar \
    mkdir -p /opt/faye/web && tar -C /opt/faye/web -xf /tmp/web.tar
COPY deploy/bootstrap.mjs deploy/ip-reputation.mjs /opt/faye/deploy/
COPY deploy/directory/seed.mjs /opt/faye/deploy/directory/seed.mjs
COPY deploy/image/s6-rc.d /etc/s6-overlay/s6-rc.d
COPY deploy/image/user-bundles.d /etc/s6-overlay/user-bundles.d
COPY deploy/image/scripts /etc/s6-overlay/scripts
COPY deploy/image/stage2-hook /etc/s6-overlay/stage2-hook
COPY deploy/image/healthcheck /opt/faye/bin/healthcheck
RUN mkdir -p /var/lib/faye \
    && chmod -R a+rX /etc/s6-overlay \
    && chmod +x /etc/s6-overlay/stage2-hook /opt/faye/bin/healthcheck \
    && chmod +x /etc/s6-overlay/scripts/* \
    && find /etc/s6-overlay/s6-rc.d -name run -exec chmod +x {} + \
    && find /etc/s6-overlay/s6-rc.d -name up -exec chmod +x {} +
ENV PATH=/opt/faye/jre/bin:/usr/local/bin:/command:/usr/bin:/bin \
    NODE_ENV=production \
    # hydra's admin API stays on the container's loopback; only :4444 is published
    SERVE_ADMIN_HOST=127.0.0.1 \
    # never put a deadline on startup: the seed hashes 1000 Argon2 passwords
    S6_CMD_WAIT_FOR_SERVICES_MAXTIME=0 \
    S6_BEHAVIOUR_IF_STAGE2_FAILS=2 \
    S6_STAGE2_HOOK=/etc/s6-overlay/stage2-hook
VOLUME /var/lib/faye
EXPOSE 4444 8080 3000 3001 3002
HEALTHCHECK --interval=15s --timeout=5s --start-period=240s CMD /opt/faye/bin/healthcheck
ENTRYPOINT ["/init"]
