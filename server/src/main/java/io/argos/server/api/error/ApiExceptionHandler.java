/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import io.argos.server.web.RequestBodyLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The global exception mapping of the REST API (14 §2.6, 16 §7.14, SEC-56): every exception that
 * leaves a controller or a handler interceptor becomes an {@link ErrorBody} written by {@link
 * ErrorBodyWriter}.
 *
 * <ul>
 *   <li>{@link ApiException}: its code, message, details and headers;
 *   <li>framework exceptions of the request itself: 400 {@code invalid_request} (unreadable body,
 *       missing or malformed parameters, validation failures with field details in snake_case), 400
 *       {@code payload_too_large} (the body limit of 14 §7.2, also when the {@link
 *       RequestBodyLimitFilter.PayloadTooLargeException} arrives wrapped, e.g. as the cause of
 *       {@link HttpMessageNotReadableException}), 404 {@code not_found}, 405 {@code
 *       method_not_allowed} with {@code Allow}, 406, 415;
 *   <li>Spring Security exceptions: 401 {@code unauthenticated}, 403 {@code forbidden};
 *   <li>anything else: 500 {@code internal} with the fixed message and the request id; the
 *       exception is logged (server log only, masked by the log pipeline), never put in the body.
 * </ul>
 *
 * Messages are fixed texts; nothing of the exception (message, class, stack, SQL, paths, hosts)
 * reaches the body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Expected API errors. */
  @ExceptionHandler(ApiException.class)
  public void apiException(ApiException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, e);
  }

  /** An unreadable body: too large (body limit) or not valid JSON for the target type. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public void notReadable(
      HttpMessageNotReadableException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isPayloadTooLarge(e)) {
      ErrorBodyWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE);
      return;
    }
    write(request, response, ErrorCode.INVALID_REQUEST, "The request body is not readable", null);
  }

  /** {@code @Valid} request bodies. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public void notValid(
      MethodArgumentNotValidException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    List<ErrorBody.Detail> details = new ArrayList<>();
    for (FieldError error : e.getBindingResult().getFieldErrors()) {
      details.add(new ErrorBody.Detail(snakeCase(error.getField()), reason(error.getCode())));
    }
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(error -> details.add(ErrorBody.Detail.of(reason(error.getCode()))));
    write(request, response, ErrorCode.INVALID_REQUEST, "The request is invalid", details);
  }

  /** Method validation of parameters ({@code @Validated} constraints on handler arguments). */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public void methodValidation(
      HandlerMethodValidationException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    List<ErrorBody.Detail> details = new ArrayList<>();
    e.getParameterValidationResults()
        .forEach(
            result -> {
              String name = result.getMethodParameter().getParameterName();
              for (MessageSourceResolvable error : result.getResolvableErrors()) {
                details.add(new ErrorBody.Detail(snakeCase(name), reason(lastCode(error))));
              }
            });
    write(request, response, ErrorCode.INVALID_REQUEST, "The request is invalid", details);
  }

  /** Bean Validation outside the MVC binding (services validating their arguments). */
  @ExceptionHandler(ConstraintViolationException.class)
  public void constraintViolation(
      ConstraintViolationException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
    List<ErrorBody.Detail> details =
        violations == null
            ? List.of()
            : violations.stream()
                .map(
                    v ->
                        new ErrorBody.Detail(
                            snakeCase(lastNode(v.getPropertyPath().toString())),
                            reason(
                                v.getConstraintDescriptor()
                                    .getAnnotation()
                                    .annotationType()
                                    .getSimpleName())))
                .collect(Collectors.toList());
    write(request, response, ErrorCode.INVALID_REQUEST, "The request is invalid", details);
  }

  /** A required query parameter is missing. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public void missingParameter(
      MissingServletRequestParameterException e,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        ErrorCode.INVALID_REQUEST,
        "A required parameter is missing",
        List.of(new ErrorBody.Detail(e.getParameterName(), "missing")));
  }

  /** A required header is missing. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public void missingHeader(
      MissingRequestHeaderException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        ErrorCode.INVALID_REQUEST,
        "A required header is missing",
        List.of(new ErrorBody.Detail(e.getHeaderName(), "missing")));
  }

  /** A required cookie is missing. */
  @ExceptionHandler(MissingRequestCookieException.class)
  public void missingCookie(
      MissingRequestCookieException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        ErrorCode.INVALID_REQUEST,
        "A required cookie is missing",
        List.of(new ErrorBody.Detail(e.getCookieName(), "missing")));
  }

  /** A path or query parameter of the wrong type (e.g. a malformed UUID). */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public void typeMismatch(
      MethodArgumentTypeMismatchException e,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        ErrorCode.INVALID_REQUEST,
        "A parameter has an invalid value",
        List.of(new ErrorBody.Detail(snakeCase(e.getName()), "invalid_format")));
  }

  /** The method is not supported by the resource: 405 with {@code Allow}. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public void methodNotSupported(
      HttpRequestMethodNotSupportedException e,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Set<HttpMethod> supported = e.getSupportedHttpMethods();
    Map<String, String> headers =
        supported == null || supported.isEmpty()
            ? Map.of()
            : Map.of(
                "Allow",
                supported.stream()
                    .map(HttpMethod::name)
                    .sorted()
                    .collect(Collectors.joining(", ")));
    ErrorBodyWriter.write(
        request, response, ErrorCode.METHOD_NOT_ALLOWED, null, List.of(), headers);
  }

  /** The request body has an unsupported media type. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public void mediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  /** No acceptable representation (the error itself is still JSON). */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public void notAcceptable(
      HttpMediaTypeNotAcceptableException e,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.NOT_ACCEPTABLE);
  }

  /** No handler and no static resource for the path. */
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public void notFound(Exception e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.NOT_FOUND);
  }

  /** A multipart upload over the limit. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public void uploadTooLarge(
      MaxUploadSizeExceededException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE);
  }

  /** Spring Security: not authenticated. */
  @ExceptionHandler(AuthenticationException.class)
  public void authentication(
      AuthenticationException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.UNAUTHENTICATED);
  }

  /** Spring Security: authenticated, not allowed. */
  @ExceptionHandler(AccessDeniedException.class)
  public void accessDenied(
      AccessDeniedException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ErrorBodyWriter.write(request, response, ErrorCode.FORBIDDEN);
  }

  /** The client went away: nothing can be written. */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void clientGone(AsyncRequestNotUsableException e) {
    LOG.debug("Response not usable any more (client disconnected)");
  }

  /**
   * Everything else: framework exceptions that carry a status (4xx by status, any 5xx as {@code
   * internal}), the body limit inside any wrapper, and unexpected failures (500, logged).
   */
  @ExceptionHandler(Exception.class)
  public void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (isPayloadTooLarge(e)) {
      ErrorBodyWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE);
      return;
    }
    ApiException wrapped = cause(e, ApiException.class);
    if (wrapped != null) {
      ErrorBodyWriter.write(request, response, wrapped);
      return;
    }
    if (e instanceof ErrorResponse status && status.getStatusCode().is4xxClientError()) {
      ErrorBodyWriter.write(request, response, ErrorCode.forStatus(status.getStatusCode().value()));
      return;
    }
    LOG.error(
        "Request {} {} failed with {}",
        request.getMethod(),
        request.getRequestURI(),
        e.getClass().getName(),
        e);
    ErrorBodyWriter.write(request, response, ErrorCode.INTERNAL);
  }

  private static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      ErrorCode code,
      String message,
      List<ErrorBody.Detail> details)
      throws IOException {
    ErrorBodyWriter.write(
        request, response, code, message, details == null ? List.of() : details, Map.of());
  }

  /** Whether the body limit of {@link RequestBodyLimitFilter} is anywhere in the cause chain. */
  static boolean isPayloadTooLarge(Throwable e) {
    return cause(e, RequestBodyLimitFilter.PayloadTooLargeException.class) != null;
  }

  /**
   * The first throwable of {@code type} in the cause chain of {@code e}, or null. At most 32 links
   * are followed, so a cyclic chain ends too.
   */
  static <T extends Throwable> T cause(Throwable e, Class<T> type) {
    Throwable t = e;
    for (int depth = 0; t != null && depth < 32; depth++) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      t = t.getCause();
    }
    return null;
  }

  /** {@code kioskSession.ipCidrs} → {@code kiosk_session.ip_cidrs} (API spelling, 14). */
  static String snakeCase(String name) {
    if (name == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(name.length() + 8);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0 && name.charAt(i - 1) != '.' && name.charAt(i - 1) != '[') {
          out.append('_');
        }
        out.append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code NotBlank} → {@code not_blank}; unknown → {@code invalid}. */
  static String reason(String constraintCode) {
    if (constraintCode == null || constraintCode.isBlank()) {
      return "invalid";
    }
    String simple = constraintCode.substring(constraintCode.lastIndexOf('.') + 1);
    return snakeCase(simple).toLowerCase(Locale.ROOT);
  }

  /** The most specific code of a resolvable error ({@code NotBlank} of {@code NotBlank.x.y}). */
  private static String lastCode(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
  }

  /** {@code create.arg0.username} → {@code username}. */
  private static String lastNode(String path) {
    return path.substring(path.lastIndexOf('.') + 1);
  }
}
