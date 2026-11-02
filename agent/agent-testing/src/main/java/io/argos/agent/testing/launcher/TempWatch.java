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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Records what happens in an app's temp directory (T-BLD-006, "기동 중 /tmp 감시"): a {@link
 * WatchService} thread sees every entry created, modified or deleted directly in the directory,
 * also entries deleted again at once; {@link #created()} adds the current tree (relative paths, '/'
 * separated). Started before the process is spawned, closed with the app.
 */
public final class TempWatch implements AutoCloseable {
  private final Path dir;
  private final WatchService service;
  private final Set<String> seen = new ConcurrentSkipListSet<>();
  private final Map<String, Long> firstSeenMillis = new ConcurrentHashMap<>();
  private final List<String> allEvents = new CopyOnWriteArrayList<>();
  private final long startNanos = System.nanoTime();
  private final Thread thread;

  private TempWatch(Path dir) throws IOException {
    this.dir = dir;
    this.service = FileSystems.getDefault().newWatchService();
    dir.register(
        service,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE);
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
          if (event.kind().equals(StandardWatchEventKinds.OVERFLOW)) {
            allEvents.add("OVERFLOW");
            continue;
          }
          if (event.context() instanceof Path name) {
            allEvents.add(event.kind().name() + " " + name);
            if (event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE)) {
              seen.add(name.toString());
              firstSeenMillis.putIfAbsent(
                  name.toString(), (System.nanoTime() - startNanos) / 1_000_000L);
            }
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

  /**
   * Every watch event so far ({@code ENTRY_CREATE x}, {@code ENTRY_MODIFY x}, {@code ENTRY_DELETE
   * x}, {@code OVERFLOW}) followed by one {@code LEFT x} line per entry still in the directory: the
   * strict "nothing at all happened" view of the WO-02 attach tests.
   */
  public List<String> eventsAndLeftovers() {
    List<String> events = new ArrayList<>(allEvents);
    if (Files.isDirectory(dir)) {
      try (Stream<Path> left = Files.list(dir)) {
        left.forEach(path -> events.add("LEFT " + path.getFileName()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return events;
  }

  /** Top-level entries seen created and when (ms after the watch started). */
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
