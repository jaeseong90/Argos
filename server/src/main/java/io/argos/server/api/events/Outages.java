/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import io.argos.server.storage.postgres.catalog.CatalogRepository.StateEventRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collection outages from state events (D-18, C-5; 14 §4.4 {@code outages}, §4.15): the time spans
 * in which data could not be collected, so that the UI shades them and does not read them as 0.
 *
 * <p>The input contract (the writers are WO-22 for instance states and the server's start and stop
 * events; ADR-0010 C-160 leaves them unwritten before WO-22):
 *
 * <ul>
 *   <li>an instance state transition is an {@code events} row {@code type = 'state'} with its
 *       {@code instance_id} and {@code detail = {"from": ..., "to": "UP|STALE|DOWN|WARN", "reason":
 *       ...}}; a transition to {@code STALE} or {@code DOWN} starts an outage of that instance, a
 *       transition to {@code UP} or {@code WARN} ends it; its reason is {@code detail.reason} when
 *       it is one of {@value #SESSION_LOST}, {@value #SERVER_DOWN}, {@value #QUEUE_OVERFLOW},
 *       otherwise {@value #SESSION_LOST};
 *   <li>a server {@code stop} event ({@code app_id} and {@code instance_id} null) starts a {@value
 *       #SERVER_DOWN} outage of every application, the next {@code start} ends it.
 * </ul>
 *
 * The last event before the range tells whether an outage was already going on at its start.
 * Outages are clipped to the range; one that has not ended by the end of the range ends there, or
 * has no end ({@code to} null) when the range reaches the present (it is still going on).
 */
public final class Outages {

  /** The agent session was lost (no heartbeat, stream ended). */
  public static final String SESSION_LOST = "session_lost";

  /** The Argos server was down. */
  public static final String SERVER_DOWN = "server_down";

  /** The agent dropped data (queue overflow). */
  public static final String QUEUE_OVERFLOW = "queue_overflow";

  private static final Set<String> REASONS = Set.of(SESSION_LOST, SERVER_DOWN, QUEUE_OVERFLOW);
  private static final Set<String> DOWN_STATES = Set.of("STALE", "DOWN");
  private static final Set<String> UP_STATES = Set.of("UP", "WARN");

  /** Sort key of server events (no instance). */
  private static final int SERVER = -1;

  private Outages() {}

  /**
   * One outage.
   *
   * @param from start (clipped to the range)
   * @param to end (clipped to the range), null while it lasts
   * @param reason {@code server_down}, {@code session_lost} or {@code queue_overflow}
   * @param instanceId the instance, null for a server outage
   */
  public record Outage(Instant from, Instant to, String reason, Integer instanceId) {}

  /**
   * The outages of {@code [from, to)}.
   *
   * @param events the state events of the range and the last one before it (any order)
   * @param instances the instances to report (every instance when empty); server outages always
   * @param now the present: an outage still going on at the end of a range that reaches it has no
   *     end
   */
  public static List<Outage> derive(
      List<StateEventRecord> events,
      Instant from,
      Instant to,
      Set<Integer> instances,
      Instant now) {
    Map<Integer, List<StateEventRecord>> byKey = new TreeMap<>();
    for (StateEventRecord e : events) {
      int key = e.instanceId() == null ? SERVER : e.instanceId();
      if (key != SERVER && !instances.isEmpty() && !instances.contains(key)) {
        continue;
      }
      if (key == SERVER && !(e.type().equals("start") || e.type().equals("stop"))) {
        continue;
      }
      byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
    }
    boolean live = !to.isBefore(now);
    List<Outage> out = new ArrayList<>();
    for (Map.Entry<Integer, List<StateEventRecord>> entry : byKey.entrySet()) {
      int key = entry.getKey();
      List<StateEventRecord> list = new ArrayList<>(entry.getValue());
      list.sort(Comparator.comparing(StateEventRecord::ts));
      Instant start = null;
      String reason = null;
      for (StateEventRecord e : list) {
        if (starts(e, key)) {
          if (start == null) {
            start = e.ts();
            reason = key == SERVER ? SERVER_DOWN : reason(e.reason());
          }
        } else if (ends(e, key) && start != null) {
          add(out, start, e.ts(), reason, key, from, to);
          start = null;
        }
      }
      if (start != null) {
        Instant clippedFrom = start.isBefore(from) ? from : start;
        if (clippedFrom.isBefore(to)) {
          out.add(new Outage(clippedFrom, live ? null : to, reason, key == SERVER ? null : key));
        }
      }
    }
    out.sort(
        Comparator.comparing(Outage::from)
            .thenComparingInt(o -> o.instanceId() == null ? SERVER : o.instanceId()));
    return out;
  }

  private static void add(
      List<Outage> out,
      Instant start,
      Instant end,
      String reason,
      int key,
      Instant from,
      Instant to) {
    Instant a = start.isBefore(from) ? from : start;
    Instant b = end.isAfter(to) ? to : end;
    if (a.isBefore(b)) {
      out.add(new Outage(a, b, reason, key == SERVER ? null : key));
    }
  }

  private static boolean starts(StateEventRecord e, int key) {
    if (key == SERVER) {
      return e.type().equals("stop");
    }
    return e.toState() != null && DOWN_STATES.contains(e.toState().toUpperCase(Locale.ROOT));
  }

  private static boolean ends(StateEventRecord e, int key) {
    if (key == SERVER) {
      return e.type().equals("start");
    }
    return e.toState() != null && UP_STATES.contains(e.toState().toUpperCase(Locale.ROOT));
  }

  private static String reason(String stored) {
    String r = Objects.requireNonNullElse(stored, "").toLowerCase(Locale.ROOT);
    return REASONS.contains(r) ? r : SESSION_LOST;
  }
}
