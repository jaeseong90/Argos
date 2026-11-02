/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Records what appears in an app's temp directory (T-BLD-006, "기동 중 /tmp 감시"): a {@link
 * WatchService} thread sees every entry created directly in the directory, also ones deleted again
 * at once, and {@link #created()} adds the current tree (relative paths, '/' separated). Started
 * before the process is spawned, closed with the app.
 */
public final class TempWatch implements AutoCloseable {
  private final Path dir;
  private final WatchService service;
  private final Set<String> seen = new ConcurrentSkipListSet<>();
  private final Map<String, Long> firstSeenMillis = new ConcurrentHashMap<>();
  private final long startNanos = System.nanoTime();
  private final Thread thread;

  private TempWatch(Path dir) throws IOException {
    this.dir = dir;
    this.service = FileSystems.getDefault().newWatchService();
    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE);
    this.thread = new Thread(this::run, "temp-watch-" + dir.getFileName());
    thread.setDaemon(true);
    thread.start();
  }

  /** Watches {@code dir} (which must exist) until {@link #close()}. */
  public static TempWatch start(Path dir) throws IOException {
    return new TempWatch(dir);
  }

  private void run() {
    try {
      while (true) {
        WatchKey key = service.take();
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.context() instanceof Path name) {
            seen.add(name.toString());
            firstSeenMillis.putIfAbsent(
                name.toString(), (System.nanoTime() - startNanos) / 1_000_000L);
          }
        }
        if (!key.reset()) {
          return;
        }
      }
    } catch (InterruptedException | ClosedWatchServiceException e) {
      // closed
    }
  }

  /** Entries seen created at the top level plus the current tree, as relative paths. */
  public Set<String> created() {
    final Set<String> names = new TreeSet<>(seen);
    if (!Files.isDirectory(dir)) {
      return names;
    }
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
              add(path);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
              add(path);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) {
              // Deleted while the tree was walked: still an entry that existed.
              add(path);
              return FileVisitResult.CONTINUE;
            }

            private void add(Path path) {
              if (!path.equals(dir)) {
                names.add(dir.relativize(path).toString().replace(java.io.File.separatorChar, '/'));
              }
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  /** Top-level entries seen by the watch service and when (ms after the watch started). */
  public Map<String, Long> events() {
    return new TreeMap<>(firstSeenMillis);
  }

  /**
   * {@code names} with every run of digits replaced by {@code #}: temp names of one app differ
   * between runs only in ports, process ids and random numbers ({@code tomcat.54321.123...}).
   */
  public static Set<String> normalized(Set<String> names) {
    Set<String> out = new TreeSet<>();
    for (String name : names) {
      out.add(name.replaceAll("[0-9]+", "#"));
    }
    return out;
  }

  @Override
  public void close() {
    try {
      service.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    thread.interrupt();
  }
}
