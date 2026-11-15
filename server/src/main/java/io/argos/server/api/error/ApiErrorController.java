/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error page of the HTTP port ({@code server.error.path}, {@code /error}): the container
 * forwards here when a filter throws or when something calls {@code sendError} outside Spring MVC.
 * It answers with the same {@link ErrorBody} as {@link ApiExceptionHandler} and replaces Spring
 * Boot's {@code BasicErrorController} (and with it the Boot error attributes: no {@code timestamp},
 * {@code path}, {@code exception} or {@code trace} ever, 16 §7.14).
 *
 * <ul>
 *   <li>the body limit of 14 §7.2 anywhere in the cause chain: 400 {@code payload_too_large};
 *   <li>otherwise the generic code of the status ({@link ErrorCode#forStatus(int)}); 5xx is {@code
 *       internal} with the fixed message;
 *   <li>a direct request to the path (not an error dispatch): 404 {@code not_found}.
 * </ul>
 *
 * The exception itself was already logged by the container; nothing of it reaches the body.
 */
@RestController
@RequiresRole(Role.PUBLIC)
public class ApiErrorController implements ErrorController {

  @RequestMapping("${server.error.path:${error.path:/error}}")
  public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (request.getDispatcherType() != DispatcherType.ERROR) {
      ErrorBodyWriter.write(request, response, ErrorCode.NOT_FOUND);
      return;
    }
    Throwable exception =
        request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t ? t : null;
    if (ApiExceptionHandler.isPayloadTooLarge(exception)) {
      ErrorBodyWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE);
      return;
    }
    ApiException api = ApiExceptionHandler.cause(exception, ApiException.class);
    if (api != null) {
      ErrorBodyWriter.write(request, response, api);
      return;
    }
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int code = status instanceof Integer i ? i : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    ErrorBodyWriter.write(request, response, ErrorCode.forStatus(code));
  }
}
