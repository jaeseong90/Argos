/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the repository's bash scripts (Git Bash on Windows, never WSL's System32\bash.exe). */
final class Shell {

  /** Exit code and combined output of one script run. */
  record Result(int exitCode, String output) {}

  private Shell() {}

  /** bash: ARGOS_BASH, then Git for Windows found through git.exe on PATH, then plain "bash". */
  static String bash() {
    String configured = System.getenv("ARGOS_BASH");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return "bash";
    }
    List<Path> candidates = new ArrayList<>();
    for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator, -1)) {
      try {
        Path dir = Path.of(entry.trim());
        if (Files.isRegularFile(dir.resolve("git.exe"))) {
          // <Git>\cmd\git.exe or <Git>\mingw64\bin\git.exe -> <Git>\bin\bash.exe
          candidates.add(dir.resolveSibling("bin").resolve("bash.exe"));
          if (dir.getParent() != null) {
            candidates.add(dir.getParent().resolveSibling("bin").resolve("bash.exe"));
          }
        }
      } catch (InvalidPathException e) {
        // Not a directory name; ignore.
      }
    }
    String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
    candidates.add(Path.of(programFiles, "Git", "bin", "bash.exe"));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    throw new AssertionError(
        "Git Bash not found (docs/dev-setup.md requires it on Windows); set ARGOS_BASH. Tried "
            + candidates);
  }

  /** A path as the scripts expect it on every OS: forward slashes. */
  static String path(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  static Result run(
      Path script,
      List<String> args,
      Map<String, String> environment,
      Path output,
      Duration timeout)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(bash());
    command.add(path(script));
    command.addAll(args);
    Files.createDirectories(output.toAbsolutePath().getParent());
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().putAll(environment);
    Process process = builder.start();
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      throw new AssertionError(
          script.getFileName() + " did not finish within " + timeout + ":\n" + read(output));
    }
    return new Result(process.exitValue(), read(output));
  }

  private static String read(Path output) throws IOException {
    return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
  }
}
