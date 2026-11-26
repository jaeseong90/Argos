// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.demo

import io.argos.buildlogic.server.ServerImage

/**
 * The two public demo images of the demo profile (17 §3.2, §3.9, WO-41), built by Jib through the
 * argos.demo-image convention: `argos-demo-app` (sample-apps/demo-app with the Argos agent) and
 * `argos-demo-load` (sample-apps/load). Single source of their names, layout, users, JVM flags and
 * default environment. Both reuse the server image's base, user and label rules ([ServerImage]), so
 * the three images share their base layers.
 *
 * Licenses (ADR-0003 C-33, ADR-0004 C-43, ADR-0010 C-186 (1)): the runtime classpath of both
 * projects is checked by checkLicenses (ArgosProject.isDevelopmentOnly leaves them out), and
 * [FORBIDDEN] keeps the development stack of the shop apps (Hibernate, Logback, H2, MySQL, MariaDB,
 * Oracle, jakarta.annotation-api, AspectJ, the shop apps themselves) out of the demo app.
 *
 * Local builds produce linux/amd64 images (`jibDockerBuild`); the release workflow (release.yml)
 * pushes linux/amd64 + linux/arm64 with -Pargos.image.platforms and -Djib.to.image/-Djib.to.tags
 * and signs them with cosign.
 */
object DemoImage {

  /** Image name of the demo app (order-api and payment-api, one image, `DEMO_ROLE`). */
  const val APP_IMAGE_NAME: String = "argos-demo-app"

  /** Image name of the demo load generator. */
  const val LOAD_IMAGE_NAME: String = "argos-demo-load"

  /** Base image: the server's digest-pinned Temurin 21 JRE (shared layers). */
  const val BASE_IMAGE: String = ServerImage.BASE_IMAGE

  /** Non-root uid:gid of both images (the server's; no passwd entry, so HOME is set). */
  const val USER: String = ServerImage.USER

  /** Root-owned, read-only working directory: LICENSE and NOTICE of the Argos code. */
  const val WORKING_DIRECTORY: String = "/opt/argos-demo"

  /** Where Jib puts the classes, resources and libraries. */
  const val APP_ROOT: String = "$WORKING_DIRECTORY/app"

  /** Directory of the agent jar in the demo app image. */
  const val AGENT_DIRECTORY: String = "/opt/argos-agent"

  /** The agent jar of :agent:agent-dist:agentJar, under a version-free name. */
  const val AGENT_JAR: String = "$AGENT_DIRECTORY/argos-agent.jar"

  /** Writable scratch space (the base image's /tmp; Compose mounts a tmpfs there, read-only root). */
  const val TMP_DIRECTORY: String = "/tmp"

  /** HTTP port of the demo app. */
  const val APP_PORT: String = "8080"

  /**
   * JVM flags of the demo app: the agent (17 §3.9, the point of the demo), a small heap (two apps
   * and the load generator fit next to the server on a 8 GiB host, 17 §3.2), Serial GC, exit on
   * OutOfMemoryError, UTF-8, UTC, home in /tmp.
   */
  val APP_JVM_FLAGS: List<String> =
    listOf(
      "-javaagent:$AGENT_JAR",
      "-Xms64m",
      "-Xmx256m",
      "-XX:+UseSerialGC",
      "-XX:+ExitOnOutOfMemoryError",
      "-Dfile.encoding=UTF-8",
      "-Duser.timezone=UTC",
      "-Duser.home=$TMP_DIRECTORY",
    )

  /**
   * Environment of the demo app image. The agent and app settings (ARGOS_APP_NAME, ARGOS_SERVER_*,
   * DEMO_ROLE, DEMO_DB_*) come from Compose; the agent log directory points at the writable /tmp
   * (otherwise the agent falls back to a directory of its own there, docs/agent-config.md).
   */
  val APP_ENVIRONMENT: Map<String, String> =
    linkedMapOf("HOME" to TMP_DIRECTORY, "ARGOS_LOG_DIR" to "$TMP_DIRECTORY/argos-agent")

  /** JVM flags of the load generator (the flags of its development Dockerfile). */
  val LOAD_JVM_FLAGS: List<String> =
    listOf(
      "-XX:+UseSerialGC",
      "-XX:MaxRAMPercentage=50",
      "-XX:+ExitOnOutOfMemoryError",
      "-Dfile.encoding=UTF-8",
      "-Duser.home=$TMP_DIRECTORY",
    )

  /** Defaults of the load generator image (17 §3.2: order-api at 50 requests/s, MIX=demo). */
  val LOAD_ENVIRONMENT: Map<String, String> =
    linkedMapOf(
      "HOME" to TMP_DIRECTORY,
      "TARGET_URL" to "http://sample-app-a:8080",
      "RATE" to "50",
      "MIX" to "demo",
    )

  /**
   * Components that must never reach the demo app's runtime classpath (C-43, C-186 (1),
   * dev-only.md condition 2): regular expressions over `group:module` of external modules and
   * `project <path>` of project components.
   */
  val FORBIDDEN: List<Regex> =
    listOf(
      "project :sample-apps:shop-.*",
      "org\\.hibernate(\\..*)?:.*",
      "ch\\.qos\\.logback:.*",
      "org\\.springframework\\.boot:spring-boot-starter-logging",
      "com\\.h2database:.*",
      "com\\.mysql:.*",
      "mysql:.*",
      "org\\.mariadb\\.jdbc:.*",
      "com\\.oracle\\.database\\..*:.*",
      "jakarta\\.annotation:jakarta\\.annotation-api",
      "org\\.aspectj:.*",
    ).map { Regex(it) }

  /** The entries of [components] (`group:module` or `project <path>`) that [FORBIDDEN] matches. */
  fun forbidden(components: List<String>): List<String> =
    components.filter { id -> FORBIDDEN.any { it.matches(id) } }

  /**
   * OCI labels of a demo image: the server's label set (19 §3.5: version, revision, licenses
   * Apache-2.0, source, created, base image) with the image's own title and description.
   */
  fun labels(
    title: String,
    description: String,
    version: String,
    revision: String,
    source: String,
    creationTime: String,
  ): Map<String, String> =
    LinkedHashMap(ServerImage.labels(version, revision, source, creationTime)).apply {
      put("org.opencontainers.image.title", title)
      put("org.opencontainers.image.description", description)
    }
}
