# syntax=docker/dockerfile:1

# PREBUILT=1: skip the Maven network build and use the host-built WAR already in
# AccountManagerService7/target/. Required when the corp TLS proxy blocks Maven Central.
# Before building with PREBUILT=1, run on the host:
#   cd src && mvn -o -pl AccountManagerObjects7,AccountManagerISO42001 install -DskipTests
#   cd src && mvn -o -pl AccountManagerService7 package -DskipTests
# The bcprov jar must also be staged at docker/bcprov-jdk18on-1.80.jar (already done). Its version
# MUST equal the bcprov-jdk18on version in AccountManagerObjects7/pom.xml: a skew against the
# bcpkix/bcutil jars inside the WAR fails at the first certificate signing
# (NoSuchFieldError: BCObjectIdentifiers.xmss_SHAKE128_512ph with 1.76 beside bcpkix 1.80).
ARG PREBUILT=0

########################################
# Stage 1: build the WAR (Objects7 -> Agent7/ISO42001 -> Service7).
# Uses normal network access to Maven Central -- a fresh build stage has no
# populated .m2 cache, so this intentionally differs from the repo's usual
# `mvn -o` offline local-dev convention (that convention is unaffected).
# With PREBUILT=1, this stage just reuses the host-built artifacts instead.
########################################
FROM maven:3.9-eclipse-temurin-26 AS java-build
ARG PREBUILT
WORKDIR /build
COPY pom.xml ./
COPY AccountManagerObjects7 AccountManagerObjects7
COPY AccountManagerAgent7 AccountManagerAgent7
COPY AccountManagerISO42001 AccountManagerISO42001
COPY AccountManagerConsole7 AccountManagerConsole7
COPY AccountManagerService7 AccountManagerService7
# bcprov staged for PREBUILT=1 path (no-op when PREBUILT=0; Maven resolves it instead)
COPY docker/bcprov-jdk18on-1.80.jar /build/docker/bcprov-jdk18on-1.80.jar
# The WAR marks bcprov-jdk18on as `provided` (AccountManagerService7/pom.xml) --
# on the dev box BouncyCastle is a container/JVM-level security provider, so it
# is deliberately excluded from WEB-INF/lib. The container has no such provider,
# so IOSystem.open() dies with NoClassDefFoundError: BouncyCastleProvider. Stage
# the bcprov jar here and copy it into WEB-INF/lib in the runtime stage. Resolve
# the version from Objects7's own dependency graph (copy-dependencies filtered to
# bcprov) rather than hardcoding it, so it can never drift from the bcpkix/bcutil
# version the WAR actually ships -- a version skew across signed/sealed BC jars in
# the same classloader would trip JCE signature verification at runtime.
RUN mkdir -p /build/bclib && \
    if [ "$PREBUILT" = "0" ]; then \
      mvn -q -pl AccountManagerObjects7,AccountManagerAgent7,AccountManagerISO42001,AccountManagerService7 \
          -am package -DskipTests && \
      mvn -q -pl AccountManagerObjects7 dependency:copy-dependencies \
          -DincludeArtifactIds=bcprov-jdk18on \
          -DoutputDirectory=/build/bclib; \
    else \
      cp /build/docker/bcprov-jdk18on-1.80.jar /build/bclib/; \
    fi

########################################
# Stage 2: build Ux752. node_modules is kept (not just dist/) because the
# runtime image runs Ux752 under node/npm (`vite preview`) behind nginx,
# rather than having nginx serve the static build directly.
########################################
FROM node:24-alpine AS ux-build
WORKDIR /build/AccountManagerUx752
COPY AccountManagerUx752/package.json AccountManagerUx752/package-lock.json* ./
# The lock file is in sync (regenerated 2026-08-20; `npm ci --dry-run` passes), so `npm ci` is the
# right command here. It stays `npm install` only because changing this RUN line invalidates the
# cached layer, and re-running it needs registry access the corp TLS proxy blocks
# (UNABLE_TO_GET_ISSUER_CERT_LOCALLY, measured 2026-10-07). Switch to `npm ci` when building off-proxy.
RUN npm install
COPY AccountManagerUx752/ ./
RUN npx vite build

########################################
# Stage 3: runtime -- Tomcat 11 + nginx + node, one container.
########################################
FROM eclipse-temurin:26-jre-alpine AS runtime

# Check https://tomcat.apache.org/download-11.cgi for newer patch releases.
ARG TOMCAT_VERSION=11.0.25
ENV CATALINA_HOME=/opt/tomcat \
    APP_CONTEXT=AccountManagerService7

