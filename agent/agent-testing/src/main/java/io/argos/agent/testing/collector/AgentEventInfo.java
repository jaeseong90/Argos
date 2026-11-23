/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.time.Instant;

/**
 * One {@code AgentEvent} the agent sent on its session stream (11 §4.2: module auto-disable, kill
 * switch on/off, config file reloaded, ...), see {@link FakeCollector#agentEvents()}.
 *
 * @param type the {@code AgentEventType} name ({@code KILL_SWITCH_ON}, {@code
 *     CONFIG_FILE_RELOADED}, ...; {@code UNRECOGNIZED} for a number the collector does not know)
 * @param module {@code module} (empty when none)
 * @param detail {@code detail} (empty when none)
 * @param atMs {@code at_ms}: the agent's clock
 * @param sessionId the session the event came on (null when the stream had no accepted session)
 * @param instanceId the instance of that session (0 when none)
 * @param receivedAt when the collector received it
 */
public record AgentEventInfo(
    String type,
    String module,
    String detail,
    long atMs,
    String sessionId,
    long instanceId,
    Instant receivedAt) {}
