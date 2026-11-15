/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStoreUnavailableException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The agent token lookup (12 §7.1, 16 §2.4 "조회"): a Caffeine cache keyed by the token's SHA-256
 * hash (never the token), {@value #TTL_SECONDS} s after the load and at most {@value #MAX_ENTRIES}
 * entries, in front of the PostgreSQL {@code agent_tokens} read.
 *
 * <p>Unknown hashes are cached for the same time, so that a flood of bad tokens reaches the
 * database once per distinct token per minute (SEC-05, besides the per-address block of {@link
 * IpAuthFailureLimiter}). Token issuance, rotation and revocation (WO-26) call {@link #invalidate}
 * for the affected hash, so that a change applies at the next call (SEC-04).
 *
 * <p>A database failure on a miss throws {@link TokenStoreUnavailableException} and caches nothing
 * (12 §3.9: the caller answers {@code UNAVAILABLE}); hits keep working while PostgreSQL is down.
 */
public final class TokenCache implements TokenLookup {

  /** 12 §7.1: entries live 60 s. */
  public static final long TTL_SECONDS = 60;

  /** 12 §7.1: at most 10,000 entries. */
  public static final long MAX_ENTRIES = 10_000;

  private final Function<TokenHash, Optional<TokenRecord>> loader;
  private final Cache<TokenHash, Optional<TokenRecord>> cache;

  /** The production cache over {@code loader} (the {@code agent_tokens} read). */
  public TokenCache(Function<TokenHash, Optional<TokenRecord>> loader) {
    this(loader, Duration.ofSeconds(TTL_SECONDS), MAX_ENTRIES, Ticker.systemTicker());
  }

  /** A cache with other bounds and time source (tests). */
  public TokenCache(
      Function<TokenHash, Optional<TokenRecord>> loader,
      Duration ttl,
      long maxEntries,
      Ticker ticker) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxEntries)
            .ticker(ticker)
            .executor(Runnable::run)
            .build();
  }

  @Override
  public Optional<TokenRecord> find(TokenHash hash) {
    Objects.requireNonNull(hash, "hash");
    Optional<TokenRecord> cached = cache.getIfPresent(hash);
    if (cached != null) {
      return cached;
    }
    Optional<TokenRecord> loaded;
    try {
      loaded = Objects.requireNonNull(loader.apply(hash), "loader result");
    } catch (RuntimeException e) {
      throw new TokenStoreUnavailableException("The agent token store cannot be read", e);
    }
    cache.put(hash, loaded);
    return loaded;
  }

  @Override
  public void invalidate(TokenHash hash) {
    cache.invalidate(hash);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }

  /** Entries held (tests). */
  long size() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
