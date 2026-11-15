/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One {@code AgentBye} a {@link FakeCollector} received (11 §5, §6 "종료": the agent sends it, then
 * flushes its queues for at most 1 s; F-A26, T-AG-067). {@link FakeCollector#batchesAfter} and
 * {@link FakeCollector#flushAfterBye} relate it to the batches that arrived after it.
 *
 * @param instanceId the session's instance id (0 before an accepted hello)
 * @param sessionId the session id ({@code null} before an accepted hello)
 * @param reason {@code AgentBye.reason} name ({@code SHUTDOWN}, {@code AGENT_DISABLED}, ...)
 * @param lastTxSeq {@code AgentBye.last_tx_seq}
 * @param lastProfileSeq {@code AgentBye.last_profile_seq}
 * @param lastMetricSeq {@code AgentBye.last_metric_seq}
 * @param lastStatSeq {@code AgentBye.last_stat_seq}
 * @param receivedAt arrival time
 * @param bye the message as received
 */
public record ByeInfo(
    long instanceId,
    String sessionId,
    String reason,
    long lastTxSeq,
    long lastProfileSeq,
    long lastMetricSeq,
    long lastStatSeq,
    Instant receivedAt,
    Message bye) {}
