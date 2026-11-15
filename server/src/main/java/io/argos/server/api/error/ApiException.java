/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An expected API error: thrown by controllers, interceptors and services, answered with the {@link
 * ErrorBody} of its code by {@link ApiExceptionHandler} (or written directly by filters through
 * {@link ErrorBodyWriter}). It is a client-facing outcome, not a failure of the server: it has no
 * stack trace and is not logged as an error.
 *
 * <pre>{@code
 * throw new ApiException(ErrorCode.PASSWORD_POLICY).detail("password", "too_short");
 * throw new ApiException(ErrorCode.RATE_LIMITED).header("Retry-After", "12");
 * }</pre>
 *
 * The message must be a fixed server text: never request data, user input, secrets or exception
 * text. For 5xx codes the body always carries the fixed message of the code.
 */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;
  private final String clientMessage;
  private final ArrayList<ErrorBody.Detail> details = new ArrayList<>();
  private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

  /** An error with the fixed message of its code. */
  public ApiException(ErrorCode code) {
    this(code, null);
  }

  /**
   * An error with a more specific fixed message.
   *
   * @param code error code
   * @param message fixed server text, or null for the code's message
   */
  public ApiException(ErrorCode code, String message) {
    super(Objects.requireNonNull(code, "code").code(), null, false, false);
    this.errorCode = code;
    this.clientMessage = message;
  }

  /** Adds a field-level detail (field in API spelling, snake_case reason). */
  public ApiException detail(String field, String reason) {
    details.add(new ErrorBody.Detail(field, reason));
    return this;
  }

  /** Adds a response header, e.g. {@code Retry-After}. */
  public ApiException header(String name, String value) {
    headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    return this;
  }

  /** The error code. */
  public ErrorCode errorCode() {
    return errorCode;
  }

  /** The message for the body, or null for the code's fixed message. */
  public String clientMessage() {
    return clientMessage;
  }

  /** The details, in order. */
  public List<ErrorBody.Detail> details() {
    return List.copyOf(details);
  }

  /** The extra response headers, in order. */
  public Map<String, String> headers() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }
}
