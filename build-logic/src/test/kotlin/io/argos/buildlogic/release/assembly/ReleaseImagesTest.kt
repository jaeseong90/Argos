// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import io.argos.buildlogic.demo.DemoImage
import io.argos.buildlogic.repositoryRoot
import io.argos.buildlogic.runScript
import io.argos.buildlogic.server.ServerImage
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The three public images of the release (server, argos-demo-app, argos-demo-load; C-171 (1),
 * C-186 (1), 19 §3.5): one digest-pinned base for all of them, the platforms of the multi-arch
 * push, and the base that test/release/check-image-arch.sh expects. That the pin is a multi-arch
 * index is shown by building the arm64 images from it (jibBuildTar -Pargos.image.platforms=
 * linux/arm64 + check-image-arch.sh, run by the integration step and the release workflow).
 */
@Tag("req:C-171")
class ReleaseImagesTest {

  @Test
  @DisplayName("T-BLD-022 server and demo images share one base pinned by digest; labels name it")
  fun oneBase() {
    assertThat(ServerImage.BASE_IMAGE).matches("eclipse-temurin:21-jre@sha256:[0-9a-f]{64}")
    assertThat(DemoImage.BASE_IMAGE).isEqualTo(ServerImage.BASE_IMAGE)
    val labels = ServerImage.labels("0.1.0", "c".repeat(40), "https://example.org/argos", "EPOCH")
    assertThat(labels["org.opencontainers.image.base.name"] + "@" + labels["org.opencontainers.image.base.digest"])
      .isEqualTo(ServerImage.BASE_IMAGE)
    val demo = DemoImage.labels("argos-demo-app", "Demo", "0.1.0", "c".repeat(40), "https://example.org/argos", "EPOCH")
    assertThat(demo).containsEntry("org.opencontainers.image.base.digest", ServerImage.BASE_IMAGE.substringAfter('@'))
    assertThat(demo).containsEntry("org.opencontainers.image.licenses", "Apache-2.0")
    assertThat(DemoImage.USER).isEqualTo(ServerImage.USER).isEqualTo("10001:10001")
  }

  @Test
  @DisplayName("T-BLD-022 release platforms linux/amd64 + linux/arm64; local default stays linux/amd64")
  fun platforms() {
    assertThat(ServerImage.platforms("linux/amd64,linux/arm64")).containsExactly("linux" to "amd64", "linux" to "arm64")
    assertThat(ServerImage.platforms(ServerImage.DEFAULT_PLATFORMS)).containsExactly("linux" to "amd64")
  }

  @Test
  @DisplayName("T-BLD-022 check-image-arch.sh expects exactly ServerImage.BASE_IMAGE")
  fun scriptReadsTheConstant() {
    val result = runScript(File(repositoryRoot, "test/release/check-image-arch.sh"), listOf("--print-expected-base"))
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output.trim()).isEqualTo(ServerImage.BASE_IMAGE)
  }
}
