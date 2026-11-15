/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.server.Compression;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

/**
 * The settings of ADR-0010 C-190 (15 §14, N-14) as packaged: response compression for the UI's
 * static file types only, and the one-year immutable caching of the hashed {@code /assets/**}
 * files. The behaviour on the real server is {@code StaticAssetsSecurityTest} (securityTest).
 */
@Tag("req:N-14")
@Tag("req:SEC-45")
class StaticAssetsConfigurationTest {

  private static Compression packagedCompression() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment)
        .bind("server.compression", Compression.class)
        .orElseThrow(() -> new AssertionError("application.yml has no server.compression"));
  }

  @Test
  @DisplayName(
      "application.yml compresses HTML, CSS, JavaScript and SVG of 2 KB or more, and nothing"
          + " else: no JSON, text/plain or CSV (C-190)")
  void compressionIsLimitedToStaticFileTypes() throws IOException {
    Compression compression = packagedCompression();
    assertThat(compression.getEnabled()).isTrue();
    assertThat(compression.getMimeTypes())
        .containsExactly(
            "text/html", "text/css", "text/javascript", "application/javascript", "image/svg+xml");
    assertThat(compression.getAdditionalMimeTypes()).isEmpty();
    assertThat(compression.getAllMimeTypes())
        .doesNotContain("application/json", "text/plain", "text/csv", "application/xml");
    assertThat(compression.getMinResponseSize()).isEqualTo(DataSize.ofKilobytes(2));
  }

  @Test
  @DisplayName("hashed /assets/** files are cached for a year, publicly and without revalidation")
  void assetsCacheControl() {
    assertThat(StaticAssetsConfiguration.ASSETS_CACHE_CONTROL.getHeaderValue())
        .isEqualTo("max-age=31536000, public, immutable");

    GenericWebApplicationContext context = new GenericWebApplicationContext();
    MockServletContext servletContext = new MockServletContext();
    context.setServletContext(servletContext);
    context.refresh();
    try {
      ResourceHandlerRegistry registry = new ResourceHandlerRegistry(context, servletContext);
      new StaticAssetsConfiguration().addResourceHandlers(registry);
      assertThat(registry.hasMappingForPattern("/assets/**")).isTrue();
      // Only the hashed files: the page and the other static files keep their own handlers.
      assertThat(registry.hasMappingForPattern("/**")).isFalse();
      assertThat(registry.hasMappingForPattern("/index.html")).isFalse();
    } finally {
      context.close();
    }
  }
}
