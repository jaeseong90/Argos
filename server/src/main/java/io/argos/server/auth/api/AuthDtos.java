/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Sensitive;
import io.argos.server.storage.postgres.auth.UserRecord;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of 14 §4.1 and §4.14. Field names are the API's snake_case; times are
 * ISO-8601 UTC in milliseconds (14 §2.4). A record that holds a secret hides it in {@code
 * toString()} ({@link Sensitive}). A request field is {@code @Schema(requiredMode = REQUIRED)}
 * exactly when the server always answers 400 {@code invalid_request} (reason {@code required})
 * without it; a field that is required only under a condition says so in its description (ADR-0010
 * C-179).
 *
 * <p>Closed value sets are enums in the OpenAPI document (ADR-0010 C-191 (1); documentation only,
 * the checks of the handlers are unchanged): {@code ip_mode} ({@code full}, {@code masked}, {@code
 * off}), {@code roles} (the assignable roles as the API writes them, {@code Role#extensionValue()})
 * and {@code permissions} ({@link ArgosPrincipal#KNOWN_PERMISSIONS}); {@code AuthDtosEnumsTest}
 * keeps the annotations equal to the code. A user name is a string an administrator typed: {@code
 * x-argos-untrusted} on the views (16 §9.1).
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
  record LoginRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) String username,
      @Schema(requiredMode = RequiredMode.REQUIRED) @Sensitive String password) {

    @Override
    public String toString() {
      return "LoginRequest[username=" + username + "]";
    }
  }

  /** {@code PUT /auth/password}. */
  record PasswordChangeRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) @Sensitive String current,
      @Schema(requiredMode = RequiredMode.REQUIRED) @Sensitive @JsonProperty("new") String next) {

    @Override
    public String toString() {
      return "PasswordChangeRequest[]";
    }
  }

  /** {@code POST /setup/admin}. */
  record SetupAdminRequest(
      @Schema(
              description =
                  "The setup token: required here or in the X-Argos-Setup-Token header (this"
                      + " field wins when both are given); while setup is open, a missing or"
                      + " wrong token answers 403 forbidden")
          @Sensitive
          @JsonProperty("setup_token")
          String setupToken,
      @Schema(requiredMode = RequiredMode.REQUIRED) String username,
      @Schema(requiredMode = RequiredMode.REQUIRED) @Sensitive String password,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"full", "masked", "off"})
          @JsonProperty("ip_mode")
          String ipMode) {

    @Override
    public String toString() {
      return "SetupAdminRequest[username=" + username + ", ipMode=" + ipMode + "]";
    }
  }

  /** The user of a session (14 §8 example 1). */
  record UserView(
      String id,
      @ArgosUntrusted String username,
      @ArraySchema(schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
          List<String> roles,
      @ArraySchema(schema = @Schema(allowableValues = {ArgosPrincipal.PII_VIEW}))
          List<String> permissions) {

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
      @ArraySchema(schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
          List<String> roles,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("session_expires_at") Instant sessionExpiresAt) {}

  /** {@code POST /auth/refresh}. */
  record RefreshResponse(@JsonProperty("session_expires_at") Instant sessionExpiresAt) {}

  /** {@code GET /auth/me}. */
  record MeResponse(
      UserView user,
      @ArraySchema(schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
          List<String> roles,
      @ArraySchema(schema = @Schema(allowableValues = {ArgosPrincipal.PII_VIEW}))
          List<String> permissions,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("session_expires_at") Instant sessionExpiresAt,
      @JsonProperty("kiosk_allowed") boolean kioskAllowed) {}

  /**
   * One of the caller's live sessions ({@code GET /auth/sessions}, 16 §7.10, ADR-0010 C-136 (3)).
   *
   * @param sessionId the session's id (UUIDv4, stable across refresh rotations)
   * @param current whether it is the session of this request
   * @param createdAt the login
   * @param lastSeenAt the last refresh
   * @param expiresAt when it ends without further use (the earlier of the idle and the absolute
   *     expiry)
   * @param userAgent the browser's {@code User-Agent} at the login (at most 256 characters)
   * @param ip the client address at the login
   * @param kiosk whether it is a long kiosk session (16 §7.5)
   */
  @Schema(name = "OwnSession")
  record OwnSessionView(
      @JsonProperty("session_id") String sessionId,
      boolean current,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("last_seen_at") Instant lastSeenAt,
      @JsonProperty("expires_at") Instant expiresAt,
      @JsonProperty("user_agent") @ArgosUntrusted @Nullable String userAgent,
      @Nullable String ip,
      boolean kiosk) {}

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
      @JsonProperty("server_version") String serverVersion,
      @Schema(
              description =
                  "Whether the server runs in demo mode (argos.demo.enabled, Compose demo profile):"
                      + " the UI shows the demo banner on every screen, also before login (WO-41)")
          @JsonProperty("demo_mode")
          boolean demoMode) {}

  /** {@code POST /setup/admin}. */
  record SetupAdminResponse(UserView user) {}

  // --- 14 §4.14
  // -----------------------------------------------------------------------------------

  /** The kiosk approval of an account (16 §7.5). */
  record KioskSession(Boolean allowed, @JsonProperty("ip_cidrs") List<String> ipCidrs) {}

  /**
   * The kiosk approval in {@code PATCH /admin/users/{id}}: the JSON of {@link KioskSession}, as a
   * request record of its own. A component schema cannot be both a request body's and a response's
   * (ArgosRequestSchemaCustomizer, WO-23 review finding r1-new-1).
   */
  record KioskSessionPatch(
      @Schema(
              description =
                  "Required when kiosk_session is present (400 invalid_request,"
                      + " kiosk_session.allowed required)")
          Boolean allowed,
      @Schema(
              description =
                  "Required, not empty, when allowed is true (400 invalid_request,"
                      + " kiosk_session.ip_cidrs required); ignored when allowed is false")
          @JsonProperty("ip_cidrs")
          List<String> ipCidrs) {}

  /** A user as administrators see it. Never a hash. */
  record AdminUserView(
      String id,
      @ArgosUntrusted String username,
      @ArraySchema(schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
          List<String> roles,
      @ArraySchema(schema = @Schema(allowableValues = {ArgosPrincipal.PII_VIEW}))
          List<String> permissions,
      boolean enabled,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      @JsonProperty("locked_until") @Nullable Instant lockedUntil,
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
      @Schema(requiredMode = RequiredMode.REQUIRED) String username,
      @ArraySchema(
              arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED),
              schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
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
      @Schema(requiredMode = RequiredMode.REQUIRED) AdminUserView user,
      @Sensitive @JsonProperty("initial_password") String initialPassword) {

    @Override
    public String toString() {
      return "CreateUserResponse[user=" + user + "]";
    }
  }

  /** {@code PATCH /admin/users/{id}}: absent fields stay as they are. */
  record PatchUserRequest(
      @ArraySchema(schema = @Schema(allowableValues = {"viewer", "operator", "admin"}))
          List<String> roles,
      Boolean enabled,
      @ArraySchema(schema = @Schema(allowableValues = {ArgosPrincipal.PII_VIEW}))
          List<String> permissions,
      @JsonProperty("kiosk_session") KioskSessionPatch kioskSession) {}

  /** {@code POST /admin/users/{id}/password/reset}: the new password, chosen by the admin. */
  record ResetPasswordRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) @Sensitive String password) {

    @Override
    public String toString() {
      return "ResetPasswordRequest[]";
    }
  }
}
