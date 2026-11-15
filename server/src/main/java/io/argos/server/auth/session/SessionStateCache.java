/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SessionStateRow;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The session record behind every API request (16 §7.3 check point 1, 12 §7.2, DSN-12-10, ADR-0010
 * C-106 (4)): the state of a {@code sid} and of its owner, cached for {@value #TTL_SECONDS}
 * seconds, bounded, and dropped at once by {@link SessionRevokedEvent}. A change made in this
 * process is therefore visible at the next request; a change made elsewhere (a command-line tool,
 * another process on the same database) within the TTL.
 *
 * <p>The database read happens outside the cache's internal locks (a blocking read inside a compute
 * would pin a carrier thread of the virtual-thread executor on JDK 21). To keep a read that started
 * before a revocation from installing the old state after it, every invalidation advances {@link
 * #generation} first; a loader that sees another generation after its {@code put} removes the entry
 * again.
 */
@Component
public class SessionStateCache {

  /** Lifetime of an entry (DSN-12-10). */
  static final long TTL_SECONDS = 5;

  /** Upper bound of entries: sessions active within the TTL. */
  static final long MAX_ENTRIES = 100_000;

  /** A cached answer: the row, or none when the session does not exist (any more). */
  private record Entry(SessionStateRow row) {}

  private final SessionRepository sessions;
  private final Cache<UUID, Entry> cache =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS))
          .maximumSize(MAX_ENTRIES)
          .build();
  private final AtomicLong generation = new AtomicLong();

  public SessionStateCache(SessionRepository sessions) {
    this.sessions = sessions;
  }

  /** The state of a session and of its owner, empty when the session does not exist. */
  public Optional<SessionStateRow> get(UUID sid) {
    Entry entry = cache.getIfPresent(sid);
    if (entry != null) {
      return Optional.ofNullable(entry.row());
    }
    long before = generation.get();
    Optional<SessionStateRow> row = sessions.loadState(sid);
    cache.put(sid, new Entry(row.orElse(null)));
    if (generation.get() != before) {
      cache.invalidate(sid);
    }
    return row;
  }

  /** Forgets one session. */
  public void invalidate(UUID sid) {
    generation.incrementAndGet();
    cache.invalidate(sid);
  }

  /** Every row of a revoked session is refused from the next request on. */
  @EventListener
  public void onRevoked(SessionRevokedEvent event) {
    generation.incrementAndGet();
    cache.invalidateAll(event.sessionIds());
  }
}
