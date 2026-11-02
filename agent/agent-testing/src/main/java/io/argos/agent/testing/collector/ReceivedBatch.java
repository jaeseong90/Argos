/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One batch a {@link FakeCollector} received (18 §5.3: instance, seq, receive time, body), recorded
 * before any injected fault decides the answer: the FakeCollector is the "sent count" reference
 * (DSN-18-04) and does no limit or quota checks.
 *
 * @param kind which unary call carried it
 * @param instanceId {@code BatchHeader.instance_id}
 * @param agentEpoch {@code BatchHeader.agent_epoch}
 * @param seq {@code BatchHeader.seq}
 * @param receivedAt when the call arrived
 * @param body the protobuf message as received
 * @param bytes serialized size
 * @param outcome {@code ACKED}, {@code FAILED <code>}, {@code HUNG} or {@code REJECTED_AUTH}
 */
public record ReceivedBatch(
    BatchKind kind,
    long instanceId,
    long agentEpoch,
    long seq,
    Instant receivedAt,
    Message body,
    int bytes,
    String outcome) {

  public static final String ACKED = "ACKED";
  public static final String HUNG = "HUNG";
  public static final String REJECTED_AUTH = "REJECTED_AUTH";

  public boolean acked() {
    return ACKED.equals(outcome);
  }
}
