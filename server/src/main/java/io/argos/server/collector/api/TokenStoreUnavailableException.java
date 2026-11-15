/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

/**
 * The token store (PostgreSQL) cannot be read and the cache has no entry (12 §3.9 "토큰 캐시 미스 + PG
 * 다운"). The collector answers {@code UNAVAILABLE}; the message never contains a token.
 */
public final class TokenStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what failed (no token, no hash)
   * @param cause the database error
   */
  public TokenStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
