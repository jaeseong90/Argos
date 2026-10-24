/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the repository and its {@code deploy/clickhouse} directory (schema, rollups, config).
 *
 * <p>Order: the system property {@code argos.repositoryRoot} (set by the Gradle test tasks), then
 * the first parent of the working directory that contains {@code deploy/clickhouse/schema}.
 */
public final class RepositoryLayout {

  /** System property naming the repository root. */
  public static final String ROOT_PROPERTY = "argos.repositoryRoot";

  private RepositoryLayout() {}

  /** The repository root. */
  public static Path root() {
    String configured = System.getProperty(ROOT_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      Path root = Paths.get(configured).toAbsolutePath().normalize();
      if (!Files.isDirectory(root.resolve("deploy/clickhouse/schema"))) {
        throw new IllegalStateException(
            ROOT_PROPERTY + "=" + configured + " has no deploy/clickhouse/schema directory");
      }
      return root;
    }
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("deploy/clickhouse/schema"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Repository root not found: set -D" + ROOT_PROPERTY + " or run below the repository");
  }

  /** {@code deploy/clickhouse}: schema/, rollups/, config.d/, users.d/, image.properties. */
  public static Path clickhouseDir() {
    return root().resolve("deploy/clickhouse");
  }
}
