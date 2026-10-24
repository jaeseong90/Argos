# SPDX-License-Identifier: Apache-2.0
#
# shop-war-jakarta image: standalone Tomcat with the WAR (development and tests only;
# 18 §4.4). Build after the WAR and the staged Tomcat distribution:
#   ./gradlew :sample-apps:shop-war-jakarta:sampleImage                      -> argos-sample/shop-war-jakarta:dev
#   docker build --build-arg JDK_IMAGE=<image@digest> --build-arg TOMCAT=tomcat10 sample-apps/shop-war-jakarta
# TOMCAT selects build/tomcat/<key> (staged by the sampleStageTomcat<Key> task). The default base
# image is the 18 §4.1 JDK (Java 17), pinned by the digest of its multi-arch index.
ARG JDK_IMAGE=eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8
FROM ${JDK_IMAGE}
ARG TOMCAT=tomcat10
ENV CATALINA_HOME=/opt/tomcat \
    SAMPLE_EXT_URL=http://ext-stub:18080
COPY build/tomcat/${TOMCAT}/ /opt/tomcat/
COPY build/sample-app/shop-war-jakarta.war /opt/tomcat/webapps/shop-a.war
COPY build/sample-app/shop-war-jakarta.war /opt/tomcat/webapps/shop-b.war
# The staged distribution has no empty directories (logs/ and work/ are missing): create them.
RUN rm -rf /opt/tomcat/webapps/ROOT /opt/tomcat/webapps/manager /opt/tomcat/webapps/host-manager \
 && mkdir -p /opt/tomcat/logs /opt/tomcat/temp /opt/tomcat/work \
 && chmod +x /opt/tomcat/bin/*.sh \
 && chown -R 10001:10001 /opt/tomcat/logs /opt/tomcat/temp /opt/tomcat/work /opt/tomcat/webapps
USER 10001:10001
EXPOSE 8080
# JAVA_OPTS / CATALINA_OPTS: extra JVM options (e.g. -javaagent:...), read by catalina.sh.
CMD ["/opt/tomcat/bin/catalina.sh", "run"]
