/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Creates users of the server under test directly in PostgreSQL (as {@code argos_app}, like the
 * server does), with passwords generated at run time (never a literal in test code, gitleaks 0) and
 * stored as bcrypt cost 12 hashes ({@code $2a$12$}, SEC-48).
 *
 * <pre>{@code
 * TestUsers users = new TestUsers(env);
 * TestUser admin = users.create(Role.ADMIN);
 * TestUser kiosk = users.create(Role.VIEWER, u -> u.kiosk("10.0.0.0/8"));
 * client.login(admin.username(), admin.password());
 * }</pre>
 *
 * Generated names start with {@code t-} and are unique per call; generated passwords are 32
 * base64url characters that never contain the user name or {@code argos} (16 §7.9).
 */
public final class TestUsers {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12, RANDOM);

  private final ServerTestEnvironment env;

  public TestUsers(ServerTestEnvironment env) {
    this.env = Objects.requireNonNull(env, "env");
  }

  /**
   * A created user. {@link #toString()} never shows the password.
   *
   * @param id {@code users.id}
   * @param username user name
   * @param password the plain password (generated at run time)
   * @param role role
   */
  public record TestUser(UUID id, String username, @Sensitive String password, Role role) {

    @Override
    public String toString() {
      return "TestUser[" + username + ", " + role + ", id=" + id + "]";
    }
  }

  /** Options of a new user (defaults: enabled, no forced password change, no kiosk). */
  public static final class Options {
    private String username;
    private String password;
    private boolean enabled = true;
    private boolean mustChangePassword;
    private boolean kioskAllowed;
    private final List<String> kioskCidrs = new ArrayList<>();
    private final List<String> permissions = new ArrayList<>();

    private Options() {}

    /** A fixed user name instead of a generated one. */
    public Options username(String value) {
      this.username = value;
      return this;
    }

    /** A given password instead of a generated one (boundary tests). */
    public Options password(@Sensitive String value) {
      this.password = value;
      return this;
    }

    /** {@code users.enabled}. */
    public Options enabled(boolean value) {
      this.enabled = value;
      return this;
    }

    /** {@code users.must_change_password}. */
    public Options mustChangePassword(boolean value) {
      this.mustChangePassword = value;
      return this;
    }

    /** {@code kiosk_allowed = true} with these {@code kiosk_ip_allowlist} entries (16 §7.5). */
    public Options kiosk(String... cidrs) {
      this.kioskAllowed = true;
      this.kioskCidrs.addAll(List.of(cidrs));
      return this;
    }

    /** A {@code user_permissions} grant, e.g. {@code pii.view}. */
    public Options permission(String value) {
      this.permissions.add(value);
      return this;
    }
  }

  /** A user with a generated name and password. */
  public TestUser create(Role role) {
    return create(role, o -> {});
  }

  /** A user with options. */
  public TestUser create(Role role, Consumer<Options> options) {
    if (!role.assignable()) {
      throw new IllegalArgumentException("not a user role: " + role);
    }
    Options o = new Options();
    options.accept(o);
    String username = o.username != null ? o.username : newUsername();
    String password = o.password != null ? o.password : newPassword(username);
    UUID id = UUID.randomUUID();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement insert =
            c.prepareStatement(
                "INSERT INTO argos.users (id, username, password_hash, role, enabled,"
                    + " must_change_password, kiosk_allowed, kiosk_ip_allowlist)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS cidr[]))")) {
      insert.setObject(1, id);
      insert.setString(2, username);
      insert.setString(3, BCRYPT.encode(password));
      insert.setString(4, role.name());
      insert.setBoolean(5, o.enabled);
      insert.setBoolean(6, o.mustChangePassword);
      insert.setBoolean(7, o.kioskAllowed);
      if (o.kioskCidrs.isEmpty()) {
        insert.setNull(8, Types.ARRAY);
      } else {
        Array cidrs = c.createArrayOf("varchar", o.kioskCidrs.toArray());
        insert.setArray(8, cidrs);
      }
      insert.executeUpdate();
      for (String permission : o.permissions) {
        grant(c, id, permission);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot create test user " + username, e);
    }
    return new TestUser(id, username, password, role);
  }

  /** Grants {@code permission} (e.g. {@code pii.view}) to an existing user. */
  public void grant(TestUser user, String permission) {
    try (Connection c = env.connect(PgAccount.APP)) {
      grant(c, user.id(), permission);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot grant " + permission + " to " + user.username(), e);
    }
  }

  private static void grant(Connection c, UUID userId, String permission) throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO argos.user_permissions (user_id, permission) VALUES (?, ?)")) {
      insert.setObject(1, userId);
      insert.setString(2, permission);
      insert.executeUpdate();
    }
  }

  /** A unique user name: {@code t-} and 12 random lower-case letters and digits. */
  public static String newUsername() {
    String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
    StringBuilder out = new StringBuilder("t-");
    for (int i = 0; i < 12; i++) {
      out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  /**
   * A password generated at run time: 24 random bytes as 32 base64url characters, never containing
   * {@code username} or {@code argos} (case-insensitive), so it passes the password policy.
   */
  public static String newPassword(String username) {
    while (true) {
      byte[] bytes = new byte[24];
      RANDOM.nextBytes(bytes);
      String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      String lower = candidate.toLowerCase(Locale.ROOT);
      if (!lower.contains("argos")
          && (username == null || !lower.contains(username.toLowerCase(Locale.ROOT)))) {
        return candidate;
      }
    }
  }
}
