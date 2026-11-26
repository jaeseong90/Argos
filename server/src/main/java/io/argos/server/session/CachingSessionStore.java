/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The session registration cache of 12 §3.9 ("PostgreSQL 다운": collection goes on, new sessions are
 * accepted on a token cache hit): a {@link SessionStore} decorator that remembers the apps and the
 * instances that PostgreSQL returned or created, so that a known instance of a known app can start
 * a session while PostgreSQL cannot be read.
 *
 * <ul>
 *   <li>{@link #findApp} and {@link #findInstance} read PostgreSQL; a row found or created is
 *       remembered, a row not found is forgotten. When the read fails and the key is remembered,
 *       the remembered value is returned instead of the failure; otherwise the failure is thrown (a
 *       new app or instance needs PostgreSQL: the caller answers {@code UNAVAILABLE}).
 *   <li>Every other method passes through, failures included.
 * </ul>
 *
 * <p>Bounded: at most {@value #MAX_ENTRIES} apps and as many instances, each forgotten after {@link
 * #IDLE_EXPIRY} without use. No database access of its own (SRV-01). Thread-safe.
 */
public final class CachingSessionStore implements SessionStore {

  private static final Logger LOG = LoggerFactory.getLogger(CachingSessionStore.class);

  /** Upper bound of remembered apps, and of remembered instances. */
  public static final long MAX_ENTRIES = 100_000;

  /** Entries unused for this long are forgotten. */
  public static final Duration IDLE_EXPIRY = Duration.ofHours(24);

  /** At most one WARN line per this interval while PostgreSQL fails. */
  static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final SessionStore delegate;
  private final Cache<AppKey, AppRef> apps;
  private final Cache<InstanceKey, Integer> instances;
  private final Ticker ticker;
  private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

  /** The production cache over {@code delegate}. */
  public CachingSessionStore(SessionStore delegate) {
    this(delegate, MAX_ENTRIES, IDLE_EXPIRY, Ticker.systemTicker());
  }

  /** A cache with other bounds and time source (tests). */
  CachingSessionStore(SessionStore delegate, long maxEntries, Duration idle, Ticker ticker) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.apps =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterAccess(idle)
            .ticker(ticker)
            .executor(Runnable::run)
            .build();
    this.instances =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterAccess(idle)
            .ticker(ticker)
            .executor(Runnable::run)
            .build();
  }

  @Override
  public Optional<AppRef> findApp(String name, String env) {
    AppKey key = new AppKey(name, env);
    Optional<AppRef> found;
    try {
      found = delegate.findApp(name, env);
    } catch (RuntimeException e) {
      AppRef cached = apps.getIfPresent(key);
      if (cached == null) {
        throw e;
      }
      warn(e);
      return Optional.of(cached);
    }
    if (found.isPresent()) {
      apps.put(key, found.get());
    } else {
      apps.invalidate(key);
    }
    return found;
  }

  @Override
  public Optional<AppRef> createVariantApp(
      String name, String env, int homeAppId, int maxVariants) {
    Optional<AppRef> created = delegate.createVariantApp(name, env, homeAppId, maxVariants);
    created.ifPresent(app -> apps.put(new AppKey(name, env), app));
    return created;
  }

  @Override
  public void recordPendingRegistration(
      String name, String env, String host, int homeAppId, Instant at) {
    delegate.recordPendingRegistration(name, env, host, homeAppId, at);
  }

  @Override
  public OptionalInt findInstance(int appId, String name) {
    InstanceKey key = new InstanceKey(appId, name);
    OptionalInt found;
    try {
      found = delegate.findInstance(appId, name);
    } catch (RuntimeException e) {
      Integer cached = instances.getIfPresent(key);
      if (cached == null) {
        throw e;
      }
      warn(e);
      return OptionalInt.of(cached);
    }
    if (found.isPresent()) {
      instances.put(key, found.getAsInt());
    } else {
      instances.invalidate(key);
    }
    return found;
  }

  /** Not cached: a failure reaches the caller (the start rule treats it as unknown). */
  @Override
  public OptionalLong instanceStartedAtMs(int instanceId) {
    return delegate.instanceStartedAtMs(instanceId);
  }

  @Override
  public int countInstances(int appId) {
    return delegate.countInstances(appId);
  }

  @Override
  public int countInstancesCreatedSince(int appId, Instant since) {
    return delegate.countInstancesCreatedSince(appId, since);
  }

  @Override
  public int createInstance(int appId, String name, InstanceMetadata metadata) {
    int id = delegate.createInstance(appId, name, metadata);
    instances.put(new InstanceKey(appId, name), id);
    return id;
  }

  @Override
  public boolean updateInstanceMetadata(int instanceId, InstanceMetadata metadata) {
    return delegate.updateInstanceMetadata(instanceId, metadata);
  }

  @Override
  public UpdateResult applyInstanceUpdate(int instanceId, InstanceChanges changes) {
    return delegate.applyInstanceUpdate(instanceId, changes);
  }

  @Override
  public void touchToken(UUID tokenId, Instant now) {
    delegate.touchToken(tokenId, now);
  }

  private void warn(RuntimeException e) {
    long now = ticker.read();
    long last = lastWarn.get();
    if (last != Long.MIN_VALUE && now - last < WARN_INTERVAL_NANOS) {
      return;
    }
    if (lastWarn.compareAndSet(last, now)) {
      LOG.warn(
          "PostgreSQL cannot be read; a known app and instance start their session from the"
              + " registration cache (12 §3.9); further occurrences are not logged for a minute:"
              + " {}",
          e.toString());
    }
  }

  private record AppKey(String name, String env) {}

  private record InstanceKey(int appId, String name) {}
}
