/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.VendorDriver;
import io.argos.sample.common.diag.RuntimeInfo;
import io.argos.sample.common.diag.StreamChunks;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The switches and catalog additions of the compatibility matrix (WO-50): SAMPLE_CONTAINER, the
 * vendor databases' driver folder, GET /api/stream, the method-less /api/any and the WAR-only GET
 * /api/jsp-forward (ADR-0010 C-127, C-162 (2)(3), 18 §6.3).
 */
class MatrixSwitchesTest {

  private static final BuildInfo BUILD = new BuildInfo("shop-test", "1.0");

  private static SampleConfig config(Map<String, String> env) {
    return SampleConfig.from(env, new Properties(), BUILD);
  }

  @Test
  @DisplayName("SAMPLE_CONTAINER: tomcat by default, jetty and undertow, anything else names it")
  void containerSwitch() {
    assertThat(config(new HashMap<>()).container()).isEqualTo(ServletContainer.TOMCAT);
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_CONTAINER", " Jetty ");
    assertThat(config(env).container()).isEqualTo(ServletContainer.JETTY);
    env.put("SAMPLE_CONTAINER", "undertow");
    assertThat(config(env).container()).isEqualTo(ServletContainer.UNDERTOW);
    env.put("SAMPLE_CONTAINER", "netty");
    assertThatThrownBy(() -> config(env)).hasMessageContaining("SAMPLE_CONTAINER");
    assertThat(ServletContainer.kindOf("Apache Tomcat/10.1.55")).isEqualTo("tomcat");
    assertThat(ServletContainer.kindOf("jetty/12.0.30")).isEqualTo("jetty");
    assertThat(ServletContainer.kindOf("Undertow - 2.3.20.Final")).isEqualTo("undertow");
    assertThat(ServletContainer.kindOf(null)).isEqualTo("other");
  }

  @Test
  @DisplayName(
      "SAMPLE_DB=tibero|altibase|cubrid|db2: driver folder from SAMPLE_JDBC_DRIVER_DIR, else"
          + " ARGOS_TEST_JDBC_DRIVER_DIR; without a usable folder the start fails with the variable")
  void vendorDriverFolder(@TempDir Path empty) {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_DB", "db2");
    assertThat(config(env).db()).isEqualTo(DbKind.DB2);
    assertThat(config(env).jdbcDriverDir()).isNull();
    assertThatThrownBy(() -> config(env).poolDriverClass())
        .hasMessageContaining("SAMPLE_JDBC_DRIVER_DIR")
        .hasMessageContaining(DbKind.DB2.driverClass());
    env.put("ARGOS_TEST_JDBC_DRIVER_DIR", "/runner/drivers");
    assertThat(config(env).jdbcDriverDir()).isEqualTo("/runner/drivers");
    env.put("SAMPLE_JDBC_DRIVER_DIR", empty.toString());
    assertThat(config(env).jdbcDriverDir()).isEqualTo(empty.toString());
    assertThatThrownBy(() -> config(env).poolDriverClass()).hasMessageContaining("no .jar file");
    // A driver that the class path has is used directly (H2 here: not a vendor database).
    assertThat(VendorDriver.driverClassName(DbKind.H2, null)).isEqualTo("org.h2.Driver");
  }

  @Test
  @DisplayName("Vendor driver jars of the folder are taken in name order, other files ignored")
  void vendorDriverJars(@TempDir Path dir) throws IOException {
    Files.write(dir.resolve("b.jar"), new byte[0]);
    Files.write(dir.resolve("a.jar"), new byte[0]);
    Files.write(dir.resolve("readme.txt"), new byte[0]);
    assertThat(jarNames(dir.toFile())).containsExactly("a.jar", "b.jar");
  }

  @Test
  @DisplayName(
      "GET /api/stream body: 3 chunks, each flushed (StreamingResponseBody, C-162 (3)); the catalog"
          + " has stream, any.get, any.post and the WAR-only jsp-forward")
  void streamAndCatalog() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int[] flushes = {0};
    OutputStream out =
        new FilterOutputStream(bytes) {
          @Override
          public void flush() throws IOException {
            flushes[0]++;
            super.flush();
          }
        };
    StreamChunks.write(out);
    assertThat(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
        .isEqualTo("chunk 1\nchunk 2\nchunk 3\n")
        .endsWith(ShopCatalog.STREAM_LAST_CHUNK + "\n");
    assertThat(flushes[0]).isEqualTo(ShopCatalog.STREAM_CHUNKS);

    Map<String, CatalogEntry> entries = new HashMap<>();
    for (CatalogEntry entry : ShopCatalog.entries()) {
      entries.put(entry.id(), entry);
    }
    assertThat(entries.get("stream").route()).isEqualTo("/api/stream");
    assertThat(entries.get("any.get").method()).isEqualTo("GET");
    assertThat(entries.get("any.post").method()).isEqualTo("POST");
    assertThat(entries.get("any.post").route()).isEqualTo("/api/any");
    CatalogEntry jsp = entries.get("jsp-forward");
    assertThat(jsp.requires()).isEqualTo(RuntimeInfo.JSP_CAPABILITY);
    assertThat(jsp.route()).isEqualTo("/api/jsp-forward");
  }

  private static List<String> jarNames(File dir) {
    List<String> names = new ArrayList<>();
    for (URL url : VendorDriver.jars(dir, DbKind.DB2)) {
      String path = url.getPath();
      names.add(path.substring(path.lastIndexOf('/') + 1));
    }
    return names;
  }
}
