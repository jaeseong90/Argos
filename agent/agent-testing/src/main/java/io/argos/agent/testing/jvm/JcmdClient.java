/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * {@code jcmd <pid> <command>} with the JDK of the target JVM (18 §5.2 {@code jcmd()}, §5.9):
 * {@code GC.run}, {@code GC.class_histogram}, {@code Thread.print}, {@code VM.native_memory
 * summary}. The attach mechanism needs no file in the target's temp directory on Windows (named
 * pipe); on Linux HotSpot uses {@code /tmp/.java_pid<pid>}, outside the run's watched temp
 * directory.
 */
public final class JcmdClient {
  private static final long TIMEOUT_SECONDS = 120;

  private final Path jcmd;
  private final long pid;
  private final Function<List<String>, String> executor;

  /**
   * @param jcmd the {@code jcmd} executable of the target's JDK
   * @param pid the target process
   */
  public JcmdClient(Path jcmd, long pid) {
    this.jcmd = jcmd;
    this.pid = pid;
    this.executor = null;
  }

  /**
   * A client whose {@code jcmd} runs elsewhere ({@code docker exec <container> jcmd 1 ...}): {@code
   * executor} gets the arguments after {@code jcmd} and returns the output.
   */
  public JcmdClient(long pid, Function<List<String>, String> executor) {
    this.jcmd = null;
    this.pid = pid;
    this.executor = executor;
  }

  public long pid() {
    return pid;
  }

  /** Runs {@code jcmd <pid> <command...>}; fails on a non-zero exit code or a timeout. */
  public String run(String... command) {
    if (executor != null) {
      List<String> arguments = new ArrayList<>();
      arguments.add(Long.toString(pid));
      arguments.addAll(List.of(command));
      return executor.apply(arguments);
    }
    List<String> line = new ArrayList<>();
    line.add(jcmd.toString());
    line.add(Long.toString(pid));
    line.addAll(List.of(command));
    try {
      Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
      CompletableFuture<String> output = read(process.getInputStream());
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(String.join(" ", line) + " timed out");
      }
      String text = output.get(10, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            String.join(" ", line) + " exited with " + process.exitValue() + ":\n" + text);
      }
      return text;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted: " + String.join(" ", line), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("cannot read the output of " + String.join(" ", line), e);
    }
  }

  /** {@code GC.run}. */
  public void gcRun() {
    run("GC.run");
  }

  /** {@code GC.class_histogram} (all objects; the command forces a full GC first). */
  public ClassHistogram classHistogram() {
    return ClassHistogram.parse(run("GC.class_histogram"));
  }

  /** {@code Thread.print}. */
  public String threadPrint() {
    return run("Thread.print");
  }

  /** {@code VM.native_memory summary} (the JVM must run with NMT, see {@code JvmOptions}). */
  public NmtSummary nativeMemorySummary() {
    return NmtSummary.parse(run("VM.native_memory", "summary"));
  }

  private static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }
}
