/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.web;

import io.argos.sample.heavy.startup.StartupReport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds the heavy startup facts to shop-boot3's GET /api/runtime (its DiagnosticsController stays
 * unchanged): {@code heavyWarmup}, {@code startupLoadedClasses}, {@code startupClassTargetMet} and
 * the per-step class counts. The smoke checks two of them (build file: expectRuntime).
 */
@RestControllerAdvice
public class HeavyRuntimeAdvice implements ResponseBodyAdvice<Object> {

  private static final String RUNTIME_PATH = "/api/runtime";

  private final StartupReport report;

  public HeavyRuntimeAdvice(StartupReport report) {
    this.report = report;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return "runtime"
            .equals(returnType.getMethod() == null ? null : returnType.getMethod().getName())
        && Map.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!(body instanceof Map<?, ?> map) || !request.getURI().getPath().endsWith(RUNTIME_PATH)) {
      return body;
    }
    Map<Object, Object> extended = new LinkedHashMap<>(map);
    extended.putAll(report.describe());
    return extended;
  }
}
