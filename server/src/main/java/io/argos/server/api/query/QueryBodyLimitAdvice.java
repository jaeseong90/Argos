/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * The JSON body limit of the query API (14 §7.2: 256 KB for search filters; the server-wide 1 MB of
 * {@code RequestBodyLimitFilter} stays below it for every other body): a {@code @RequestBody} of a
 * controller in {@code io.argos.server.api} is read into memory up to {@value
 * QueryLimits#MAX_JSON_BODY_BYTES} bytes before it is converted, and a longer body is answered 400
 * {@code payload_too_large} without being parsed.
 */
@ControllerAdvice(basePackages = "io.argos.server.api")
public class QueryBodyLimitAdvice extends RequestBodyAdviceAdapter {

  @Override
  public boolean supports(
      MethodParameter methodParameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType)
      throws IOException {
    byte[] body = readBounded(inputMessage.getBody(), QueryLimits.MAX_JSON_BODY_BYTES);
    HttpHeaders headers = inputMessage.getHeaders();
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }

  /**
   * The bytes of {@code in}, at most {@code limit}.
   *
   * @throws ApiException 400 {@code payload_too_large} when there are more
   */
  static byte[] readBounded(InputStream in, int limit) throws IOException {
    byte[] bytes = in.readNBytes(limit + 1);
    if (bytes.length > limit) {
      throw new ApiException(
          ErrorCode.PAYLOAD_TOO_LARGE, "The request body exceeds the limit of 256 KB");
    }
    return bytes;
  }
}
