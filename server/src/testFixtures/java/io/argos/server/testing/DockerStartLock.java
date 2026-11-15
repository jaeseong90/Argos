/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing;

import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

/**
 * Serialises container starts across the JVMs of one repository build: the same lock file as
 * clickhouse-support's {@code DockerStartup} ({@code build/tmp/argos-testcontainers-startup.lock}),
 * held for the whole start of each container. With Docker Desktop on Windows the daemon listens on
 * a named pipe that refuses connections while every pipe instance is busy; parallel test JVMs that
 * start containers at the same moment then fail (see {@code DockerStartup}).
 *
 * <p>The Testcontainers client is initialised first, through clickhouse-support (which takes the
 * same lock once per JVM), and only then is the lock taken here: a JVM cannot hold two overlapping
 * locks on one file.
 */
final class DockerStartLock {

  /** File name used by clickhouse-support's DockerStartup. */
  static final String LOCK_FILE = "argos-testcontainers-startup.lock";

  private static final Object JVM_LOCK = new Object();

  private DockerStartLock() {}

  /** Runs {@code start} while holding the repository-wide Docker start lock. */
  @SuppressWarnings("try") // the lock is held for the whole body
  static <T> T withLock(Supplier<T> start) {
    ArgosClickHouse.publishesOnLoopback(); // initialises the Docker client (DockerStartup) once
    Path lockFile = lockFile();
    synchronized (JVM_LOCK) {
      try (FileChannel channel =
              FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock lock = channel.lock()) {
        return start.get();
      } catch (IOException e) {
        throw new UncheckedIOException("Docker start lock " + lockFile, e);
      }
    }
  }

  static Path lockFile() {
    Path dir = RepositoryLayout.root().resolve("build").resolve("tmp");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create " + dir, e);
    }
    return dir.resolve(LOCK_FILE);
  }
}
