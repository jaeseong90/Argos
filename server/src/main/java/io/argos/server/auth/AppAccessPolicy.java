/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * The single place of per-application permissions (16 §7.11, SEC-50): every handler of an
 * application-scoped resource calls {@link #check} with the {@code app_id} of the request. In 1.0
 * every signed-in user may see every application; 1.x application permissions change this one
 * method and nothing else (12 §7.3 calls the same hook {@code AppScopeFilter}).
 */
@Component
public class AppAccessPolicy {

  /**
   * Checks that {@code principal} may use the application {@code appId}.
   *
   * @param principal the requesting user; null is refused (a handler that lost its principal fails
   *     closed)
   * @param appId the application
   * @throws ApiException 401 {@code unauthenticated} without a principal
   */
  public void check(ArgosPrincipal principal, long appId) {
    if (principal == null) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
  }
}
