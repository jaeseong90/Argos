/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.web.RequestBodyLimitFilter;
import io.argos.server.web.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

/** The global exception mapping and the error page (14 §2.6, 16 §7.14, SEC-56). */
@Tag("req:SEC-56")
class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
    request.setAttribute(RequestIdFilter.ATTRIBUTE, "01JREQ");
    return request;
  }

  /**
   * The exception the body limit really throws: a chunked body (no declared length) of 1 MiB + 1
   * bytes read through {@link RequestBodyLimitFilter}.
   */
  static RequestBodyLimitFilter.PayloadTooLargeException payloadTooLarge() throws Exception {
    MockHttpServletRequest chunked =
        new MockHttpServletRequest("POST", "/api/v1/x") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    chunked.setContent(new byte[(int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
    AtomicReference<RequestBodyLimitFilter.PayloadTooLargeException> thrown =
        new AtomicReference<>();
    new RequestBodyLimitFilter()
        .doFilter(
            chunked,
            new MockHttpServletResponse(),
            (req, res) -> {
              try (InputStream in = req.getInputStream()) {
                in.readAllBytes();
              } catch (RequestBodyLimitFilter.PayloadTooLargeException e) {
                thrown.set(e);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    assertThat(thrown.get()).as("the limit was hit").isNotNull();
    return thrown.get();
  }

  @Test
  @DisplayName(
      "T-SEC-56a the body limit wrapped in HttpMessageNotReadableException is 400 payload_too_large")
  void payloadTooLargeWrapped() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    HttpMessageNotReadableException wrapped =
        new HttpMessageNotReadableException(
            "I/O error while reading input message",
            payloadTooLarge(),
            new MockHttpInputMessage(new byte[0]));
    handler.notReadable(wrapped, request(), response);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"payload_too_large\",\"message\":\"The request body exceeds the"
                + " limit of 1 MB\",\"request_id\":\"01JREQ\"}}");
    assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
  }

  @Test
  @DisplayName(
      "T-SEC-56a the body limit thrown directly or deeper in a chain is 400 payload_too_large")
  void payloadTooLargeAnywhere() throws Exception {
    MockHttpServletResponse direct = new MockHttpServletResponse();
    handler.unexpected(payloadTooLarge(), request(), direct);
    assertThat(direct.getStatus()).isEqualTo(400);
    assertThat(direct.getContentAsString()).contains("\"code\":\"payload_too_large\"");

    MockHttpServletResponse deep = new MockHttpServletResponse();
    handler.unexpected(
        new IllegalStateException("outer", new UncheckedIOException(payloadTooLarge())),
        request(),
        deep);
    assertThat(deep.getStatus()).isEqualTo(400);
    assertThat(deep.getContentAsString()).contains("\"code\":\"payload_too_large\"");
  }

  @Test
  @DisplayName("an unreadable body without the limit is 400 invalid_request without parser text")
  void unreadable() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.notReadable(
        new HttpMessageNotReadableException(
            "JSON parse error: Unexpected character ('<' (code 60)) at [Source: secret-input]",
            new MockHttpInputMessage(new byte[0])),
        request(),
        response);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"invalid_request\"")
        .doesNotContain("secret-input")
        .doesNotContain("parse");
  }

  @Test
  @DisplayName(
      "T-SEC-56a an unexpected exception is 500 internal: fixed message and request id, nothing of"
          + " the exception")
  void unexpected() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.unexpected(
        new IllegalStateException(
            "ERROR: relation \"argos.users\" SELECT password_hash FROM users at"
                + " /opt/argos/lib/server.jar host pg-internal-1"),
        request(),
        response);
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"internal\",\"message\":\"Internal server error\","
                + "\"request_id\":\"01JREQ\"}}");
  }

  @Test
  @DisplayName("an ApiException carries its code, message, details and headers")
  void apiException() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.apiException(
        new ApiException(ErrorCode.RATE_LIMITED).header("Retry-After", "12").detail(null, "api"),
        request(),
        response);
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("12");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Too many requests\","
                + "\"details\":[{\"reason\":\"api\"}],\"request_id\":\"01JREQ\"}}");
  }

  @Test
  @DisplayName("bean validation errors become invalid_request details in snake_case")
  void validationDetails() throws Exception {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "body");
    result.addError(
        new FieldError(
            "body", "kioskSession.ipCidrs", null, false, new String[] {"Size"}, null, "x"));
    result.addError(
        new FieldError("body", "username", null, false, new String[] {"NotBlank"}, null, "y"));
    MethodParameter parameter =
        new MethodParameter(
            ApiExceptionHandlerTest.class.getDeclaredMethod("target", String.class), 0);
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.notValid(new MethodArgumentNotValidException(parameter, result), request(), response);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"invalid_request\",\"message\":\"The request is invalid\","
                + "\"details\":[{\"field\":\"kiosk_session.ip_cidrs\",\"reason\":\"size\"},"
                + "{\"field\":\"username\",\"reason\":\"not_blank\"}],\"request_id\":\"01JREQ\"}}");
  }

  @SuppressWarnings("unused")
  private void target(String body) {}

  @Test
  @DisplayName("405 carries Allow; framework 4xx statuses map to their generic code")
  void frameworkStatuses() throws Exception {
    MockHttpServletResponse methodResponse = new MockHttpServletResponse();
    handler.methodNotSupported(
        new HttpRequestMethodNotSupportedException("DELETE", List.of("POST", "GET")),
        request(),
        methodResponse);
    assertThat(methodResponse.getStatus()).isEqualTo(405);
    assertThat(methodResponse.getHeader("Allow")).isEqualTo("GET, POST");
    assertThat(methodResponse.getContentAsString()).contains("\"code\":\"method_not_allowed\"");

    MockHttpServletResponse conflict = new MockHttpServletResponse();
    handler.unexpected(
        new ResponseStatusException(HttpStatus.CONFLICT, "internal detail"), request(), conflict);
    assertThat(conflict.getStatus()).isEqualTo(409);
    assertThat(conflict.getContentAsString())
        .contains("\"code\":\"invalid_state\"")
        .doesNotContain("internal detail");

    MockHttpServletResponse server = new MockHttpServletResponse();
    handler.unexpected(
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "pool db-1 down"),
        request(),
        server);
    assertThat(server.getStatus()).isEqualTo(500);
    assertThat(server.getContentAsString())
        .contains("\"code\":\"internal\"")
        .doesNotContain("db-1");
  }

  @Test
  @DisplayName("snake_case of field names and reason codes")
  void naming() {
    assertThat(ApiExceptionHandler.snakeCase("ipMode")).isEqualTo("ip_mode");
    assertThat(ApiExceptionHandler.snakeCase("kioskSession.ipCidrs[0]"))
        .isEqualTo("kiosk_session.ip_cidrs[0]");
    assertThat(ApiExceptionHandler.snakeCase("username")).isEqualTo("username");
    assertThat(ApiExceptionHandler.reason("NotBlank")).isEqualTo("not_blank");
    assertThat(ApiExceptionHandler.reason("jakarta.validation.constraints.Size")).isEqualTo("size");
    assertThat(ApiExceptionHandler.reason(null)).isEqualTo("invalid");
  }

  @Test
  @DisplayName(
      "T-SEC-56a error page: the body limit, an ApiException in the chain, the status code, and 404"
          + " for a direct request")
  void errorPage() throws Exception {
    ApiErrorController page = new ApiErrorController();

    MockHttpServletRequest limit = errorDispatch(500, new RuntimeException(payloadTooLarge()));
    MockHttpServletResponse limitResponse = new MockHttpServletResponse();
    page.error(limit, limitResponse);
    assertThat(limitResponse.getStatus()).isEqualTo(400);
    assertThat(limitResponse.getContentAsString()).contains("\"code\":\"payload_too_large\"");

    MockHttpServletRequest api =
        errorDispatch(500, new IllegalStateException(new ApiException(ErrorCode.CSRF_INVALID)));
    MockHttpServletResponse apiResponse = new MockHttpServletResponse();
    page.error(api, apiResponse);
    assertThat(apiResponse.getStatus()).isEqualTo(403);
    assertThat(apiResponse.getContentAsString()).contains("\"code\":\"csrf_invalid\"");

    MockHttpServletRequest boom =
        errorDispatch(500, new IllegalStateException("SELECT * FROM argos.users"));
    MockHttpServletResponse boomResponse = new MockHttpServletResponse();
    page.error(boom, boomResponse);
    assertThat(boomResponse.getStatus()).isEqualTo(500);
    assertThat(boomResponse.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"internal\",\"message\":\"Internal server error\","
                + "\"request_id\":\"01JREQ\"}}");

    MockHttpServletRequest missing = errorDispatch(404, null);
    MockHttpServletResponse missingResponse = new MockHttpServletResponse();
    page.error(missing, missingResponse);
    assertThat(missingResponse.getStatus()).isEqualTo(404);
    assertThat(missingResponse.getContentAsString()).contains("\"code\":\"not_found\"");

    MockHttpServletRequest direct = request();
    MockHttpServletResponse directResponse = new MockHttpServletResponse();
    page.error(direct, directResponse);
    assertThat(directResponse.getStatus()).isEqualTo(404);
    assertThat(directResponse.getContentAsString()).contains("\"code\":\"not_found\"");
  }

  @Test
  @DisplayName("the writer leaves a committed response alone")
  void committed() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getOutputStream().write("partial".getBytes(StandardCharsets.UTF_8));
    response.setCommitted(true);
    ErrorBodyWriter.write(request(), response, ErrorCode.INTERNAL);
    assertThat(response.getContentAsString()).isEqualTo("partial");
  }

  private static MockHttpServletRequest errorDispatch(int status, Throwable exception) {
    MockHttpServletRequest request = request();
    request.setDispatcherType(DispatcherType.ERROR);
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    if (exception != null) {
      request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
    }
    return request;
  }
}
