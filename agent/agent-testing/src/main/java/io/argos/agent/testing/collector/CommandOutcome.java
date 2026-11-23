/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;

/**
 * The agent's answer to a {@link FakeCollector#command} ({@code CommandResult}, chunks joined, 11
 * §5, 10 §12.1).
 *
 * @param commandId the command id
 * @param status {@code OK}, {@code REJECTED}, {@code EXPIRED}, {@code FAILED}, {@code COOLDOWN}
 * @param payload the joined payload of every chunk
 * @param contentType the payload's content type
 * @param error the error text (empty when none)
 * @param truncated whether the agent cut the payload
 * @param chunks chunks received
 * @param durationMs {@code duration_ms} of the last chunk (the agent's own run time)
 * @param roundTripMs measured by the collector: from sending the {@code Command} to receiving the
 *     last chunk
 */
public record CommandOutcome(
    String commandId,
    String status,
    ByteString payload,
    String contentType,
    String error,
    boolean truncated,
    int chunks,
    long durationMs,
    long roundTripMs) {

  /** The payload as UTF-8 text. */
  public String payloadText() {
    return payload.toString(StandardCharsets.UTF_8);
  }

  /** Whether the agent answered {@code OK}. */
  public boolean ok() {
    return "OK".equals(status);
  }
}
