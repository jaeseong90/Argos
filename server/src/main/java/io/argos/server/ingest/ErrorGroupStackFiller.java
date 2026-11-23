/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.proto.v1.DictionaryKind;
import io.argos.server.ingest.IngestStore.StackCandidate;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fills the representative stack of the error groups ({@code error_groups.sample_stack}, 12 §3.5
 * (4) "대표 스택은 ERROR step이 있는 첫 프로파일에서 1회 채운다", F-U07, WO-27).
 *
 * <p>Every {@value #PERIOD_S} s ({@code argos-error-stack}) one run takes at most {@value
 * #MAX_GROUPS_PER_RUN} groups whose stack is still null and that occurred within the last day,
 * latest first, and for each app of them
 *
 * <ol>
 *   <li>reads, as {@code argos_reader} ({@code sql/ingest/error_stack.sql}), the frame keys of the
 *       earliest ERROR step with frames among the captured profiles of the group's transactions of
 *       the last day ({@code xlog.error_group_id}, {@code profile_captured = 1}, {@code
 *       profile_step.type = 'ERROR'});
 *   <li>looks the frames up in the FRAME dictionary; while one text is missing (the agent sends it
 *       later, 11 §7) the group waits for a later run;
 *   <li>masks every frame once more with the server's default set ({@link ServerMasking}, SEC-36),
 *       cuts it to {@value #MAX_FRAME_CHARS} characters, joins the frames with newlines (top frame
 *       first, as the agent sent them) and sets the stack only while it is still null: a group's
 *       stack is written once.
 * </ol>
 *
 * A group without a stack yet is tried again after {@value #RETRY_MINUTES} minutes; at most {@value
 * #MAX_WAITING} such groups are remembered (bounded, the oldest forgotten first). A failed read or
 * write is counted in {@code argos_ingest_errors_total{stage="error_stack"}} and the run goes on
 * with the next app; the collected texts never reach a log line.
 */
@Component
public class ErrorGroupStackFiller implements DisposableBean {

  /** Run period. */
  static final long PERIOD_S = 60;

  /** Most groups per run. */
  static final int MAX_GROUPS_PER_RUN = 100;

  /** Groups read from PostgreSQL per run (candidates before the retry filter). */
  static final int CANDIDATES_PER_RUN = 1_000;

  /** How far back the transactions of a group are searched. */
  static final Duration WINDOW = Duration.ofDays(1);

  /** Wait before a group without a stack is tried again. */
  static final long RETRY_MINUTES = 10;

  /** Most groups remembered for the retry wait. */
  static final int MAX_WAITING = 10_000;

  /** Most characters of one frame (as {@code error_groups.top_frame}). */
  static final int MAX_FRAME_CHARS = 1_024;

  /** Most frames of a stack (an ERROR step holds at most 30, a STACK step 64). */
  static final int MAX_FRAMES = 64;

  /** Text of an overflowed frame key (16 §4.4). */
  static final String OVERFLOW_TEXT = DictionaryResolver.OVERFLOW_TEXT;

  private static final String TEMPLATE = "ingest/error_stack";
  private static final String FRAME_KIND = DictKinds.pgName(DictionaryKind.FRAME).orElseThrow();
  private static final Logger LOG = LoggerFactory.getLogger(ErrorGroupStackFiller.class);

  /** The ClickHouse side: frame keys of the groups of one app. */
  @FunctionalInterface
  interface FrameSource {
    /**
     * The frame keys of the representative ERROR step per group of {@code groups} in {@code [from,
     * to)}; groups without one are absent.
     */
    Map<Long, List<Long>> frames(int appId, Collection<Long> groups, Instant from, Instant to)
        throws Exception;
  }

  private record GroupKey(int appId, long groupId) {}

  /** Outcome of one run (tests). */
  record Run(int candidates, int filled, int waiting, int failures) {}

  private final IngestStore store;
  private final FrameSource frames;
  private final IngestMetrics metrics;
  private final Clock clock;
  private final Map<GroupKey, Instant> retryAt = new ConcurrentHashMap<>();
  private ScheduledExecutorService executor;

  @Autowired
  public ErrorGroupStackFiller(IngestStore store, QueryExecutor reader, IngestMetrics metrics) {
    this(store, clickHouse(reader), metrics, Clock.systemUTC());
  }

  ErrorGroupStackFiller(IngestStore store, FrameSource frames, IngestMetrics metrics, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.frames = Objects.requireNonNull(frames, "frames");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static FrameSource clickHouse(QueryExecutor reader) {
    Objects.requireNonNull(reader, "reader");
    return (appId, groups, from, to) -> {
      QueryResult result =
          reader.query(
              TEMPLATE,
              QueryParams.create()
                  .uint32("app", appId)
                  .uint64Array("groups", groups)
                  .dateTime64("from", from)
                  .dateTime64("to", to),
              QueryOptions.defaults());
      Map<Long, List<Long>> out = new HashMap<>();
      for (QueryRow row : result.rows()) {
        List<Long> keys = new ArrayList<>();
        for (Object f : row.getList("frames")) {
          keys.add(Long.parseUnsignedLong(String.valueOf(f)));
        }
        out.put(Long.parseUnsignedLong(row.getString("group_id")), keys);
      }
      return out;
    };
  }

  /** Starts the run thread (first run after one period). */
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-error-stack");
              t.setDaemon(true);
              return t;
            });
    var unused =
        executor.scheduleWithFixedDelay(this::runQuietly, PERIOD_S, PERIOD_S, TimeUnit.SECONDS);
  }

  private void runQuietly() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      metrics.error("error_stack");
      LOG.debug("Error stack run failed: {}", e.getClass().getName());
    }
  }

  /**
   * One run now (also called by the tests instead of waiting for the period).
   *
   * @return what the run did
   */
  public synchronized Run runOnce() {
    Instant now = clock.instant();
    Instant from = now.minus(WINDOW);
    List<StackCandidate> candidates;
    try {
      candidates = store.groupsWithoutStack(from, CANDIDATES_PER_RUN);
    } catch (Exception e) {
      metrics.error("error_stack");
      LOG.debug("Error groups without a stack could not be read: {}", e.getClass().getName());
      return new Run(0, 0, 0, 1);
    }
    retryAt.values().removeIf(at -> !at.isAfter(now));
    Map<Integer, List<Long>> byApp = new LinkedHashMap<>();
    int taken = 0;
    for (StackCandidate c : candidates) {
      if (taken >= MAX_GROUPS_PER_RUN) {
        break;
      }
      if (retryAt.containsKey(new GroupKey(c.appId(), c.groupId()))) {
        continue;
      }
      byApp.computeIfAbsent(c.appId(), k -> new ArrayList<>()).add(c.groupId());
      taken++;
    }
    int filled = 0;
    int waiting = 0;
    int failures = 0;
    Instant to = now.plus(Duration.ofMinutes(5));
    for (Map.Entry<Integer, List<Long>> app : byApp.entrySet()) {
      int appId = app.getKey();
      try {
        Map<Long, List<Long>> stacks = frames.frames(appId, app.getValue(), from, to);
        Set<Long> keys = new LinkedHashSet<>();
        for (List<Long> s : stacks.values()) {
          for (int i = 0; i < Math.min(s.size(), MAX_FRAMES); i++) {
            if (s.get(i) != DictionaryResolver.OVERFLOW_ID) {
              keys.add(s.get(i));
            }
          }
        }
        Map<Long, String> texts = store.dictionaryTexts(appId, FRAME_KIND, keys);
        for (long groupId : app.getValue()) {
          String stack = stack(stacks.get(groupId), texts);
          if (stack == null) {
            deferRetry(new GroupKey(appId, groupId), now);
            waiting++;
          } else if (store.fillSampleStack(appId, groupId, stack)) {
            filled++;
          }
        }
      } catch (Exception e) {
        failures++;
        metrics.error("error_stack");
        LOG.debug(
            "The representative stacks of app {} were not filled: {}",
            appId,
            e.getClass().getName());
      }
    }
    if (filled > 0) {
      LOG.debug("Filled the representative stack of {} error groups", filled);
    }
    return new Run(taken, filled, waiting, failures);
  }

  /**
   * The stack text of {@code keys}, or {@code null} when there are none or a text is missing yet.
   */
  static String stack(List<Long> keys, Map<Long, String> texts) {
    if (keys == null || keys.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < Math.min(keys.size(), MAX_FRAMES); i++) {
      long key = keys.get(i);
      String text = key == DictionaryResolver.OVERFLOW_ID ? OVERFLOW_TEXT : texts.get(key);
      if (text == null) {
        return null;
      }
      String frame = ServerMasking.mask(text.replace('\r', ' ').replace('\n', ' ')).strip();
      if (frame.length() > MAX_FRAME_CHARS) {
        frame = frame.substring(0, MAX_FRAME_CHARS);
      }
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(frame);
    }
    return out.toString();
  }

  private void deferRetry(GroupKey key, Instant now) {
    if (retryAt.size() >= MAX_WAITING && !retryAt.containsKey(key)) {
      GroupKey oldest = null;
      Instant oldestAt = null;
      for (Map.Entry<GroupKey, Instant> e : retryAt.entrySet()) {
        if (oldestAt == null || e.getValue().isBefore(oldestAt)) {
          oldest = e.getKey();
          oldestAt = e.getValue();
        }
      }
      if (oldest != null) {
        retryAt.remove(oldest);
      }
    }
    retryAt.put(key, now.plus(Duration.ofMinutes(RETRY_MINUTES)));
  }

  /** Groups currently waiting for a retry (tests). */
  int waitingGroups() {
    return retryAt.size();
  }

  /** Forgets every retry wait (tests: a later run tries every group again). */
  void clearWaits() {
    retryAt.clear();
  }

  @Override
  public synchronized void destroy() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
