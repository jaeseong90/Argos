/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import java.util.Locale;

/**
 * The error codes of the REST API (14 §2.6, DSN-14-05) with their HTTP status and fixed message.
 *
 * <p>The code is the contract: clients branch on it, never on the message. The message is a fixed
 * English text of the server (no request data, no exception text); handlers may pass a more
 * specific fixed text for 4xx codes. Every 5xx body carries its fixed message and the request id
 * only (16 §7.14, SEC-56).
 *
 * <p>Sources: the 14 §2.6 table (400 to 504), the authentication codes of 14 §2.2 and 16 §7 ({@code
 * password_change_required} of 16 §7.9) and four generic codes for requests that fail before any
 * endpoint-specific validation ({@code invalid_request}, {@code method_not_allowed}, {@code
 * not_acceptable}, {@code unsupported_media_type}; not in the 14 §2.6 table, reported to the PM
 * with WO-24). {@code internal} is the 14 §2.6 name (16 §7.14 writes {@code internal_error}; 14 is
 * the API contract).
 */
public enum ErrorCode {

  // 400: parameter validation (14 §2.6)
  /** The time range is invalid ({@code to} before {@code from}, longer than allowed). */
  RANGE_INVALID(400, "The time range is invalid"),
  /** A {@code limit}, array size or similar bound is exceeded. */
  LIMIT_EXCEEDED(400, "A request limit is exceeded"),
  /** A filter expression is invalid. */
  FILTER_INVALID(400, "A filter is invalid"),
  /** The sort field or direction is not supported. */
  UNSUPPORTED_SORT(400, "The sort order is not supported"),
  /** The request body or URL is larger than allowed (14 §7.2). */
  PAYLOAD_TOO_LARGE(400, "The request body exceeds the limit of 1 MB"),
  /** Generic validation failure: unreadable body, missing or malformed parameter. */
  INVALID_REQUEST(400, "The request is invalid"),

  // 401: not authenticated (14 §2.2, §2.6)
  /** No valid session. */
  UNAUTHENTICATED(401, "Authentication is required"),
  /** The session or access token has expired. */
  SESSION_EXPIRED(401, "The session has expired"),
  /** The session was revoked (logout, password or role change, reuse detection, ...). */
  SESSION_REVOKED(401, "The session has been revoked"),

  // 403: not allowed (14 §2.6, 16 §7.9)
  /** The role of the session does not allow the operation. */
  FORBIDDEN(403, "The operation is not allowed"),
  /** CSRF double-submit token or Origin check failed (16 §7.6). */
  CSRF_INVALID(403, "The CSRF check failed"),
  /** The resource belongs to another user (16 §7.11). */
  NOT_OWNER(403, "The resource belongs to another user"),
  /** The operation needs the {@code pii.view} permission. */
  PII_VIEW_REQUIRED(403, "The pii.view permission is required"),
  /** The password must be changed before any other operation (16 §7.9). */
  PASSWORD_CHANGE_REQUIRED(403, "The password must be changed first"),

  /** No such resource (also used instead of 403 when existence must stay hidden). */
  NOT_FOUND(404, "The resource does not exist"),
  /** The HTTP method is not supported by the resource. */
  METHOD_NOT_ALLOWED(405, "The method is not allowed"),
  /** No representation acceptable to the client. */
  NOT_ACCEPTABLE(406, "No acceptable representation"),

  // 409: state conflicts
  /** The resource already exists. */
  ALREADY_EXISTS(409, "The resource already exists"),
  /** The resource is not in a state that allows the operation. */
  INVALID_STATE(409, "The resource is not in a valid state for this operation"),
  /**
   * A stored secret cannot be encrypted or decrypted: no master key is configured, or the key that
   * encrypted it is not loaded (a key rotation or a restore without the key, 16 §8.3; WO-25).
   */
  SECRET_UNAVAILABLE(409, "The secret is unavailable; check the master key configuration"),

  /** The request body has a media type the endpoint does not accept. */
  UNSUPPORTED_MEDIA_TYPE(415, "The media type is not supported"),

  // 422: semantic validation
  /** A webhook destination is blocked (SEC-51). */
  WEBHOOK_DESTINATION_BLOCKED(422, "The destination is blocked"),
  /** The password does not satisfy the password policy (16 §7.9, SEC-48). */
  PASSWORD_POLICY(422, "The password does not satisfy the password policy"),
  /** A notification template uses a variable that is not allowed (SEC-39). */
  TEMPLATE_VARIABLE_FORBIDDEN(422, "The template uses a variable that is not allowed"),
  /** A remote configuration key is outside the allow list (SEC-23). */
  REMOTE_KEY_NOT_ALLOWED(422, "The configuration key is not allowed"),
  /**
   * A well-formed filter that cannot be applied to the data, e.g. a {@code user_id} filter on an
   * application in hashed mode before its salt exists (ADR-0010 C-152, detail {@code
   * user_id_hash_unavailable}). The wire code is {@code filter_invalid} like the 400 one; the
   * status 422 says that the request is valid but not answerable.
   */
  FILTER_UNPROCESSABLE(422, "filter_invalid", "The filter cannot be applied to this data"),

  /** The account is locked after repeated login failures (16 §7.9). */
  ACCOUNT_LOCKED(423, "The account is locked"),

  /** A rate limit is exceeded (14 §2.7); the response carries {@code Retry-After}. */
  RATE_LIMITED(429, "Too many requests"),

  // 5xx: fixed message and request id only
  /** Unexpected server error. */
  INTERNAL(500, "Internal server error"),
  /** A storage backend is unavailable. */
  STORAGE_UNAVAILABLE(503, "Storage is unavailable"),
  /** The server is starting. */
  STARTING(503, "The server is starting"),
  /** A query took longer than allowed. */
  QUERY_TIMEOUT(504, "The query timed out; narrow the range");

  private final int status;
  private final String defaultMessage;
  private final String code;

  ErrorCode(int status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
    this.code = name().toLowerCase(Locale.ROOT);
  }

  /** A code whose wire name differs from the constant (two statuses of one wire code). */
  ErrorCode(int status, String code, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
    this.code = code;
  }

  /** The wire code, e.g. {@code csrf_invalid}. */
  public String code() {
    return code;
  }

  /** The HTTP status. */
  public int status() {
    return status;
  }

  /** The fixed message used when a handler gives none (always for 5xx). */
  public String defaultMessage() {
    return defaultMessage;
  }

  /** Whether this is a server error (5xx): fixed message, no details. */
  public boolean serverError() {
    return status >= 500;
  }

  /**
   * The code used for a bare HTTP status (framework exceptions, container error pages): the generic
   * code of that status. 413 and 414 become 400 {@code payload_too_large} and {@code
   * limit_exceeded} (14 §2.6, §7.2); other 4xx statuses {@link #INVALID_REQUEST}; every 5xx status
   * {@link #INTERNAL} (the specific 503 and 504 codes are raised explicitly by the code that knows
   * the cause).
   */
  public static ErrorCode forStatus(int status) {
    return switch (status) {
      case 400 -> INVALID_REQUEST;
      case 401 -> UNAUTHENTICATED;
      case 403 -> FORBIDDEN;
      case 404 -> NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 406 -> NOT_ACCEPTABLE;
      case 409 -> INVALID_STATE;
      case 413 -> PAYLOAD_TOO_LARGE;
      case 414 -> LIMIT_EXCEEDED;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 423 -> ACCOUNT_LOCKED;
      case 429 -> RATE_LIMITED;
      default -> status >= 400 && status < 500 ? INVALID_REQUEST : INTERNAL;
    };
  }
}
