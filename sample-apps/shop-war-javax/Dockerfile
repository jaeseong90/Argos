# SPDX-License-Identifier: Apache-2.0
#
# shop-war-javax image: standalone Tomcat with the WAR (development and tests only;
# 18 §4.4). Build after the WAR and the staged Tomcat distribution:
#   ./gradlew :sample-apps:shop-war-javax:sampleImage                      -> argos-sample/shop-war-javax:dev
#   docker build --build-arg JDK_IMAGE=<image@digest> --build-arg TOMCAT=tomcat9 sample-apps/shop-war-javax
# TOMCAT selects build/tomcat/<key> (staged by the sampleStageTomcat<Key> task). The default base
# image is the 18 §4.1 JDK (Java 8), pinned by the digest of its multi-arch index.
ARG JDK_IMAGE=eclipse-temurin:8-jre@sha256:1fc1cdba9c1e68173bc553b95438206b0b995f9f763cfc77feb45b27f5774d9b
FROM ${JDK_IMAGE}
ARG TOMCAT=tomcat9
ENV CATALINA_HOME=/opt/tomcat \
    SAMPLE_EXT_URL=http://ext-stub:18080
COPY build/tomcat/${TOMCAT}/ /opt/tomcat/
COPY build/sample-app/shop-war-javax.war /opt/tomcat/webapps/shop-war-javax.war
# The staged distribution has no empty directories (logs/, work/): create them for the non-root user.
RUN rm -rf /opt/tomcat/webapps/ROOT /opt/tomcat/webapps/manager /opt/tomcat/webapps/host-manager \
 && mkdir -p /opt/tomcat/logs /opt/tomcat/temp /opt/tomcat/work \
 && chmod +x /opt/tomcat/bin/*.sh \
 && chown -R 10001:10001 /opt/tomcat/logs /opt/tomcat/temp /opt/tomcat/work /opt/tomcat/webapps
USER 10001:10001
EXPOSE 8080
# JAVA_OPTS / CATALINA_OPTS: extra JVM options (e.g. -javaagent:...), read by catalina.sh.
CMD ["/opt/tomcat/bin/catalina.sh", "run"]
