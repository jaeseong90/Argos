/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;

/**
 * A secret cannot be encrypted or decrypted (16 §8.3): no master key, the key of an envelope is not
 * loaded, or the envelope does not authenticate. The REST answer is 409 {@code secret_unavailable},
 * never 500. Like every {@link ApiException} it carries no stack trace and no message from the
 * caller: neither the envelope nor the plaintext ever reaches a log or a response.
 */
public final class SecretUnavailableException extends ApiException {

  private static final long serialVersionUID = 1L;

  /** Why, as a fixed code (also the {@code notification_deliveries.error} of a failed delivery). */
  private final String reason;

  /**
   * The exception.
   *
   * @param reason {@code no_master_key}, {@code unknown_key}, {@code malformed} or {@code
   *     authentication_failed}
   */
  public SecretUnavailableException(String reason) {
    super(ErrorCode.SECRET_UNAVAILABLE);
    this.reason = reason;
    detail("secret", reason);
  }

  /** The fixed reason code. */
  public String reason() {
    return reason;
  }
}
