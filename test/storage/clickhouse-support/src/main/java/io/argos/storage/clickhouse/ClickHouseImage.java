/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A pinned ClickHouse server image: exact version and multi-arch manifest digest, read from {@code
 * deploy/clickhouse/image.properties} (the single source for Compose and tests, DSN-13-18).
 *
 * @param version exact ClickHouse version, e.g. {@code 26.8.34.13}
 * @param image image reference with tag and digest ({@code repo:tag@sha256:...})
 */
public record ClickHouseImage(String version, String image) {

  private static final Pattern PINNED =
      Pattern.compile("clickhouse/clickhouse-server:[0-9.]+@sha256:[0-9a-f]{64}");

  public ClickHouseImage {
    if (!PINNED.matcher(image).matches()) {
      throw new IllegalArgumentException("Image is not pinned by tag and digest: " + image);
    }
    if (!image.contains(":" + version + "@")) {
      throw new IllegalArgumentException("Image tag does not match version " + version);
    }
  }

  /** The ClickHouse LTS bundled with Argos. */
  public static ClickHouseImage bundled() {
    Properties p = load();
    return new ClickHouseImage(required(p, "version"), required(p, "image"));
  }

  /** The previous LTS, for the upgrade test (T-ST-051). */
  public static ClickHouseImage previousLts() {
    Properties p = load();
    return new ClickHouseImage(
        required(p, "previous-lts.version"), required(p, "previous-lts.image"));
  }

  private static Properties load() {
    Path file = RepositoryLayout.clickhouseDir().resolve("image.properties");
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
    return p;
  }

  private static String required(Properties p, String key) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("image.properties: missing " + key);
    }
    return value.trim();
  }
}
