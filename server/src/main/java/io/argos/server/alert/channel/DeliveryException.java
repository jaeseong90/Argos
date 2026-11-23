/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

/**
 * A delivery attempt failed (12 §5.5). The message is a fixed error code, stored in {@code
 * notification_deliveries.error}: never a URL, host, address, credential or response text, so the
 * exception may be logged as it is. No stack trace (an expected outcome).
 *
 * <p>Codes: {@code connect_timeout}, {@code connect_failed}, {@code tls_failed}, {@code timeout},
 * {@code io_error}, {@code protocol_error}, {@code redirect}, {@code http_status}, {@code
 * starttls_unavailable}, {@code auth_failed}, {@code smtp_rejected}, {@code recipient_not_allowed},
 * {@code smtp_not_configured}, {@code destination_blocked}, {@code secret_unavailable}, {@code
 * no_signing_key}.
 */
public final class DeliveryException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final Integer statusCode;

  /** A failure with a fixed code. */
  public DeliveryException(String code) {
    this(code, null);
  }

  /** A failure with a fixed code and the HTTP status or SMTP reply code that caused it. */
  public DeliveryException(String code, Integer statusCode) {
    super(code, null, false, false);
    this.code = code;
    this.statusCode = statusCode;
  }

  /** The error code. */
  public String code() {
    return code;
  }

  /** The HTTP status or SMTP reply code, when the peer answered. */
  public Integer statusCode() {
    return statusCode;
  }
}
