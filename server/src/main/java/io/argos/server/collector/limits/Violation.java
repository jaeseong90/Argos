/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import io.grpc.Status;
import java.util.Objects;

/**
 * Why a received message is refused as a whole (11 §4.4, 12 §3.3, SEC-11; ADR-0010 C-130 (2): a
 * violation rejects the batch, nothing is cut). The {@link #reason()} is the label of {@code
 * argos_collector_rejected_total{reason}} and the value of the {@code argos-reject-reason} trailer;
 * the {@link #description()} is an English text without collected data.
 *
 * @param code {@code RESOURCE_EXHAUSTED} for a cap, {@code INVALID_ARGUMENT} for a format error
 * @param reason one of the {@code *} constants of this class
 * @param description the status description sent to the agent
 */
public record Violation(Status.Code code, String reason, String description) {

  /** Decompressed message above {@code max-message-bytes} (12 §3.3 class 1). */
  public static final String MESSAGE_SIZE = "message_size";

  /** Items of a batch above its cap (12 §3.3 class 2). */
  public static final String BATCH_ITEMS = "batch_items";

  /** A string above its byte cap (12 §3.3 class 3). */
  public static final String STRING_LENGTH = "string_length";

  /** A repeated field above its cap (12 §3.3 class 4). */
  public static final String REPEATED_LENGTH = "repeated_length";

  /**
   * Format: dictionary hash 0, {@code trace_id} not 16 bytes, an IP address not 0, 4 or 16 bytes, a
   * batch header without seq or with another instance, an unparsable message (12 §3.3 class 5).
   */
  public static final String FORMAT = "format";

  /** Validates the components. */
  public Violation {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(description, "description");
  }

  /** A cap violation ({@code RESOURCE_EXHAUSTED}). */
  public static Violation exhausted(String reason, String description) {
    return new Violation(Status.Code.RESOURCE_EXHAUSTED, reason, description);
  }

  /** A format violation ({@code INVALID_ARGUMENT}, reason {@link #FORMAT}). */
  public static Violation format(String description) {
    return new Violation(Status.Code.INVALID_ARGUMENT, FORMAT, description);
  }

  /** The gRPC status of this violation. */
  public Status status() {
    return Status.fromCode(code).withDescription(description);
  }
}
