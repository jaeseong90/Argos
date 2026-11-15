// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import java.time.Instant

/**
 * The server container image built by Jib (19 §3.5, ADR-0003 note on 17 §16 #3). Single source of
 * the base image, the non-root user, the image layout and the JVM defaults; argos.java-server
 * applies them.
 *
 * Volume contract (17 §3.2, §3.4): the server keeps its files below [DATA_VOLUME] (WAL, setup token,
 * JWT signing key, temporary files, later the spool) and [LOGS_VOLUME] (JSON Lines server log,
 * 17 §11.3), never in the root-owned [WORKING_DIRECTORY]; [ENVIRONMENT] and [JVM_FLAGS] point the
 * defaults there. The image ships [WRITABLE_DIRECTORIES] owned by [USER] with mode
 * [DIRECTORY_MODE] (WO-40): Jib adds them from `extraDirectories` and [ImageOwnershipExtension]
 * sets their owner at build time. An empty Docker named volume mounted on `/data` or `/logs` copies
 * that owner and mode, so new `argos-data` and `argos-logs` volumes need no preparation; a bind
 * mount (or a volume that already holds files) keeps its own owner and must already be owned by
 * uid 10001 with `/data/tmp` present. Nothing is world-writable (no 0777 or 1777).
 */
object ServerImage {

  /**
   * Base image: Eclipse Temurin 21 JRE (Ubuntu 26.04), pinned by the digest of its multi-arch index
   * (linux/amd64, linux/arm64, linux/ppc64le, linux/s390x). Tag 21-jre = 21.0.13_9-jre-resolute,
   * rebuilt 2026-11-11 (same JDK, updated Ubuntu packages), checked on 2026-11-14 (WO-40). Earlier
   * pins: WO-20 sha256:0b99b33... (2026-10-29 build), WO-00 sha256:2271187... (21.0.12.1). The image
   * contains wget and curl (the Compose healthcheck uses wget, 17 §3.2). Verify: docker buildx
   * imagetools inspect eclipse-temurin:21-jre.
   */
  const val BASE_IMAGE: String =
    "eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2"

  /**
   * Fixed non-root uid:gid. The Temurin image runs as root by default (WO-00 U-3), and uid 10001 has
   * no passwd entry, so HOME and `user.home` are set explicitly ([ENVIRONMENT], [JVM_FLAGS]).
   */
  const val USER: String = "10001:10001"

  /** Working directory of the server process (17 §4.1 layout root); root-owned, read-only for it. */
  const val WORKING_DIRECTORY: String = "/opt/argos"

  /** Where Jib puts the classes, resources and libraries. */
  const val APP_ROOT: String = "/opt/argos/app"

  /** Ports published by the image: HTTP 8080 and gRPC 9100. Management 8081 is never exposed (C-7). */
  val PORTS: List<String> = listOf("8080", "9100")

  /** Mount point of the `argos-data` volume (17 §3.2, §3.4): WAL, setup token, JWT key, spool. */
  const val DATA_VOLUME: String = "/data"

  /** Mount point of the `argos-logs` volume (17 §3.2, §3.4): server log files. */
  const val LOGS_VOLUME: String = "/logs"

  /** Temporary files of the JVM (`java.io.tmpdir`: Tomcat work directory, uploads). */
  const val TMP_DIRECTORY: String = "$DATA_VOLUME/tmp"

  /** Directories the image ships for the server user, parents before children. */
  val WRITABLE_DIRECTORIES: List<String> = listOf(DATA_VOLUME, TMP_DIRECTORY, LOGS_VOLUME)

  /** Mode of [WRITABLE_DIRECTORIES] (octal, owner only; 17 §3.4). */
  const val DIRECTORY_MODE: String = "700"

  /**
   * Process environment of the image (Compose or `docker run -e` may override each one):
   * - HOME for uid 10001 (no passwd entry): the data volume.
   * - The server log directory (`logging.file.path`, read by log4j2-spring.xml as LOG_PATH).
   * - The WAL directory (`argos.wal.dir`).
   * - The setup token file (`argos.auth.setup-token-file`, 17 §6.4, 16 §7.8; DSN-17-06's
   *   `conf/setup-token` maps to the data volume, which is the writable place in a container).
   * - The JWT signing key file (`argos.jwt-key-file`, 16 §7.1): created owner-only at the first
   *   start when missing; Compose may point it at a prepared secret file instead.
   *
   * The names are the relaxed environment names of the `argos.*` keys (Spring binds `-` in a key to
   * `_` in the variable name).
   */
  val ENVIRONMENT: Map<String, String> =
    linkedMapOf(
      "HOME" to DATA_VOLUME,
      "LOGGING_FILE_PATH" to "$LOGS_VOLUME/server",
      "ARGOS_WAL_DIR" to "$DATA_VOLUME/wal",
      "ARGOS_AUTH_SETUP_TOKEN_FILE" to "$DATA_VOLUME/setup-token",
      "ARGOS_JWT_KEY_FILE" to "$DATA_VOLUME/jwt.key",
    )

