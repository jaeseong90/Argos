# SPDX-License-Identifier: Apache-2.0
#
# shop-batch image (development and tests only; 18 §4.4). Build after the jar:
#   ./gradlew :sample-apps:shop-batch:sampleImage                      -> argos-sample/shop-batch:dev
#   ./gradlew :sample-apps:shop-batch:sampleImage -Psample.jdkImage=eclipse-temurin:21-jre@sha256:<digest>
#   docker build --build-arg JDK_IMAGE=<image@digest> sample-apps/shop-batch   (matrix: other JDK)
# The default base image is the 18 §4.1 build JDK (Java 17), pinned by the digest of its multi-arch
# index. Other JDK images: README.md "Container images".
ARG JDK_IMAGE=eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8
FROM ${JDK_IMAGE}
WORKDIR /app
COPY build/sample-app/shop-batch.jar /app/app.jar
# In Compose, ext-stub is the service "ext-stub"; nothing listens on its port 1 (connection refused).
ENV SERVER_PORT=8080 \
    SAMPLE_EXT_URL=http://ext-stub:18080
USER 10001:10001
EXPOSE 8080
# JAVA_OPTS: extra JVM options, e.g. -javaagent:/opt/argos-agent/argos-agent.jar.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
