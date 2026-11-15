/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
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
   * The longest AF_UNIX socket path, in UTF-8 bytes, that the JDK binds on Windows ({@code
   * sun_path} of 108 bytes less 2; JDK 17, 21 and 25 throw "Unix domain path too long" above it).
   */
  static final int WINDOWS_AF_UNIX_MAX_PATH = 106;

  /** The separator and {@code socket_} before the random number of a selector's socket file. */
  private static final int SOCKET_PREFIX = 8;

  /**
   * Temp path lengths (bytes) up to this one fit every selector socket name: the random number has
   * at most 10 digits ({@code nextInt(Integer.MAX_VALUE)}).
   */
  static final int SOCKET_NAMES_ALWAYS_FIT = WINDOWS_AF_UNIX_MAX_PATH - SOCKET_PREFIX - 10;

  /** Temp path lengths (bytes) from this one on fit no selector socket name (1 digit or more). */
  static final int SOCKET_NAMES_NEVER_FIT = WINDOWS_AF_UNIX_MAX_PATH - SOCKET_PREFIX;

  /**
   * The work directory {@code parent/name} of a run whose app gets {@code <workDir>/tmp} as its
   * temp directory ({@link AppLauncher}: {@code java.io.tmpdir}, {@code TMP}, {@code TEMP}, {@code
   * TMPDIR}), so that whether the JDK's NIO selectors create files there does not depend on chance
   * (T-BLD-006 compares the temp entries of two runs).
   *
   * <p>On Windows from JDK 16 on, every {@code Selector} binds its wakeup pipe to an AF_UNIX socket
   * file {@code %TEMP%\socket_<n>} (deleted at once; {@code n} a random int of 1 to 10 digits) when
   * that path fits {@value #WINDOWS_AF_UNIX_MAX_PATH} bytes, and uses a loopback TCP pipe when it
   * does not. With a temp path of {@value #SOCKET_NAMES_ALWAYS_FIT} bytes or less every name fits;
   * from {@value #SOCKET_NAMES_NEVER_FIT} bytes on none does. In between only the names with fewer
   * digits fit, so one run of an app creates {@code socket_<n>} files and the next run of the same
   * app does not. Such a directory name gets a suffix ({@code -x...}) that makes the temp path
   * {@value #SOCKET_NAMES_NEVER_FIT} bytes long; names of the same length get the same suffix.
   * Other paths and other systems keep {@code parent/name}.
   */
  public static Path runDirectory(Path parent, String name) {
    return runDirectory(
        parent,
        name,
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
  }

  static Path runDirectory(Path parent, String name, boolean windows) {
    Path base = parent.toAbsolutePath();
    Path dir = base.resolve(name);
    if (!windows) {
      return dir;
    }
    int pad = socketPadding(dir.resolve("tmp").toString().getBytes(StandardCharsets.UTF_8).length);
    return pad == 0 ? dir : base.resolve(name + "-" + "x".repeat(pad - 1));
  }

  /**
   * Bytes to add to a Windows temp path of {@code tempPathBytes} so that the JDK's selector socket
   * names either always or never fit ({@link #runDirectory}): 0 outside the range where it depends
   * on the random number, else up to {@value #SOCKET_NAMES_NEVER_FIT}.
   */
  static int socketPadding(int tempPathBytes) {
    if (tempPathBytes <= SOCKET_NAMES_ALWAYS_FIT || tempPathBytes >= SOCKET_NAMES_NEVER_FIT) {
      return 0;
    }
    return SOCKET_NAMES_NEVER_FIT - tempPathBytes;
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