  /**
   * JVM flags of the image entrypoint (`java <flags> -cp ... ServerApplication`): 17 §7.2 adapted to
   * a container. G1 with a 200 ms pause goal, 512 MiB direct memory (gRPC/Netty buffers), exit on
   * OutOfMemoryError (the container restarts instead of running damaged), UTF-8, UTC, and the
   * temporary and home directories on the data volume.
   *
   * Not here: the heap (-Xms/-Xmx: the installer sizes it from the host RAM, 17 §7.3, and Compose
   * passes it in JAVA_TOOL_OPTIONS; without it the JVM takes 25% of the container limit), heap dumps
   * (a dump holds secrets and collected data and is as large as the heap), GC log files (-Xlog
   * refuses to start the JVM when the file's directory does not exist yet) and
   * `java.security.egd`. Operators add such options in JAVA_TOOL_OPTIONS. The JVM reads
   * JAVA_TOOL_OPTIONS before these command-line flags, so a flag listed here keeps its value; a
   * different value needs `_JAVA_OPTIONS` (read after the command line) or another entrypoint.
   */
  val JVM_FLAGS: List<String> =
    listOf(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=200",
      "-XX:MaxDirectMemorySize=512m",
      "-XX:+ExitOnOutOfMemoryError",
      "-Dfile.encoding=UTF-8",
      "-Duser.timezone=UTC",
      "-Djava.io.tmpdir=$TMP_DIRECTORY",
      "-Duser.home=$DATA_VOLUME",
    )

  /** Local image name of jibDockerBuild; publishing to a registry is WO-44. */
  const val IMAGE_NAME: String = "argos-server"

  /**
   * Platforms built by default: linux/amd64 for the local `jibDockerBuild` of WO-40. The multi-arch
   * image (linux/amd64 + linux/arm64, `-Pargos.image.platforms=linux/amd64,linux/arm64`) is built
   * and pushed by the release pipeline (WO-44); jibDockerBuild loads one platform only.
   */
  const val DEFAULT_PLATFORMS: String = "linux/amd64"

  /**
   * Image creation time for Jib (`container.creationTime`): the commit time from SOURCE_DATE_EPOCH
   * (seconds, 19 §3.7) when set, otherwise the Unix epoch, so that two builds of the same commit
   * produce the same image.
   */
  fun creationTime(sourceDateEpoch: String?): String {
    val value = sourceDateEpoch?.trim().orEmpty()
    if (value.isEmpty()) return "EPOCH"
    val seconds =
      value.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("SOURCE_DATE_EPOCH must be a non-negative integer, got '$value'")
    return Instant.ofEpochSecond(seconds).toString()
  }

  /**
   * OCI annotations of 19 §3.5 (`org.opencontainers.image.*`): version, revision, licenses and
   * source, plus title, description, the base image, and `created` (the image creation time, which
   * otherwise the base image's label would claim).
   *
   * @param creationTime value of [creationTime]
   */
  fun labels(version: String, revision: String, source: String, creationTime: String): Map<String, String> =
    linkedMapOf(
      "org.opencontainers.image.title" to "argos-server",
      "org.opencontainers.image.description" to "Argos APM collector server",
      "org.opencontainers.image.version" to version,
      "org.opencontainers.image.revision" to revision,
      "org.opencontainers.image.licenses" to "Apache-2.0",
      "org.opencontainers.image.source" to source,
      "org.opencontainers.image.created" to (if (creationTime == "EPOCH") Instant.EPOCH.toString() else creationTime),
      "org.opencontainers.image.base.name" to BASE_IMAGE.substringBefore('@'),
      "org.opencontainers.image.base.digest" to BASE_IMAGE.substringAfter('@'),
    )

  /** `linux/amd64,linux/arm64` -> (os, architecture) pairs. */
  fun platforms(spec: String): List<Pair<String, String>> =
    spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
      val parts = it.split('/')
      require(parts.size == 2 && parts.all { p -> p.isNotBlank() }) {
        "Platform must be <os>/<architecture>, got '$it'"
      }
      parts[0] to parts[1]
    }
}
