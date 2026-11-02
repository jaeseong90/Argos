/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One {@code AgentHello} a {@link FakeCollector} received and how it answered.
 *
 * @param number session number (1, 2, ... in arrival order)
 * @param instanceId the instance id the collector assigned (0 when rejected)
 * @param accepted whether {@code ServerHello.accepted} was true
 * @param appName {@code AgentHello.app_name}
 * @param env {@code AgentHello.env}
 * @param appVersion {@code AgentHello.app_version}
 * @param agentVersion {@code AgentHello.agent_version}
 * @param openedAt when the hello arrived
 * @param hello the hello message as received
 */
public record SessionInfo(
    int number,
    long instanceId,
    boolean accepted,
    String appName,
    String env,
    String appVersion,
    String agentVersion,
    Instant openedAt,
    Message hello) {}
