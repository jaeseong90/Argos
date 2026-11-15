// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import java.time.Instant

/**
 * The server container image built by Jib (19 §3.5, ADR-0003 note on 17 §16 #3). Single source of
 * the base image, the non-root user and the image layout; argos.java-server applies them.
 *
 * Volume contract (17 §3.2, §3.4): the server keeps its files below [DATA_VOLUME] (WAL, later the
 * setup token and the spool) and [LOGS_VOLUME] (JSON Lines server log, 17 §11.3), never in the
 * root-owned [WORKING_DIRECTORY]; [ENVIRONMENT] points the defaults there. Both paths must be
 * mounted as volumes that uid 10001 owns and can write (mode 0700); the installer and
 * `argos-env-init.sh` prepare them (WO-40). Jib cannot set the owner of a directory in the image,
 * and a world-writable directory (0777, 1777) would break the 0700 rule of 17 §3.4, so the image
 * ships neither directory, and a new named volume mounted there is owned by root (0755). Known
 * limitation: without prepared volumes the server cannot write its log file (Log4j prints a status
 * ERROR for every event; console logging continues) nor its WAL.
 */
object ServerImage {

  /**
   * Base image: Eclipse Temurin 21 JRE (Ubuntu 26.04), pinned by the digest of its multi-arch index
   * (linux/amd64, linux/arm64). Tag 21-jre = 21.0.13_9-jre-resolute, built 2026-10-29, checked on
   * 2026-11-03 (WO-20). The WO-00 value (21.0.12.1, sha256:2271187...) was superseded by this
   * October 2026 update. Verify: docker buildx imagetools inspect eclipse-temurin:21-jre.
   */
  const val BASE_IMAGE: String =
    "eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a"

  /**
   * Fixed non-root uid:gid. The Temurin image runs as root by default (WO-00 U-3), and uid 10001 has
   * no passwd entry, so HOME is set explicitly ([ENVIRONMENT]).
   */
  const val USER: String = "10001:10001"

  /** Working directory and HOME of the server process (17 §4.1 layout root). */
  const val WORKING_DIRECTORY: String = "/opt/argos"

  /** Where Jib puts the classes, resources and libraries. */
  const val APP_ROOT: String = "/opt/argos/app"

  /** Ports published by the image: HTTP 8080 and gRPC 9100. Management 8081 is never exposed (C-7). */
  val PORTS: List<String> = listOf("8080", "9100")

  /** Mount point of the `argos-data` volume (17 §3.2, §3.4): WAL, setup token, spool. */
  const val DATA_VOLUME: String = "/data"

  /** Mount point of the `argos-logs` volume (17 §3.2, §3.4): server log files. */
  const val LOGS_VOLUME: String = "/logs"

  /**
   * Process environment of the image: HOME for uid 10001 (no passwd entry), the server log
   * directory (`logging.file.path`, read by log4j2-spring.xml as LOG_PATH) and the WAL directory
   * (`argos.wal.dir` by its relaxed environment name) on the volumes of [DATA_VOLUME] and
   * [LOGS_VOLUME]. Compose or `docker run -e` may override them.
   */
  val ENVIRONMENT: Map<String, String> =
    linkedMapOf(
      "HOME" to WORKING_DIRECTORY,
      "LOGGING_FILE_PATH" to "$LOGS_VOLUME/server",
      "ARGOS_WAL_DIR" to "$DATA_VOLUME/wal",
    )

  /** Local image name of jibDockerBuild; publishing to a registry is WO-44. */
  const val IMAGE_NAME: String = "argos-server"

  /** Platforms built by default; `-Pargos.image.platforms=linux/amd64,linux/arm64` overrides. */
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
