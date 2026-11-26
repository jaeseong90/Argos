/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent's reconnect attempts while the collector is away (T-CH-001 "재접속 백오프 ≤ 30초"), seen from
 * toxiproxy: every attempt opens one TCP connection to the proxy (agent-testing
 * ConnectionTransportTest: one TCP connect per back-off step), which toxiproxy logs as {@code
 * "Accepted client"} with the Docker log time. Connections that open within {@link #CLUSTER} of
 * each other are one attempt (the gRPC channel may open more than one socket per attempt).
 *
 * <p>The agent's back-off (agent-core {@code Backoff}, 10 §10.2) doubles from 1 s to a cap of 30 s
 * with ±20 % jitter, so the longest gap the design allows is {@link #LIMIT} = 36 s; after a
 * graceful server stop the first attempt waits {@code retry_after_s = 30} (12 §3.9). The report
 * states the target as "≤ 30 s back-off cap (±20 % jitter)".
 *
 * @param attempts attempt times, oldest first
 */
public record ReconnectGaps(List<Instant> attempts) {

  /** Connections closer than this are one attempt. */
  public static final Duration CLUSTER = Duration.ofMillis(500);

  /** The back-off cap with its jitter: 30 s × 1.2. */
  public static final Duration LIMIT = Duration.ofSeconds(36);

  static final Pattern ACCEPTED =
      Pattern.compile("^(\\S+)\\s+\\{.*\"message\":\"Accepted client\".*$");

  /** Copies. */
  public ReconnectGaps {
    attempts = List.copyOf(attempts);
  }

  /**
   * The attempts in [from, to] of toxiproxy's log with Docker timestamps ({@code docker logs
   * --timestamps}).
   */
  public static ReconnectGaps fromToxiproxyLog(List<String> lines, Instant from, Instant to) {
    List<Instant> accepted = new ArrayList<>();
    for (String line : lines) {
      Matcher m = ACCEPTED.matcher(line.strip());
      if (!m.matches()) {
        continue;
      }
      Instant t;
      try {
        t = Instant.parse(m.group(1));
      } catch (java.time.DateTimeException e) {
        continue;
      }
      if (!t.isBefore(from) && !t.isAfter(to)) {
        accepted.add(t);
      }
    }
    accepted.sort(null);
    List<Instant> attempts = new ArrayList<>();
    for (Instant t : accepted) {
      if (attempts.isEmpty()
          || Duration.between(attempts.get(attempts.size() - 1), t).compareTo(CLUSTER) > 0) {
        attempts.add(t);
      }
    }
    return new ReconnectGaps(attempts);
  }

  /** The gaps between consecutive attempts. */
  public List<Duration> gaps() {
    List<Duration> gaps = new ArrayList<>();
    for (int i = 1; i < attempts.size(); i++) {
      gaps.add(Duration.between(attempts.get(i - 1), attempts.get(i)));
    }
    return gaps;
  }

  /** The longest gap, empty with fewer than two attempts. */
  public Optional<Duration> longest() {
    return gaps().stream().max(Duration::compareTo);
  }

  /** Whether at least two attempts were seen and no gap exceeds {@link #LIMIT}. */
  public boolean withinLimit() {
    return longest().map(g -> g.compareTo(LIMIT) <= 0).orElse(false);
  }
}
