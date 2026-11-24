/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.salt;

import java.util.Optional;

/**
 * The {@code hashed} user id of an application (16 §6.3, ADR-0010 C-125 (2), C-152): {@code
 * base64url(HMAC-SHA256(salt, user_id))} without padding, its first 16 characters, with the
 * application's 32-byte salt ({@code apps.user_id_salt_enc}, envelope encrypted with the AAD {@code
 * apps:user_id_salt_enc:<id>}). The agent computes the same value with the salt it receives in
 * {@code ServerHello.user_id_salt}, so a search or a deletion by a plain user id finds the rows of
 * a {@code hashed} application.
 *
 * <p>The salt is created the first time it is needed ({@code UPDATE apps SET user_id_salt_enc = ?
 * WHERE id = ? AND user_id_salt_enc IS NULL}). Neither the salt nor the user id is ever logged.
 */
public interface UserIdHasher {

  /** Characters of a hashed user id (16 §6.3). */
  int HASH_CHARS = 16;

  /**
   * The hashed form of a user id of one application.
   *
   * @param appId the application id
   * @param normalizedUserId the user id as {@code QueryLimits.userIdFilter} normalises it (control
   *     characters removed, at most 128 characters, trimmed; ADR-0010 C-163 (2))
   * @return the 16-character hash, or empty when the application does not exist (or is deleted) or
   *     its salt cannot be read (no master key for its key id)
   */
  Optional<String> hashed(int appId, String normalizedUserId);
}
