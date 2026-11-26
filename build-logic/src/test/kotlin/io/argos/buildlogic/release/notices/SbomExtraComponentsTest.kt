// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import com.fasterxml.jackson.databind.ObjectMapper
import io.argos.buildlogic.SbomNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * serverSbom (WO-44, SEC-70, T-BLD-008): modules the Spring Boot plugin packs into the boot jar from
 * its own resources (loader, jarmode tools) become SBOM components, once, in a stable order.
 */
@Tag("req:SEC-70")
class SbomExtraComponentsTest {

  private val sbom =
    """{"bomFormat":"CycloneDX","specVersion":"1.6","components":[""" +
      """{"type":"library","group":"io.grpc","name":"grpc-api","version":"1.85.1"}]}"""

  @Test
  @DisplayName("T-BLD-008 extra modules are added as library components with purl and license, without duplicates")
  fun addsExtraComponents() {
    val extra =
      mapOf(
        "org.springframework.boot:spring-boot-loader:4.1.2" to "Apache-2.0",
        "org.springframework.boot:spring-boot-jarmode-tools:4.1.2" to "Apache-2.0",
        "io.grpc:grpc-api:1.85.1" to "Apache-2.0",
      )
    val text = SbomNormalizer.withExtraComponents(sbom, extra)
    val components = ObjectMapper().readTree(text).path("components")
    assertThat(components.map { "${it.path("group").asText()}:${it.path("name").asText()}" })
      .containsExactly(
        "io.grpc:grpc-api",
        "org.springframework.boot:spring-boot-jarmode-tools",
        "org.springframework.boot:spring-boot-loader",
      )
    val loader = components[2]
    assertThat(loader.path("purl").asText()).isEqualTo("pkg:maven/org.springframework.boot/spring-boot-loader@4.1.2?type=jar")
    assertThat(loader.path("licenses")[0].path("license").path("id").asText()).isEqualTo("Apache-2.0")
    assertThat(text).endsWith("}\n").doesNotContain("\r")
    // Deterministic: the same input gives the same bytes; no extras leave the document unchanged.
    assertThat(SbomNormalizer.withExtraComponents(sbom, extra.toList().reversed().toMap())).isEqualTo(text)
    assertThat(SbomNormalizer.withExtraComponents(sbom, emptyMap())).isSameAs(sbom)
  }
}
