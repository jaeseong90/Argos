/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.bootstrap;

import io.argos.server.collector.api.TokenStatus;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * An in-memory {@link AgentTokenStore} with the rules {@code TokenService#register} relies on: the
 * app row lock and limit of {@link #insert}, the unique {@code token_hash} (V001; a duplicate is a
 * {@link DuplicateKeyException} like the PostgreSQL one), newest-first {@link #listByApp}. The SQL
 * itself is proven by {@code DemoBootstrapIT}.
 */
final class MemoryTokenStore extends AgentTokenStore {

  final Map<UUID, StoredToken> rows = new LinkedHashMap<>();
  private final Set<Integer> apps;

  MemoryTokenStore(Set<Integer> apps) {
    super(new DriverManagerDataSource());
    this.apps = Set.copyOf(apps);
  }

  @Override
  public boolean appExists(int appId) {
    return apps.contains(appId);
  }

  @Override
  public WriteResult insert(NewToken token, int maxActive, Instant now) {
    if (!appExists(token.appId())) {
      return new WriteResult(Outcome.NOT_FOUND, 0);
    }
    int active =
        (int)
            rows.values().stream()
                .filter(t -> t.appId() == token.appId())
                .filter(t -> t.status() == TokenStatus.ACTIVE && t.revokedAt() == null)
                .filter(t -> t.expiresAt() == null || t.expiresAt().isAfter(now))
                .count();
    if (active >= maxActive) {
      return new WriteResult(Outcome.LIMIT_REACHED, active);
    }
    if (rows.values().stream().anyMatch(t -> t.hash().equals(token.hash()))) {
      throw new DuplicateKeyException("duplicate key value violates unique constraint");
    }
    rows.put(
        token.id(),
        new StoredToken(
            token.id(),
            token.appId(),
            token.hash(),
            token.prefix(),
            TokenStatus.ACTIVE,
            token.issuedBy(),
            null,
            token.issuedAt(),
            token.expiresAt(),
            null,
            null,
            null,
            null));
    return new WriteResult(Outcome.DONE, active);
  }

  @Override
  public List<StoredToken> listByApp(int appId, int limit) {
    return rows.values().stream()
        .filter(t -> t.appId() == appId)
        .sorted(Comparator.comparing(StoredToken::issuedAt).reversed())
        .limit(limit)
        .toList();
  }

  /** Revokes {@code id} in place. */
  void revoke(UUID id, Instant now) {
    StoredToken t = rows.get(id);
    rows.put(
        id,
        new StoredToken(
            t.id(),
            t.appId(),
            t.hash(),
            t.prefix(),
            TokenStatus.REVOKED,
            t.issuedBy(),
            t.issuedByName(),
            t.issuedAt(),
            t.expiresAt(),
            t.graceUntil(),
            now,
            null,
            t.lastUsedAt()));
  }
}
