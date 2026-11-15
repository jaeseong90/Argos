/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Self metrics of the transport (10 §11 "에이전트 자기", 18 §19), kept apart from {@code AgentCounters}
 * (whose label sets are bounded at 110 entries) and merged into the self-diagnostics snapshot.
 * Every name and label set is fixed ({@link #snapshot()}):
 *
 * <ul>
 *   <li>{@code argos_agent_batches_sent_total{kind}}: batches the server acknowledged (any {@code
 *       AckStatus}, duplicates included);
 *   <li>{@code argos_agent_batch_retries_total{kind}}: resends of the same batch and {@code seq}
 *       (11 §6: at most 3 per batch, and the resend after {@code SESSION_REQUIRED});
 *   <li>{@code argos_agent_batches_dropped_total{kind}}: batches given up (retries exhausted,
 *       {@code INVALID_ARGUMENT}, {@code PERMISSION_DENIED}, {@code UNAUTHENTICATED}, an oversized
 *       batch that could not be split, shutdown); their items also count in {@code
 *       argos_agent_dropped_total{queue}} (D-06), so the heartbeat's {@code dropped_total} includes
 *       them;
 *   <li>{@code argos_agent_server_dropped_items_total{kind}}: items the server reported as dropped
 *       ({@code Ack.dropped_items}: quota {@code ACK_PARTIAL_DROP}, full WAL), never resent;
 *   <li>{@code argos_agent_session_established_total}, {@code argos_agent_session_rejected_total}
 *       ({@code ServerHello.accepted = false} and {@code UNAUTHENTICATED}), {@code
 *       argos_agent_session_messages_dropped_total} (session stream messages dropped because the
 *       stream's bounded pending list was full, T-AG-071), {@code
 *       argos_agent_dictionary_entries_sent_total};
 *   <li>gauges {@code argos_agent_transport_up} (1 while a session is established) and {@code
 *       argos_agent_clock_offset_ms} (the server's {@code clock_offset_ms} from the last {@code
 *       HeartbeatAck}, recorded as sent: server minus agent (ADR-0010 C-139), absent until known,
 *       11 §9).
 * </ul>
 *
 * Lock-free ({@link LongAdder}); updated from {@code argos-sender}, {@code argos-io} and the
 * shutdown hook.
 */
public final class TransportCounters {
  static final String BATCHES_SENT = "argos_agent_batches_sent_total";
  static final String BATCH_RETRIES = "argos_agent_batch_retries_total";
  static final String BATCHES_DROPPED = "argos_agent_batches_dropped_total";
  static final String SERVER_DROPPED_ITEMS = "argos_agent_server_dropped_items_total";
  static final String SESSION_ESTABLISHED = "argos_agent_session_established_total";
  static final String SESSION_REJECTED = "argos_agent_session_rejected_total";
  static final String SESSION_MESSAGES_DROPPED = "argos_agent_session_messages_dropped_total";
  static final String DICTIONARY_ENTRIES_SENT = "argos_agent_dictionary_entries_sent_total";
  static final String TRANSPORT_UP = "argos_agent_transport_up";
  static final String CLOCK_OFFSET = "argos_agent_clock_offset_ms";

  private static final int KINDS = BatchKind.COUNT;

  private final LongAdder[] sent = adders(KINDS);
  private final LongAdder[] retries = adders(KINDS);
  private final LongAdder[] dropped = adders(KINDS);
  private final LongAdder[] serverDropped = adders(KINDS);
  private final LongAdder established = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final LongAdder sessionMessagesDropped = new LongAdder();
  private final LongAdder dictionaryEntriesSent = new LongAdder();
  private volatile boolean up;
  private volatile boolean offsetKnown;
  private volatile long offsetMillis;

  private static LongAdder[] adders(int count) {
    LongAdder[] adders = new LongAdder[count];
    for (int i = 0; i < count; i++) {
      adders[i] = new LongAdder();
    }
    return adders;
  }

  void incSent(BatchKind kind) {
    sent[kind.index()].increment();
  }

  void incRetry(BatchKind kind) {
    retries[kind.index()].increment();
  }

  void incDropped(BatchKind kind) {
    dropped[kind.index()].increment();
  }

  void addServerDropped(BatchKind kind, long items) {
    if (items > 0) {
      serverDropped[kind.index()].add(items);
    }
  }

  void incEstablished() {
    established.increment();
  }

  void incRejected() {
    rejected.increment();
  }

  void incSessionMessagesDropped() {
    sessionMessagesDropped.increment();
  }

  void addDictionaryEntriesSent(long entries) {
    dictionaryEntriesSent.add(entries);
  }

  void up(boolean value) {
    up = value;
  }

  void clockOffset(long millis) {
    offsetMillis = millis;
    offsetKnown = true;
  }

  public long sent(BatchKind kind) {
    return sent[kind.index()].sum();
  }

  public long retries(BatchKind kind) {
    return retries[kind.index()].sum();
  }

  public long dropped(BatchKind kind) {
    return dropped[kind.index()].sum();
  }

  public long serverDropped(BatchKind kind) {
    return serverDropped[kind.index()].sum();
  }

  public long established() {
    return established.sum();
  }

  public long rejected() {
    return rejected.sum();
  }

  public long sessionMessagesDropped() {
    return sessionMessagesDropped.sum();
  }

  public boolean up() {
    return up;
  }

  /**
   * Every metric with its OpenMetrics name and labels, in a fixed order (at most 26 entries); the
   * clock offset only once it is known (missing is not zero, C-4).
   */
  public Map<String, Long> snapshot() {
    Map<String, Long> out = new LinkedHashMap<String, Long>();
    for (BatchKind kind : BatchKind.values()) {
      out.put(labelled(BATCHES_SENT, kind), sent(kind));
    }
    for (BatchKind kind : BatchKind.values()) {
      out.put(labelled(BATCH_RETRIES, kind), retries(kind));
    }
    for (BatchKind kind : BatchKind.values()) {
      out.put(labelled(BATCHES_DROPPED, kind), dropped(kind));
    }
    for (BatchKind kind : BatchKind.values()) {
      out.put(labelled(SERVER_DROPPED_ITEMS, kind), serverDropped(kind));
    }
    out.put(SESSION_ESTABLISHED, established());
    out.put(SESSION_REJECTED, rejected());
    out.put(SESSION_MESSAGES_DROPPED, sessionMessagesDropped());
    out.put(DICTIONARY_ENTRIES_SENT, dictionaryEntriesSent.sum());
    out.put(TRANSPORT_UP, up ? 1L : 0L);
    if (offsetKnown) {
      out.put(CLOCK_OFFSET, offsetMillis);
    }
    return Collections.unmodifiableMap(out);
  }

  private static String labelled(String metric, BatchKind kind) {
    return metric + "{kind=\"" + kind.label() + "\"}";
  }
}
