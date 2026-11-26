/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * {@code jdk.VirtualThreadPinned} events of a JFR recording of the server (12 open item 4): how
 * often a virtual thread blocked while pinned to its carrier, the longest pin and the top frames
 * that pinned most often. The recording settings are written by {@link LoadServerJvm} (event
 * threshold 20 ms, the JDK default).
 */
public final class JfrPinning {

  /** JFR event name. */
  public static final String EVENT = "jdk.VirtualThreadPinned";

  /**
   * The summary.
   *
   * @param events pinned events recorded
   * @param longest the longest pin
   * @param total the sum of the pins
   * @param topFrames the top application frame of the pins, most frequent first (at most 10)
   */
  public record Summary(
      long events, Duration longest, Duration total, Map<String, Long> topFrames) {

    /** Copies the map. */
    public Summary {
      topFrames = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(topFrames));
    }
  }

  private JfrPinning() {}

  /** Reads {@code recording} and summarizes its {@value #EVENT} events. */
  public static Summary summarize(Path recording) throws IOException {
    List<RecordedEvent> pinned =
        RecordingFile.readAllEvents(recording).stream()
            .filter(e -> e.getEventType().getName().equals(EVENT))
            .toList();
    Duration longest = Duration.ZERO;
    Duration total = Duration.ZERO;
    Map<String, Long> frames = new LinkedHashMap<>();
    for (RecordedEvent e : pinned) {
      Duration d = e.getDuration();
      total = total.plus(d);
      if (d.compareTo(longest) > 0) {
        longest = d;
      }
      frames.merge(topFrame(e), 1L, Long::sum);
    }
    Map<String, Long> top =
        frames.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    return new Summary(pinned.size(), longest, total, top);
  }

  private static String topFrame(RecordedEvent e) {
    if (e.getStackTrace() == null) {
      return "(no stack)";
    }
    for (RecordedFrame f : e.getStackTrace().getFrames()) {
      if (f.getMethod() == null) {
        continue;
      }
      String type = f.getMethod().getType().getName();
      if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) {
        return type + "#" + f.getMethod().getName();
      }
    }
    return "(JDK frames only)";
  }
}
