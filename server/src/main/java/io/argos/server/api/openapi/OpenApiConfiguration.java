/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.auth.HandlerRoleSource;
import io.argos.server.auth.Role;
import io.argos.server.config.ServerBuild;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The run-time OpenAPI document (14 §8, ADR-0010 C-110). The paths are set in {@code
 * application.yml}: JSON at {@code /api/v1/openapi}, YAML at {@code /api/v1/openapi.yaml}, only
 * {@code /api/**} documented, springdoc's default {@code /v3/api-docs} paths gone.
 *
 * <p>springdoc's own handlers cannot carry {@code @RequiresRole}, so {@link #openApiRoleSource()}
 * gives them {@code VIEWER} (14 §8: "viewer 세션으로 제공"); without it they would fail closed.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  /** The package prefix of springdoc's handlers. */
  private static final String SPRINGDOC_PACKAGE = "org.springdoc.";

  /**
   * Title and version of the document. The server URL is the relative {@code /}: the document does
   * not repeat the host name that a client happened to use (14 §2.6 keeps host names out of API
   * bodies), and clients use the origin they fetched it from.
   */
  @Bean
  OpenAPI argosOpenApi(ServerBuild build) {
    return new OpenAPI()
        .info(new Info().title("Argos API").version(build.version()))
        .servers(List.of(new Server().url("/")));
  }

  /** The role of springdoc's document handlers: {@code VIEWER}. */
  @Bean
  HandlerRoleSource openApiRoleSource() {
    return handler ->
        handler.getBeanType().getName().startsWith(SPRINGDOC_PACKAGE)
            ? Optional.of(Role.VIEWER)
            : Optional.empty();
  }
}
