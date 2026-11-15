/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.util.Objects;

/**
 * One {@code apps} row as a session sees it (DSN-02-14: an app is a (name, env) pair).
 *
 * @param id {@code apps.id} ({@code ServerHello.app_id}, ClickHouse {@code app_id})
 * @param name {@code apps.name}
 * @param env {@code apps.env}
 * @param registeredFromAppId {@code apps.registered_from_app_id}: the home app of the agent token
 *     whose Hello registered this app automatically as an environment variant (ADR-0010 C-155), 0
 *     when none (created by an administrator, or its home app was deleted)
 */
public record AppRef(int id, String name, String env, int registeredFromAppId) {

  /** Validates the components. */
  public AppRef {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(env, "env");
  }

  /** An app that is nobody's environment variant. */
  public AppRef(int id, String name, String env) {
    this(id, name, env, 0);
  }

  /**
   * Whether an agent token of home app {@code homeAppId} may use this app (ADR-0010 C-155): the
   * home app itself, or an environment variant registered from it.
   */
  public boolean usableWith(int homeAppId) {
    return id == homeAppId || (registeredFromAppId != 0 && registeredFromAppId == homeAppId);
  }
}
