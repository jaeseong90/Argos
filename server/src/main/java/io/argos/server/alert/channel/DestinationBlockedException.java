/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;

/**
 * A notification destination failed the validation of 16 §8.1 (SEC-51): 422 {@code
 * webhook_destination_blocked} with the detail {@code {field: "target", reason}} (14 §8 example 4).
 * The URL, host name and resolved addresses are never part of it.
 *
 * <p>Reasons: {@code invalid_url}, {@code scheme} (not https and http not allowed), {@code
 * userinfo}, {@code dns} (no address, or the lookup timed out), a blocked range of {@link
 * BlockedRanges} ({@code loopback}, {@code private}, {@code link_local}, ...) and {@code
 * own_address} (an address of this server or of its public origin).
 */
public final class DestinationBlockedException extends ApiException {

  private static final long serialVersionUID = 1L;

  private final String reason;

  /** The exception with a fixed reason code. */
  public DestinationBlockedException(String reason) {
    super(ErrorCode.WEBHOOK_DESTINATION_BLOCKED, "The destination is not allowed");
    this.reason = reason;
    detail("target", reason);
  }

  /** The reason code. */
  public String reason() {
    return reason;
  }
}
