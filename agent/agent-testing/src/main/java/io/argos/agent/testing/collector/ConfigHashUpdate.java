/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.time.Instant;

/**
 * One {@code InstanceUpdate.config_hash} the agent sent (11 §4.2: the effective configuration hash
 * after a change), see {@link FakeCollector#configHashUpdates()}.
 *
 * @param hash the {@code config_hash} value
 * @param sessionId the session it came on (null when the stream had no accepted session)
 * @param instanceId the instance of that session (0 when none)
 * @param receivedAt when the collector received it
 */
public record ConfigHashUpdate(
    String hash, String sessionId, long instanceId, Instant receivedAt) {}
