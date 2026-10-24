/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.SpringDocProviders;
import org.springdoc.core.service.AbstractRequestService;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.core.service.OperationService;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectFactory;

/**
 * springdoc's /v3/api-docs resource, with a way to build the OpenAPI description at startup in the
 * calling thread. springdoc's own pre-loading (springdoc.pre-loading-enabled) runs on a separate
 * executor and may finish after the port is bound; shop-heavy wants the cost inside the measured
 * startup. The description is cached, so /v3/api-docs serves the built one.
 */
public class ApiDescription extends OpenApiWebMvcResource {

  public ApiDescription(
      ObjectFactory<OpenAPIService> openApiServices,
      AbstractRequestService requestService,
      GenericResponseService responseService,
      OperationService operationService,
      SpringDocConfigProperties properties,
      SpringDocProviders providers,
      SpringDocCustomizers customizers) {
    super(
        openApiServices,
        requestService,
        responseService,
        operationService,
        properties,
        providers,
        customizers);
  }

  /**
   * Builds (and caches) the OpenAPI description of every controller and writes it as JSON and YAML
   * (swagger-core); returns its size.
   */
  public Map<String, Object> build() throws JsonProcessingException {
    OpenAPI api = getOpenApi(Locale.ROOT);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("paths", api.getPaths() == null ? 0 : api.getPaths().size());
    result.put(
        "schemas",
        api.getComponents() == null || api.getComponents().getSchemas() == null
            ? 0
            : api.getComponents().getSchemas().size());
    result.put("jsonBytes", Json.mapper().writeValueAsBytes(api).length);
    result.put("yamlBytes", Yaml.mapper().writeValueAsBytes(api).length);
    return result;
  }
}
