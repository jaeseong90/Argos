/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Evidence lines for the PoC report (docs/poc/clickhouse-benchmark.md): measured values and
 * observed server behaviour, appended to {@code build/reports/clickhouse-schema/<topic>.md}.
 */
final class Evidence {

  private Evidence() {}

  /** Appends {@code - key: value} to the topic file. */
  static void record(String topic, String key, Object value) {
    String dir = System.getProperty("argos.reportDir", "build/reports/clickhouse-schema");
    Path file = Paths.get(dir, topic + ".md");
    try {
      Files.createDirectories(file.getParent());
      if (!Files.exists(file)) {
        Files.writeString(
            file, "# " + topic + " (" + Instant.now() + ")\n\n", StandardCharsets.UTF_8);
      }
      Files.writeString(
          file,
          "- " + key + ": " + value + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
