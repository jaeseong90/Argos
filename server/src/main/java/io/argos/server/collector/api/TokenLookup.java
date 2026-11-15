/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import java.util.Optional;

/**
 * Agent token lookup by hash (12 §7.1, 16 §2.4 "조회"). Implemented by lane {@code collector}: a
 * Caffeine {@code TokenCache} (60 s, at most 10,000 entries) over the PostgreSQL {@code
 * agent_tokens} repository (a read joins {@code apps} for the name and environment). The cache may
 * also remember unknown hashes for the same time, so that a flood of bad tokens does not reach the
 * database (SEC-05); token issuance (WO-26) then calls {@link #invalidate} for the new hash.
 *
 * <p>The caller decides with {@link TokenRecord#verdict}; this interface does not judge.
 */
public interface TokenLookup {

  /**
   * The token row of {@code hash}, from the cache or the database.
   *
   * @return empty when no row has this hash
   * @throws TokenStoreUnavailableException on a cache miss while PostgreSQL cannot be read (12
   *     §3.9: the Hello is answered {@code UNAVAILABLE} with {@code retry_after_s = 30})
   */
  Optional<TokenRecord> find(TokenHash hash);

  /**
   * Drops {@code hash} from the cache so that the next {@link #find} reads the database: token
   * revocation and rotation (WO-26, 16 §2.4 "폐기 전파", SEC-04).
   */
  void invalidate(TokenHash hash);

  /** Drops every cached entry. */
  void invalidateAll();
}
