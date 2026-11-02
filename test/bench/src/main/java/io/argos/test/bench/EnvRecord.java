/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The environment record of 18 §7.1, written by {@code bench-env.sh} ({@code env-record.json}) and
 * copied into every set directory. Its {@code valid} flag is false when the machine is not fit for
 * a verdict (CPU governor not {@code performance}, no machine profile, an override for smoke runs);
 * the report is then INVALID with the record's {@code problems} as reasons.
 */
final class EnvRecord {
  private EnvRecord() {}

  /** Name of the copy in a set directory. */
  static final String FILE_NAME = "env-record.json";

  /** Reads a record; a missing file or another format is a usage error. */
  static Map<String, Object> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new UsageException("no environment record " + file + " (run bench-env.sh first)");
    }
    Map<String, Object> record = Json.object(FileTools.readJson(file));
    if (!"bench-env.sh".equals(record.get("tool"))) {
      throw new UsageException(file + " is not a bench-env.sh record");
    }
    return record;
  }

  /** The record of a set directory, or null when the set has none (WO-02 sets). */
  static Map<String, Object> ofSet(Path setDir) throws IOException {
    Path file = setDir.resolve(FILE_NAME);
    return Files.isRegularFile(file) ? read(file) : null;
  }

  /**
   * Reasons why the record does not allow a verdict; empty when it does. A missing record is a
   * reason itself (the environment of 18 §7.1 is unknown).
   */
  static List<String> problems(Map<String, Object> record) {
    List<String> problems = new ArrayList<>();
    if (record == null) {
      problems.add("no bench-env.sh environment record (18 §7.1)");
      return problems;
    }
    for (Object p : Json.array(record.get("problems"))) {
      problems.add("environment: " + p);
    }
    if (!Boolean.TRUE.equals(record.get("valid")) && problems.isEmpty()) {
      problems.add("environment: record not marked valid");
    }
    return problems;
  }
}
