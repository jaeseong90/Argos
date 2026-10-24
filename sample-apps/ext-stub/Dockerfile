# SPDX-License-Identifier: Apache-2.0
#
# ext-stub image (outgoing-call stub, development and tests only; 18 §4.4). Build after the jar:
#   ./gradlew :sample-apps:ext-stub:sampleImage                      -> argos-sample/ext-stub:dev
#   docker build --build-arg JDK_IMAGE=<image@digest> sample-apps/ext-stub
# The default base image (Java 17) is pinned by the digest of its multi-arch index.
ARG JDK_IMAGE=eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8
FROM ${JDK_IMAGE}
WORKDIR /app
COPY build/sample-app/ext-stub.jar /app/ext-stub.jar
ENV EXT_STUB_BIND=0.0.0.0 \
    EXT_STUB_PORT=18080
USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/ext-stub.jar \"$@\"", "--"]
