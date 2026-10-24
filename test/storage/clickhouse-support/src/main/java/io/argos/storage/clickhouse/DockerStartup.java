/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import org.testcontainers.DockerClientFactory;

/**
 * Initialises the Testcontainers Docker client once per JVM, one JVM of the build at a time.
 *
 * <p>With Docker Desktop on Windows the daemon listens on a named pipe ({@code
 * \\.\pipe\docker_engine}). Testcontainers first checks it with {@code Files.exists}, which opens
 * the pipe: while another process holds every pipe instance the check reports the pipe as missing,
 * and Testcontainers then refuses every later attempt in that JVM ("Previous attempts to find a
 * Docker environment failed. Will not retry"). The storage integration test tasks run in parallel
 * JVMs that reach Docker within the same millisecond (10 JVMs started together: 1 to 2 of them
 * failed this way), which failed a whole test task. So the first initialisation of each JVM holds a
 * lock file shared by the repository build and, on a named-pipe host, waits until the pipe can be
 * opened. On Linux and macOS (Unix socket) only the lock applies, which just orders the start.
 */
final class DockerStartup {

  private static final Duration PIPE_WAIT = Duration.ofSeconds(30);
  private static final String WINDOWS_DEFAULT_HOST = "npipe:////./pipe/docker_engine";

  private static boolean initialised;

  private DockerStartup() {}

  /** Initialises the Testcontainers Docker client (idempotent, thread-safe). */
  @SuppressWarnings("try") // the file lock is held for the whole body
  static synchronized void ensureInitialised() {
    if (initialised) {
      return;
    }
    Path lockFile = lockFile();
    try (FileChannel channel =
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      awaitNamedPipe();
      DockerClientFactory.instance().client();
    } catch (IOException e) {
      throw new UncheckedIOException("Docker start-up lock " + lockFile, e);
    }
    initialised = true;
  }

  /** {@code build/tmp/} of the repository (shared by all its test JVMs), else java.io.tmpdir. */
  private static Path lockFile() {
    Path dir;
    try {
      dir = RepositoryLayout.root().resolve("build").resolve("tmp");
    } catch (IllegalStateException e) {
      dir = Paths.get(System.getProperty("java.io.tmpdir"));
    }
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create " + dir, e);
    }
    return dir.resolve("argos-testcontainers-startup.lock");
  }

  /** Waits (bounded) until the Docker named pipe can be opened; no-op for other Docker hosts. */
  private static void awaitNamedPipe() {
    String host = System.getenv("DOCKER_HOST");
    if (host == null || host.isBlank()) {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (!os.startsWith("windows")) {
        return;
      }
      host = WINDOWS_DEFAULT_HOST;
    }
    if (!host.startsWith("npipe:")) {
      return;
    }
    Path pipe = Paths.get(URI.create(host).getPath());
    long deadline = System.nanoTime() + PIPE_WAIT.toNanos();
    while (!Files.exists(pipe)) {
      if (System.nanoTime() - deadline > 0) {
        return; // Testcontainers reports the missing daemon itself
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
