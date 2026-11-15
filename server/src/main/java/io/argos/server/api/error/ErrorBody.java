/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Objects;

/**
 * The error body of every REST error response (14 §2.6, DSN-14-05; 16 §7.14):
 *
 * <pre>{@code
 * {"error": {"code": "range_invalid", "message": "...",
 *            "details": [{"field": "to", "reason": "before_from"}], "request_id": "01J..."}}
 * }</pre>
 *
 * <ul>
 *   <li>{@code code}: {@link ErrorCode#code()}, the contract;
 *   <li>{@code message}: a fixed English text of the server, never request data or exception text;
 *   <li>{@code details}: optional, left out when empty and always for 5xx; {@code field} names the
 *       request field or parameter in its API spelling, {@code reason} is a snake_case reason code;
 *   <li>{@code request_id}: the id of {@link io.argos.server.web.RequestIdFilter}, also in the
 *       {@code X-Request-Id} header and in every server log line of the request.
 * </ul>
 *
 * The body never contains stack traces, exception or class names, SQL, file paths or host names.
 *
 * @param error the error
 */
public record ErrorBody(Payload error) {

  /** Upper bound of {@code details} entries in one body. */
  public static final int MAX_DETAILS = 20;

  public ErrorBody {
    Objects.requireNonNull(error, "error");
  }

  /**
   * A body; for a 5xx code the message is the fixed one and the details are dropped.
   *
   * @param code error code
   * @param message fixed text, or null for {@link ErrorCode#defaultMessage()}
   * @param details details, or null
   * @param requestId request id, or null outside a request
   */
  public static ErrorBody of(
      ErrorCode code, String message, List<Detail> details, String requestId) {
    boolean server = code.serverError();
    String text = server || message == null || message.isBlank() ? code.defaultMessage() : message;
    List<Detail> kept =
        server || details == null
            ? List.of()
            : List.copyOf(details.subList(0, Math.min(details.size(), MAX_DETAILS)));
    return new ErrorBody(new Payload(code.code(), text, kept, requestId));
  }

  /**
   * The {@code error} object.
   *
   * @param code error code ({@link ErrorCode#code()})
   * @param message fixed text
   * @param details field-level details, possibly empty (then not serialised)
   * @param requestId request id, or null (then not serialised)
   */
  @JsonPropertyOrder({"code", "message", "details", "request_id"})
  public record Payload(
      String code,
      String message,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Detail> details,
      @JsonProperty("request_id") @JsonInclude(JsonInclude.Include.NON_NULL) String requestId) {

    public Payload {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
      details = details == null ? List.of() : List.copyOf(details);
    }
  }

  /**
   * One field-level problem.
   *
   * @param field the request field or parameter in its API spelling (snake_case), or null for the
   *     request as a whole
   * @param reason a snake_case reason code, e.g. {@code too_short}, {@code missing}
   */
  @JsonPropertyOrder({"field", "reason"})
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Detail(String field, String reason) {

    public Detail {
      Objects.requireNonNull(reason, "reason");
    }

    /** A detail of the request as a whole. */
    public static Detail of(String reason) {
      return new Detail(null, reason);
    }
  }
}
