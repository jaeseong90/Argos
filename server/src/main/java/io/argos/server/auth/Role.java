/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.Locale;

/**
 * The roles of the REST API (16 §7.10, 14 §7.1, SEC-49), from least to most privileged: {@code
 * ADMIN} includes {@code OPERATOR} includes {@code VIEWER}; every role includes {@code PUBLIC}.
 *
 * <p>{@link #PUBLIC} is an endpoint level only (no session needed: setup status, login, CSRF token,
 * refresh, CSP reports, error page); users have one of the three other roles ({@code users.role},
 * V001). The OpenAPI extension {@code x-argos-role} is {@link #extensionValue()} (14 §2.8).
 */
public enum Role {
  /** No session required. */
  PUBLIC,
  /** Read-only access to monitoring data. */
  VIEWER,
  /** Viewer plus thread dumps, alert rules, mutes and acknowledgements. */
  OPERATOR,
  /** Everything, including users, tokens, configuration changes and privacy operations. */
  ADMIN;

  /** Whether this role may call an endpoint that requires {@code required}. */
  public boolean includes(Role required) {
    return compareTo(required) >= 0;
  }

  /**
   * The {@code x-argos-role} value: {@code public}, {@code viewer}, {@code operator}, {@code
   * admin}.
   */
  public String extensionValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether a user can have this role ({@code users.role}): every role but {@link #PUBLIC}. */
  public boolean assignable() {
    return this != PUBLIC;
  }

  /**
   * The role of a {@code users.role} value ({@code ADMIN}, {@code OPERATOR}, {@code VIEWER}) or of
   * an API value ({@code admin}, ...), case-insensitive.
   *
   * @throws IllegalArgumentException for an unknown value or {@code public}
   */
  public static Role ofUserRole(String value) {
    if (value == null) {
      throw new IllegalArgumentException("role is required");
    }
    Role role;
    try {
      role = valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown role", e);
    }
    if (!role.assignable()) {
      throw new IllegalArgumentException("public is not a user role");
    }
    return role;
  }
}