# dlcdn.apache.org carries ONLY the CURRENT release of each branch. When Apache ships
# 11.0.N+1, the pinned 11.0.N is rotated off dlcdn to archive.apache.org and this RUN
# starts failing with `curl: (22) The requested URL returned error: 404` - a time bomb
# armed on every pinned version, not a mirror outage. It went off 2026-09-17: measured
# that day, v11.0.25 was 404 on dlcdn and 200 on archive, with dlcdn carrying 11.0.26.
#
# So: try the CDN, then fall back to the archive. This keeps the pin authoritative
# (floating the version to whatever dlcdn happens to hold would make the image
# non-reproducible and silently change the servlet container under you) while making
# the build immune to the next rotation.
RUN apk add --no-cache bash curl gettext openssl nginx supervisor nodejs npm unzip \
    && ( curl -fsL "https://dlcdn.apache.org/tomcat/tomcat-11/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" -o /tmp/tomcat.tar.gz \
      || ( echo "NOTE: dlcdn no longer carries v${TOMCAT_VERSION}; falling back to archive.apache.org" \
           && curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-11/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" -o /tmp/tomcat.tar.gz ) ) \
    && mkdir -p "$CATALINA_HOME" \
    && tar xzf /tmp/tomcat.tar.gz -C "$CATALINA_HOME" --strip-components=1 \
    && rm /tmp/tomcat.tar.gz \
    && rm -rf "$CATALINA_HOME"/webapps/* \
    && chmod +x "$CATALINA_HOME"/bin/*.sh

# Tomcat, vite preview and nginx all run as this unprivileged user (supervisord.conf drops to it
# per program; only the entrypoint and supervisord itself stay root). Fixed uid/gid so ownership
# of a Linux bind mount is predictable across rebuilds. Created after the apk/Tomcat layer so that
# layer stays cache-hot (it needs network the corp TLS proxy blocks), and before any COPY --chown.
RUN addgroup -g 1000 am7 && adduser -D -u 1000 -G am7 -h /home/am7 -s /sbin/nologin am7

# Explode the WAR (rather than deploy it packed) so the entrypoint can
# envsubst context.xml/web.xml into the live webapp directory at container
# start, before Tomcat reads them.
RUN mkdir -p "$CATALINA_HOME/webapps/${APP_CONTEXT}"
COPY --from=java-build /build/AccountManagerService7/target/AccountManagerService7.war /tmp/app.war
# Remove the WAR's real context.xml/web.xml (which still carry the committed
# dev DB URL/creds, LAN server addresses, and the task.api.key default) so
# only the .template files ship in the image; entrypoint.sh renders the real
# ones into the container's writable layer at start, never at build time.
# The chown is in the same layer as the unzip on purpose: a separate `RUN chown -R` would
# re-copy the whole exploded WAR into a new layer.
RUN unzip -q /tmp/app.war -d "$CATALINA_HOME/webapps/${APP_CONTEXT}" && rm /tmp/app.war \
    && rm -f "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml" \
             "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml" \
    && chown -R am7:am7 "$CATALINA_HOME"

# bcprov-jdk18on: excluded from the WAR by its `provided` scope; supplied here
# so BouncyCastleProvider is on the webapp classpath (see java-build stage note).
# Wildcard because the version is resolved from Objects7's dependency graph.
COPY --chown=am7:am7 --from=java-build /build/bclib/bcprov-jdk18on-*.jar "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/lib/"

COPY --chown=am7:am7 docker/context.xml.template "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml.template"
COPY --chown=am7:am7 docker/web.xml.template "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml.template"
COPY --chown=am7:am7 docker/server.xml "$CATALINA_HOME/conf/server.xml"
COPY --chown=am7:am7 docker/setenv.sh "$CATALINA_HOME/bin/setenv.sh"

# Ux752, run at runtime via `vite preview` (node/npm), proxied by nginx.
# --chown rather than a later chown -R: node_modules is the largest tree in the image and a
# post-hoc chown would duplicate it into another layer.
WORKDIR /opt/ux752
COPY --chown=am7:am7 --from=ux-build /build/AccountManagerUx752/node_modules ./node_modules
COPY --chown=am7:am7 --from=ux-build /build/AccountManagerUx752/dist ./dist
COPY --chown=am7:am7 --from=ux-build /build/AccountManagerUx752/package.json ./
COPY --chown=am7:am7 --from=ux-build /build/AccountManagerUx752/vite.config.js ./

COPY docker/nginx.conf /etc/nginx/nginx.conf
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh

# Strip CR from the scripts and configs copied out of the build context. .gitattributes pins these
# to LF in the repo, but the build context is whatever is on disk -- and a CRLF entrypoint fails at
# exec with "no such file or directory", which is the kernel reporting that the interpreter
# "/bin/bash\r" does not exist, NOT that the script is missing. Cheap, idempotent, and it makes the
# image build correctly from a checkout with any core.autocrlf setting.
#
# The chown list is every path a dropped-privilege process writes: the data/cert roots (volumes
# normally, but the image-time ownership is what a fresh named volume inherits), nginx's pid/temp/
# log dirs (its master runs unprivileged too, so it cannot open /run/nginx.pid or
# /var/lib/nginx/tmp as the package left them), and the app user's HOME for npm's cache.
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh "$CATALINA_HOME/bin/setenv.sh" \
        /etc/nginx/nginx.conf /etc/supervisord.conf "$CATALINA_HOME/conf/server.xml" \
        "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml.template" \
        "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml.template" \
    && chmod +x /usr/local/bin/entrypoint.sh "$CATALINA_HOME/bin/setenv.sh" \
    && mkdir -p /data/am7/store /data/am7/vault/credentials /data/am7/datagen /data/am7/sessions /etc/am7/certs /run/nginx /home/am7 \
    && chown -R am7:am7 /data/am7 /etc/am7/certs /run/nginx /var/lib/nginx /var/log/nginx /home/am7 \
    && chown am7:am7 "$CATALINA_HOME/conf/server.xml" "$CATALINA_HOME/bin/setenv.sh" \
        "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml.template" \
        "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml.template"

EXPOSE 8443

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
