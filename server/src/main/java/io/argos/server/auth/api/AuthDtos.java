/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.auth.Sensitive;
import io.argos.server.storage.postgres.auth.UserRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Request and response bodies of 14 §4.1 and §4.14. Field names are the API's snake_case; times are
 * ISO-8601 UTC in milliseconds (14 §2.4). A record that holds a secret hides it in {@code
 * toString()} ({@link Sensitive}).
 */
final class AuthDtos {

  private AuthDtos() {}

  /** Times of the API: millisecond precision. */
  static Instant millis(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }

  /** The role of a user as the API lists it (14 §8 example 1: an array of one). */
  static List<String> roles(UserRecord user) {
    return List.of(user.role().extensionValue());
  }

  // --- 14 §4.1
  // ------------------------------------------------------------------------------------

  /** {@code POST /auth/login}. */
  record LoginRequest(String username, @Sensitive String password) {

    @Override
    public String toString() {
      return "LoginRequest[username=" + username + "]";
    }
  }

  /** {@code PUT /auth/password}. */
  record PasswordChangeRequest(
      @Sensitive String current, @Sensitive @JsonProperty("new") String next) {

    @Override
    public String toString() {
      return "PasswordChangeRequest[]";
    }
  }

  /** {@code POST /setup/admin}. */
  record SetupAdminRequest(
      @Sensitive @JsonProperty("setup_token") String setupToken,
      String username,
      @Sensitive String password,
      @JsonProperty("ip_mode") String ipMode) {

    @Override
    public String toString() {
      return "SetupAdminRequest[username=" + username + ", ipMode=" + ipMode + "]";
    }
  }

  /** The user of a session (14 §8 example 1). */
  record UserView(String id, String username, List<String> roles, List<String> permissions) {

    static UserView of(UserRecord user) {
      return new UserView(
          user.id().toString(),
          user.username(),
          AuthDtos.roles(user),
          user.effectivePermissions().stream().sorted().toList());
    }
  }

  /** {@code POST /auth/login} (14 §4.1, §8 example 1). */
  record LoginResponse(
      UserView user,
      List<String> roles,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("session_expires_at") Instant sessionExpiresAt) {}

  /** {@code POST /auth/refresh}. */
  record RefreshResponse(@JsonProperty("session_expires_at") Instant sessionExpiresAt) {}

  /** {@code GET /auth/me}. */
  record MeResponse(
      UserView user,
      List<String> roles,
      List<String> permissions,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("session_expires_at") Instant sessionExpiresAt,
      @JsonProperty("kiosk_allowed") boolean kioskAllowed) {}

  /** {@code GET /auth/csrf}: the one token a response body carries (14 §4.1). */
  record CsrfResponse(@JsonProperty("csrf_token") String csrfToken) {

    @Override
    public String toString() {
      return "CsrfResponse[]";
    }
  }

  /** {@code GET /setup/status}. */
  record SetupStatusResponse(
      @JsonProperty("needs_setup") boolean needsSetup,
      @JsonProperty("server_version") String serverVersion) {}

  /** {@code POST /setup/admin}. */
  record SetupAdminResponse(UserView user) {}

  // --- 14 §4.14
  // -----------------------------------------------------------------------------------

  /** The kiosk approval of an account (16 §7.5). */
  record KioskSession(Boolean allowed, @JsonProperty("ip_cidrs") List<String> ipCidrs) {}

  /** A user as administrators see it. Never a hash. */
  record AdminUserView(
      String id,
      String username,
      List<String> roles,
      List<String> permissions,
      boolean enabled,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("locked_until") Instant lockedUntil,
      @JsonProperty("kiosk_session") KioskSession kioskSession,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {

    static AdminUserView of(UserRecord user) {
      return new AdminUserView(
          user.id().toString(),
          user.username(),
          AuthDtos.roles(user),
          user.grants().stream().sorted().toList(),
          user.enabled(),
          user.mustChangePassword(),
          millis(user.lockedUntil()),
          new KioskSession(user.kioskAllowed(), user.kioskCidrs()),
          millis(user.createdAt()),
          millis(user.updatedAt()));
    }
  }

  /**
   * {@code POST /admin/users}. {@code password} is optional: without it the server generates one.
   * There is no {@code email}: nothing would store it (no column holds one), so the API does not
   * offer it.
   */
  record CreateUserRequest(
      String username,
      List<String> roles,
      @JsonProperty("must_change_password") Boolean mustChangePassword,
      @Sensitive String password) {

    @Override
    public String toString() {
      return "CreateUserRequest[username=" + username + ", roles=" + roles + "]";
    }
  }

  /** {@code POST /admin/users}: a generated password appears here once and nowhere else. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreateUserResponse(
      AdminUserView user, @Sensitive @JsonProperty("initial_password") String initialPassword) {

    @Override
    public String toString() {
      return "CreateUserResponse[user=" + user + "]";
    }
  }

  /** {@code PATCH /admin/users/{id}}: absent fields stay as they are. */
  record PatchUserRequest(
      List<String> roles,
      Boolean enabled,
      List<String> permissions,
      @JsonProperty("kiosk_session") KioskSession kioskSession) {}

  /** {@code POST /admin/users/{id}/password/reset}: the new password, chosen by the admin. */
  record ResetPasswordRequest(@Sensitive String password) {

    @Override
    public String toString() {
      return "ResetPasswordRequest[]";
    }
  }
}
