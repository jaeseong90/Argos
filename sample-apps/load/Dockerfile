# SPDX-License-Identifier: Apache-2.0
#
# Demo load generator image (becomes the public argos-demo-load image; publishing is WO-41/WO-44).
# Argos code only: load.jar has no third-party dependency (checkDemoLoadDependencies) and k6 is
# not in this image (AGPL-3.0, ADR-0003 C-33). Build after the jar:
#   ./gradlew :sample-apps:load:sampleImage                      -> argos-sample/load:dev
#   docker build --build-arg JDK_IMAGE=<image@digest> sample-apps/load
# Run (docs/17 §3.2 demo profile; the variables are listed by `--help`):
#   docker run --rm -e TARGET_URL=http://sample-app-a:8080 -e RATE=50 -e MIX=default argos-sample/load:dev
# The default base image (Java 17, the generator's release) is pinned by its multi-arch index digest.
ARG JDK_IMAGE=eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8
FROM ${JDK_IMAGE}
# The release build (WO-44) passes the Argos version; otherwise the base image's label would show.
ARG ARGOS_VERSION=dev
# The licenses label is the license of the Argos code in this image (load.jar, which carries
# META-INF/LICENSE and NOTICE). The base JRE (Eclipse Temurin: GPL-2.0-only WITH
# Classpath-exception-2.0) and the OS packages of the base image are under their own licenses and
# need their own notices; how the public image gives them is decided with the server image
# (WO-41/WO-44).
LABEL org.opencontainers.image.title="argos-demo-load" \
      org.opencontainers.image.description="Argos demo load generator: fixed arrival rate over the sample app endpoints" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${ARGOS_VERSION}"
WORKDIR /app
COPY build/sample-app/load.jar /app/load.jar
ENV TARGET_URL=http://sample-app-a:8080 \
    RATE=50 \
    MIX=default \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=50"
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/load.jar \"$@\"", "--"]
