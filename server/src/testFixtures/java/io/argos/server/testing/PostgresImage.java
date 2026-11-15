/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing;

import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The pinned PostgreSQL image of {@code deploy/postgres/image.properties} (single source for
 * Compose and tests, like {@code deploy/clickhouse/image.properties}).
 *
 * @param version exact PostgreSQL version, e.g. {@code 16.15}
 * @param image image reference with tag and digest ({@code postgres:<version>@sha256:...})
 */
public record PostgresImage(String version, String image) {

  private static final Pattern PINNED = Pattern.compile("postgres:[0-9.]+@sha256:[0-9a-f]{64}");

  public PostgresImage {
    if (!PINNED.matcher(image).matches()) {
      throw new IllegalArgumentException("Image is not pinned by tag and digest: " + image);
    }
    if (!image.contains(":" + version + "@")) {
      throw new IllegalArgumentException("Image tag does not match version " + version);
    }
    if (!version.startsWith("16.")) {
      throw new IllegalArgumentException("Argos uses PostgreSQL 16 (13 §12), got " + version);
    }
  }

  /** {@code deploy/postgres} of the repository. */
  public static Path deployDirectory() {
    return RepositoryLayout.root().resolve("deploy").resolve("postgres");
  }

  /** The PostgreSQL image used by Argos. */
  public static PostgresImage bundled() {
    Path file = deployDirectory().resolve("image.properties");
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
    return new PostgresImage(required(p, "version", file), required(p, "image", file));
  }

  private static String required(Properties p, String key, Path file) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(file + ": missing " + key);
    }
    return value.trim();
  }
}
