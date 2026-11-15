/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One {@code Heartbeat} a {@link FakeCollector} received and the clock round it completed (11 §9,
 * {@link ClockEstimator}).
 *
 * @param instanceId the session's instance id (0 before an accepted hello)
 * @param sessionId the session id ({@code null} before an accepted hello)
 * @param receivedAt arrival time (collector's real clock)
 * @param t1 {@code Heartbeat.t1_ms} (agent clock)
 * @param prevT1 {@code Heartbeat.prev_t1_ms}
 * @param prevT4 {@code Heartbeat.prev_t4_ms}
 * @param t2 the receive time the collector reported ({@code HeartbeatAck.t2_ms}, with {@link
 *     FaultInjector#clockOffset}); 0 when the heartbeat was not answered
 * @param t3 the send time the collector reported ({@code HeartbeatAck.t3_ms}); 0 when not answered
 * @param round the round {@code prevT1}/{@code prevT4} completed, or null
 * @param estimateMs the instance's offset estimate after this heartbeat (server minus agent), or
 *     null when there is none
 * @param answered a {@code HeartbeatAck} was sent
 * @param droppedTotal {@code Heartbeat.dropped_total}
 * @param heartbeat the message as received
 */
public record HeartbeatRecord(
    long instanceId,
    String sessionId,
    Instant receivedAt,
    long t1,
    long prevT1,
    long prevT4,
    long t2,
    long t3,
    ClockEstimator.Round round,
    Long estimateMs,
    boolean answered,
    long droppedTotal,
    Message heartbeat) {}
