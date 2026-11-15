/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One batch a {@link FakeCollector} received (18 §5.3: instance, seq, receive time, body), recorded
 * before any injected fault or protocol check decides the answer: the FakeCollector is the "sent
 * count" reference (DSN-18-04) and does no limit or quota checks. Retries, duplicates and refused
 * calls are recorded too; {@link #outcome()} tells them apart.
 *
 * @param kind which unary call carried it
 * @param instanceId {@code BatchHeader.instance_id}
 * @param agentEpoch {@code BatchHeader.agent_epoch}
 * @param seq {@code BatchHeader.seq}
 * @param receivedAt when the call arrived
 * @param body the protobuf message as received
 * @param bytes serialized size
 * @param outcome {@link #ACKED}, {@link #DUPLICATE}, {@link #NOT_WRITTEN}, {@link #HUNG}, {@link
 *     #REJECTED_AUTH}, {@link #SESSION_REQUIRED} or {@code FAILED <code>}
 * @param duplicate answered {@code ACK_DUPLICATE} (11 §6: the key was already written)
 * @param droppedItems {@code Ack.dropped_items} of the answer (quota or WAL full), else 0
 * @param sessionId the {@code argos-session-id} metadata (null when absent)
 * @param token the {@code authorization} token (masked in {@code toString}; null when absent)
 * @param sentAtMs {@code BatchHeader.sent_at_ms}
 */
public record ReceivedBatch(
    BatchKind kind,
    long instanceId,
    long agentEpoch,
    long seq,
    Instant receivedAt,
    Message body,
    int bytes,
    String outcome,
    boolean duplicate,
    int droppedItems,
    String sessionId,
    ReceivedToken token,
    long sentAtMs) {

  /** {@code ACK_OK} or {@code ACK_PARTIAL_DROP} (see {@link #droppedItems()}). */
  public static final String ACKED = "ACKED";

  /** {@code ACK_DUPLICATE} (11 §6): acknowledged, not written again. */
  public static final String DUPLICATE = "DUPLICATE";

  /**
   * {@code ACK_OK} with every item dropped and nothing written ({@link FaultInjector#walFull()}, 11
   * §6 "서버 드롭"): not a transaction, and a resend of the same seq is new.
   */
  public static final String NOT_WRITTEN = "NOT_WRITTEN";

  /** Never answered ({@link FaultInjector#hang()}). */
  public static final String HUNG = "HUNG";

  /** {@code UNAUTHENTICATED}: missing, malformed or refused token. */
  public static final String REJECTED_AUTH = "REJECTED_AUTH";

  /** {@code FAILED_PRECONDITION} with detail {@code SESSION_REQUIRED} (11 §3). */
  public static final String SESSION_REQUIRED = "SESSION_REQUIRED";

  /** The WO-05 shape (no session metadata). */
  public ReceivedBatch(
      BatchKind kind,
      long instanceId,
      long agentEpoch,
      long seq,
      Instant receivedAt,
      Message body,
      int bytes,
      String outcome) {
    this(
        kind,
        instanceId,
        agentEpoch,
        seq,
        receivedAt,
        body,
        bytes,
        outcome,
        DUPLICATE.equals(outcome),
        0,
        null,
        null,
        0);
  }

  /** Answered {@code ACK_OK} or {@code ACK_PARTIAL_DROP}: the batch was written once. */
  public boolean acked() {
    return ACKED.equals(outcome);
  }
}
