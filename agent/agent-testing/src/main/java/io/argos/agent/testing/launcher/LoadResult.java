/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.util.Map;

/**
 * Outcome of {@link RunningApp#load}: requests scheduled, answered (by HTTP status) and failed.
 *
 * @param scheduled requests the load sent
 * @param statuses answered requests by HTTP status
 * @param failures requests without an answer (I/O error, timeout)
 * @param seconds wall time of the load
 */
public record LoadResult(
    long scheduled, Map<Integer, Long> statuses, long failures, double seconds) {
  public LoadResult {
    statuses = Map.copyOf(statuses);
  }

  /** Answered requests. */
  public long answered() {
    return statuses.values().stream().mapToLong(Long::longValue).sum();
  }

  /** Answered requests with a 2xx status. */
  public long ok() {
    return statuses.entrySet().stream()
        .filter(e -> e.getKey() >= 200 && e.getKey() < 300)
        .mapToLong(Map.Entry::getValue)
        .sum();
  }
}
