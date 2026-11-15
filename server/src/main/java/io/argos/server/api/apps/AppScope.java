/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The application of an application-scoped request (14 §2.3, DSN-14-07, SEC-50): the {@code apps}
 * row, 404 {@code not_found} when there is none, and the per-application permission of {@link
 * AppAccessPolicy} (the one place 1.x application permissions change). An instance-scoped request
 * resolves the instance first, then its application.
 */
@Component
public class AppScope {

  private final CatalogRepository catalog;
  private final AppAccessPolicy access;

  public AppScope(CatalogRepository catalog, AppAccessPolicy access) {
    this.catalog = catalog;
    this.access = access;
  }

  /**
   * The application {@code appId} for the current user.
   *
   * @throws ApiException 404 {@code not_found}, or the refusal of {@link AppAccessPolicy}
   */
  public AppRecord app(int appId) {
    AppRecord app =
        (appId > 0 ? catalog.app(appId) : Optional.<AppRecord>empty())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    access.check(ArgosPrincipal.current().orElse(null), app.id());
    return app;
  }

  /**
   * The instance {@code instanceId} and its application for the current user.
   *
   * @throws ApiException 404 {@code not_found}, or the refusal of {@link AppAccessPolicy}
   */
  public Scoped instance(int instanceId) {
    InstanceRecord instance =
        (instanceId > 0 ? catalog.instance(instanceId) : Optional.<InstanceRecord>empty())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return new Scoped(app(instance.appId()), instance);
  }

  /**
   * An instance with its application.
   *
   * @param app the application
   * @param instance the instance
   */
  public record Scoped(AppRecord app, InstanceRecord instance) {}
}
