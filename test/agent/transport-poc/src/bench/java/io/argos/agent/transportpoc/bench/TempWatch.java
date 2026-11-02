/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Records every entry created in a run's temporary directory while the JVM runs, including files
 * deleted right away (T-BLD-006). Example: on Windows, JDK 16+ NIO selectors create their wakeup
 * pipe as an AF_UNIX socket file {@code socket_<n>} in the temporary directory when its path is
 * short enough, and delete it at once.
 */
final class TempWatch implements AutoCloseable {
  private final WatchService service;
  private final Thread thread;
  private final List<String> created = new ArrayList<>();
  private volatile boolean running = true;

  private TempWatch(Path dir) throws IOException {
    service = FileSystems.getDefault().newWatchService();
    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE);
    thread = new Thread(this::loop, "temp-watch");
    thread.setDaemon(true);
    thread.start();
  }

  static TempWatch start(Path dir) throws IOException {
    return new TempWatch(dir);
  }

  private void loop() {
    while (running) {
      WatchKey key;
      try {
        key = service.poll(100, TimeUnit.MILLISECONDS);
      } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
        return;
      }
      if (key == null) {
        continue;
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        Object context = event.context();
        synchronized (created) {
          created.add(context == null ? "(overflow)" : context.toString());
        }
      }
      key.reset();
    }
  }

  /** Names created so far, digits replaced by '#' so that runs compare. */
  List<String> created() {
    synchronized (created) {
      return created.stream().map(n -> n.replaceAll("[0-9]+", "#")).toList();
    }
  }

  @Override
  public void close() throws IOException {
    if (!running) {
      return;
    }
    try {
      // Let the watcher drain events of the last moments of the run.
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    running = false;
    service.close();
  }
}
