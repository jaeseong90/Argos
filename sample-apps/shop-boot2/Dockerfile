# SPDX-License-Identifier: Apache-2.0
#
# shop-boot2 image (development and tests only; 18 §4.4). Build after the jar:
#   ./gradlew :sample-apps:shop-boot2:sampleImage                      -> argos-sample/shop-boot2:dev
#   ./gradlew :sample-apps:shop-boot2:sampleImage -Psample.jdkImage=eclipse-temurin:21-jre@sha256:<digest>
#   docker build --build-arg JDK_IMAGE=<image@digest> sample-apps/shop-boot2   (matrix: other JDK)
# The default base image is the 18 §4.1 build JDK (Java 8), pinned by the digest of its multi-arch
# index. Other JDK images: README.md "Container images".
ARG JDK_IMAGE=eclipse-temurin:8-jre@sha256:1fc1cdba9c1e68173bc553b95438206b0b995f9f763cfc77feb45b27f5774d9b
FROM ${JDK_IMAGE}
WORKDIR /app
COPY build/sample-app/shop-boot2.jar /app/app.jar
# In Compose, ext-stub is the service "ext-stub"; nothing listens on its port 1 (connection refused).
ENV SERVER_PORT=8080 \
    SAMPLE_EXT_URL=http://ext-stub:18080
USER 10001:10001
EXPOSE 8080
# JAVA_OPTS: extra JVM options, e.g. -javaagent:/opt/argos-agent/argos-agent.jar.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
