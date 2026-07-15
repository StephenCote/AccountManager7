# syntax=docker/dockerfile:1

########################################
# Stage 1: build the WAR (Objects7 -> Agent7/ISO42001 -> Service7).
# Uses normal network access to Maven Central -- a fresh build stage has no
# populated .m2 cache, so this intentionally differs from the repo's usual
# `mvn -o` offline local-dev convention (that convention is unaffected).
########################################
FROM maven:3.9-eclipse-temurin-26 AS java-build
WORKDIR /build
COPY pom.xml ./
COPY AccountManagerObjects7 AccountManagerObjects7
COPY AccountManagerAgent7 AccountManagerAgent7
COPY AccountManagerISO42001 AccountManagerISO42001
COPY AccountManagerConsole7 AccountManagerConsole7
COPY AccountManagerService7 AccountManagerService7
RUN mvn -q -pl AccountManagerObjects7,AccountManagerAgent7,AccountManagerISO42001,AccountManagerService7 \
    -am package -DskipTests

########################################
# Stage 2: build Ux752. node_modules is kept (not just dist/) because the
# runtime image runs Ux752 under node/npm (`vite preview`) behind nginx,
# rather than having nginx serve the static build directly.
########################################
FROM node:24-alpine AS ux-build
WORKDIR /build/AccountManagerUx752
COPY AccountManagerUx752/package.json AccountManagerUx752/package-lock.json* ./
# `npm ci` requires package-lock.json to exactly match package.json; the
# repo's committed lock file is currently out of sync (e.g. vitest 2.1.9 in
# the lock vs "^4.1.9" in package.json), so `npm install` is used instead of
# `npm ci` here. Flagged separately -- the lock file should be regenerated.
RUN npm install
COPY AccountManagerUx752/ ./
RUN npx vite build

########################################
# Stage 3: runtime -- Tomcat 11 + nginx + node, one container.
########################################
FROM eclipse-temurin:26-jre-alpine AS runtime

# Check https://tomcat.apache.org/download-11.cgi for newer patch releases.
ARG TOMCAT_VERSION=11.0.24
ENV CATALINA_HOME=/opt/tomcat \
    APP_CONTEXT=AccountManagerService7

RUN apk add --no-cache bash curl gettext openssl nginx supervisor nodejs npm unzip \
    && curl -fsSL "https://dlcdn.apache.org/tomcat/tomcat-11/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" -o /tmp/tomcat.tar.gz \
    && mkdir -p "$CATALINA_HOME" \
    && tar xzf /tmp/tomcat.tar.gz -C "$CATALINA_HOME" --strip-components=1 \
    && rm /tmp/tomcat.tar.gz \
    && rm -rf "$CATALINA_HOME"/webapps/* \
    && chmod +x "$CATALINA_HOME"/bin/*.sh

# Explode the WAR (rather than deploy it packed) so the entrypoint can
# envsubst context.xml/web.xml into the live webapp directory at container
# start, before Tomcat reads them.
RUN mkdir -p "$CATALINA_HOME/webapps/${APP_CONTEXT}"
COPY --from=java-build /build/AccountManagerService7/target/AccountManagerService7.war /tmp/app.war
# Remove the WAR's real context.xml/web.xml (which still carry the committed
# dev DB URL/creds, LAN server addresses, and the task.api.key default) so
# only the .template files ship in the image; entrypoint.sh renders the real
# ones into the container's writable layer at start, never at build time.
RUN unzip -q /tmp/app.war -d "$CATALINA_HOME/webapps/${APP_CONTEXT}" && rm /tmp/app.war \
    && rm -f "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml" \
             "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml"

COPY docker/context.xml.template "$CATALINA_HOME/webapps/${APP_CONTEXT}/META-INF/context.xml.template"
COPY docker/web.xml.template "$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/web.xml.template"
COPY docker/server.xml "$CATALINA_HOME/conf/server.xml"
COPY docker/setenv.sh "$CATALINA_HOME/bin/setenv.sh"

# Ux752, run at runtime via `vite preview` (node/npm), proxied by nginx.
WORKDIR /opt/ux752
COPY --from=ux-build /build/AccountManagerUx752/node_modules ./node_modules
COPY --from=ux-build /build/AccountManagerUx752/dist ./dist
COPY --from=ux-build /build/AccountManagerUx752/package.json ./
COPY --from=ux-build /build/AccountManagerUx752/vite.config.js ./

COPY docker/nginx.conf /etc/nginx/nginx.conf
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh

RUN chmod +x /usr/local/bin/entrypoint.sh "$CATALINA_HOME/bin/setenv.sh" \
    && mkdir -p /data/am7/store /data/am7/vault/credentials /data/am7/datagen /data/am7/sessions /etc/am7/certs /run/nginx

EXPOSE 8443

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
