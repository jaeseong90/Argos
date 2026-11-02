/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external tools (docker, jcmd, java -version, git) with a timeout. */
final class Cmd {
  private Cmd() {}

  /**
   * Charset of tool output: Windows console tools (powercfg, PowerShell tables) write the native
   * code page; the other tools here write ASCII or UTF-8.
   */
  private static final Charset TOOL_CHARSET =
      Platform.isWindows()
          ? Charset.forName(System.getProperty("native.encoding", "UTF-8"), StandardCharsets.UTF_8)
          : StandardCharsets.UTF_8;

  /** Exit code (-1 when it timed out and was killed) and the merged stdout and stderr. */
  record Result(int exitCode, String output) {
    boolean ok() {
      return exitCode == 0;
    }
  }

  static Result run(List<String> command, Duration timeout) throws InterruptedException {
    return run(command, timeout, Map.of(), null);
  }

  /**
   * Runs {@code command} with extra environment {@code env}; the merged output goes to {@code
   * outputFile} when given (the result then carries no output), otherwise into the result.
   */
  static Result run(
      List<String> command, Duration timeout, Map<String, String> env, Path outputFile)
      throws InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().putAll(env);
    if (outputFile != null) {
      builder.redirectOutput(outputFile.toFile());
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Result(-2, "cannot start " + command.get(0) + ": " + e.getMessage());
    }
    try {
      process.getOutputStream().close();
    } catch (IOException e) {
      // The tool already exited (its stdin is gone); the exit code below tells what happened.
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread reader = null;
    if (outputFile == null) {
      InputStream in = process.getInputStream();
      reader =
          Thread.ofPlatform()
              .daemon()
              .name("bench-cmd-output")
              .start(
                  () -> {
                    try {
                      in.transferTo(bytes);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
    }
    boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!done) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    if (reader != null) {
      reader.join(TimeUnit.SECONDS.toMillis(10));
    }
    return new Result(done ? process.exitValue() : -1, bytes.toString(TOOL_CHARSET));
  }
}
