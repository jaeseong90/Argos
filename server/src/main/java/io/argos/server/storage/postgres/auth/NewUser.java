/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import java.util.Objects;
import java.util.UUID;

/**
 * A user to insert. {@link #toString()} never shows the password hash.
 *
 * @param id new UUIDv4
 * @param username login name (validated by the caller)
 * @param passwordHash bcrypt hash
 * @param role role
 * @param mustChangePassword whether the password must be changed at the first login
 */
public record NewUser(
    UUID id,
    String username,
    @Sensitive String passwordHash,
    Role role,
    boolean mustChangePassword) {

  public NewUser {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(role, "role");
  }

  @Override
  public String toString() {
    return "NewUser[id=" + id + ", username=" + username + ", role=" + role + "]";
  }
}
