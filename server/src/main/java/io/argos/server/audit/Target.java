/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.util.UUID;

/**
 * What it was done to (16 §11.2: {@code target_type}, {@code target_id}, {@code target_name}).
 *
 * @param type e.g. {@code user}, {@code session}, {@code system}; null for none
 * @param id identifier (UUID, token prefix, ...), or null
 * @param name display name (user name, ...), or null
 */
public record Target(String type, String id, String name) {

  /** No target. */
  public static Target none() {
    return new Target(null, null, null);
  }

  /** A user. */
  public static Target user(UUID userId, String username) {
    return new Target("user", userId == null ? null : userId.toString(), username);
  }

  /** A session ({@code user_sessions.id}). */
  public static Target session(UUID sid) {
    return new Target("session", sid == null ? null : sid.toString(), null);
  }
}
