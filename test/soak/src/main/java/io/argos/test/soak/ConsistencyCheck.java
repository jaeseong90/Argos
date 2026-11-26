/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The nightly consistency check of 18 §11.2 ("야간 정합 검사 결과 매일, 불일치 0"): {@code java -jar <boot jar>
 * admin consistency check --date <d> --json} (17 §4.4, F-S22) with the server's own configuration
 * as {@code --argos.*} arguments (the password files, never the passwords). The run reads the exit
 * code (0: no rollup mismatch left) and the JSON document of {@code ReconcileReport}: {@code
 * result} and {@code checks[]} ({@code check}, {@code mismatch_minutes}); D-12 rows are recorded
 * only (12 §9.1).
 */
final class ConsistencyCheck {

  /** {@code check} of the D-12 rows (recorded, never repaired). */
  static final String D12_PREFIX = "d12";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Path bootJar;
  private final Map<String, String> serverProperties;
  private final Path work;

  ConsistencyCheck(Path bootJar, Map<String, String> serverProperties, Path work) {
    this.bootJar = bootJar;
    this.serverProperties = Map.copyOf(serverProperties);
    this.work = work;
  }

  /** Runs the check of {@code day}. */
  ConsistencyRun run(LocalDate day) {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx512m");
    command.add("-Duser.timezone=UTC");
    command.add("-Djava.io.tmpdir=" + work.toAbsolutePath());
    command.add("-jar");
    command.add(bootJar.toAbsolutePath().toString());
    command.add("admin");
    command.add("consistency");
    command.add("check");
    command.add("--date");
    command.add(day.toString());
    command.add("--json");
    serverProperties.forEach(
        (k, v) -> {
          if (k.startsWith("argos.")) {
            command.add("--" + k + "=" + v);
          }
        });
    try {
      Files.createDirectories(work);
      Path out = work.resolve("consistency-" + day + ".json");
      Path err = work.resolve("consistency-" + day + ".log");
      Process p =
          new ProcessBuilder(command)
              .directory(work.toFile())
              .redirectOutput(out.toFile())
              .redirectError(err.toFile())
              .start();
      if (!p.waitFor(30, TimeUnit.MINUTES)) {
        p.destroyForcibly();
        return new ConsistencyRun(day, -1, "", 0, 0, "timed out after 30 minutes");
      }
      return parse(day, p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return new ConsistencyRun(day, -1, "", 0, 0, e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ConsistencyRun(day, -1, "", 0, 0, "interrupted");
    }
  }

  /** Reads the command's JSON output (a failure document carries {@code error}). */
  static ConsistencyRun parse(LocalDate day, int exitCode, String stdout) {
    JsonNode root;
    try {
      root = JSON.readTree(stdout.strip().isEmpty() ? "{}" : stdout);
    } catch (JacksonException e) {
      return new ConsistencyRun(day, exitCode, "", 0, 0, "unreadable output: " + oneLine(stdout));
    }
    if (root.hasNonNull("error")) {
      return new ConsistencyRun(day, exitCode, "", 0, 0, root.get("error").asString());
    }
    if (!root.has("result")) {
      return new ConsistencyRun(day, exitCode, "", 0, 0, "no result in the output");
    }
    long rollup = 0;
    long d12 = 0;
    for (JsonNode c : root.path("checks")) {
      long minutes = c.path("mismatch_minutes").asLong(0);
      if (c.path("check").asString("").startsWith(D12_PREFIX)) {
        d12 += minutes;
      } else {
        rollup += minutes;
      }
    }
    return new ConsistencyRun(day, exitCode, root.get("result").asString(), rollup, d12, null);
  }

  private static String oneLine(String text) {
    String t = text.replaceAll("\\s+", " ").strip();
    return t.length() > 200 ? t.substring(0, 197) + "..." : t;
  }
}
