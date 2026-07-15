#!/bin/sh
# Sourced automatically by catalina.sh. The JAASRealm configured in
# context.xml.template needs java.security.auth.login.config pointing at the
# jaas.conf bundled into the webapp (src/main/resources/jaas.conf ->
# WEB-INF/classes/jaas.conf after the WAR is built/exploded). Locally this is
# normally set as a JVM argument by Stephen's Eclipse Tomcat launch config,
# which isn't tracked in git -- the container has to set it itself.
JAAS_CONF="$CATALINA_HOME/webapps/${APP_CONTEXT}/WEB-INF/classes/jaas.conf"
export JAVA_OPTS="$JAVA_OPTS -Djava.security.auth.login.config=$JAAS_CONF"
