// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The server image rules of 19 §3.5 and the ADR-0003 note on 17 §16 #3 (ServerImage). */
@Tag("req:DSN-19-05")
class ServerImageTest {

  @Test
  @DisplayName("base image pinned by digest, non-root 10001:10001, management port not exposed")
  fun imageRules() {
    assertThat(ServerImage.BASE_IMAGE).matches("eclipse-temurin:21-jre@sha256:[0-9a-f]{64}")
    assertThat(ServerImage.USER).isEqualTo("10001:10001")
    assertThat(ServerImage.PORTS).containsExactly("8080", "9100").doesNotContain("8081")
    assertThat(ServerImage.ENVIRONMENT).containsEntry("HOME", ServerImage.WORKING_DIRECTORY)
  }

  @Test
  @DisplayName("log and WAL directories on the /logs and /data volumes of 17 §3.2")
  fun volumePaths() {
    assertThat(ServerImage.DATA_VOLUME).isEqualTo("/data")
    assertThat(ServerImage.LOGS_VOLUME).isEqualTo("/logs")
    assertThat(ServerImage.ENVIRONMENT)
      .containsEntry("LOGGING_FILE_PATH", "/logs/server")
      .containsEntry("ARGOS_WAL_DIR", "/data/wal")
    // Nothing the server writes may default to the root-owned working directory of the image.
    val written = ServerImage.ENVIRONMENT.filterKeys { it != "HOME" }.values
    assertThat(written.filter { it.startsWith(ServerImage.WORKING_DIRECTORY) }).isEmpty()
  }

  @Test
  @DisplayName("creation time: SOURCE_DATE_EPOCH as ISO-8601, otherwise the Unix epoch")
  fun creationTime() {
    assertThat(ServerImage.creationTime(null)).isEqualTo("EPOCH")
    assertThat(ServerImage.creationTime(" ")).isEqualTo("EPOCH")
    assertThat(ServerImage.creationTime("1793700000")).isEqualTo("2026-11-03T10:00:00Z")
    assertThatThrownBy { ServerImage.creationTime("yesterday") }.isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { ServerImage.creationTime("-1") }.isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  @DisplayName("OCI labels: version, revision, licenses=Apache-2.0, source")
  fun labels() {
    val labels = ServerImage.labels("1.2.3", "abc123", "https://github.com/argos-apm/argos", "EPOCH")
    assertThat(labels)
      .containsEntry("org.opencontainers.image.version", "1.2.3")
      .containsEntry("org.opencontainers.image.revision", "abc123")
      .containsEntry("org.opencontainers.image.licenses", "Apache-2.0")
      .containsEntry("org.opencontainers.image.source", "https://github.com/argos-apm/argos")
      .containsEntry("org.opencontainers.image.created", "1970-01-01T00:00:00Z")
      .containsEntry("org.opencontainers.image.base.name", "eclipse-temurin:21-jre")
    assertThat(labels["org.opencontainers.image.base.digest"]).startsWith("sha256:")
    assertThat(ServerImage.labels("1", "r", "s", "2026-11-03T10:00:00Z"))
      .containsEntry("org.opencontainers.image.created", "2026-11-03T10:00:00Z")
  }

  @Test
  @DisplayName("platform list parsing")
  fun platforms() {
    assertThat(ServerImage.platforms("linux/amd64, linux/arm64"))
      .containsExactly("linux" to "amd64", "linux" to "arm64")
    assertThatThrownBy { ServerImage.platforms("amd64") }.isInstanceOf(IllegalArgumentException::class.java)
  }
}
