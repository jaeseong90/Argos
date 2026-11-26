/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.instance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.session.AppRef;
import io.argos.server.session.InstanceChanges;
import io.argos.server.session.InstanceMetadata;
import io.argos.server.session.SessionStore;
import io.argos.server.storage.postgres.app.AppRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository;
import io.argos.server.storage.postgres.token.AgentTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * {@link SessionStore} over PostgreSQL: {@code apps}, {@code instances} and the {@code
 * agent_tokens.last_used_at} write of a session start (WO-21), and the approval queue {@code
 * pending_registrations} (WO-22). A context without the {@link PendingRegistrationRepository} bean
 * (a collector test context that registers its repositories one by one) queues nothing.
 */
@Component
public class PostgresSessionStore implements SessionStore {

  /** 16 §2.2: {@code last_used_at} is written at most once per this interval per token. */
  static final Duration TOKEN_TOUCH_INTERVAL = Duration.ofMinutes(5);

  private final AppRepository apps;
  private final InstanceRepository instances;
  private final AgentTokenRepository tokens;
  private final Clock clock;
  private final PendingRegistrationRepository pending;

  /** Tokens touched recently (bounded): saves the UPDATE round trip within the interval. */
  private final Cache<UUID, Instant> touched =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(TOKEN_TOUCH_INTERVAL).build();

  public PostgresSessionStore(
      AppRepository apps,
      InstanceRepository instances,
      AgentTokenRepository tokens,
      Clock clock,
      ObjectProvider<PendingRegistrationRepository> pending) {
    this.apps = apps;
    this.instances = instances;
    this.tokens = tokens;
    this.clock = clock;
    this.pending = pending.getIfAvailable();
  }

  @Override
  public Optional<AppRef> findApp(String name, String env) {
    return apps.find(name, env);
  }

  @Override
  public Optional<AppRef> createVariantApp(
      String name, String env, int homeAppId, int maxVariants) {
    return apps.createVariant(name, env, homeAppId, maxVariants);
  }

  @Override
  public void recordPendingRegistration(
      String name, String env, String host, int homeAppId, Instant at) {
    if (pending != null) {
      pending.record(name, env, host, homeAppId, at);
    }
  }

  @Override
  public OptionalInt findInstance(int appId, String name) {
    return instances.find(appId, name);
  }

  @Override
  public OptionalLong instanceStartedAtMs(int instanceId) {
    return instances.startedAtMs(instanceId);
  }

  @Override
  public int countInstances(int appId) {
    return instances.countActive(appId);
  }

  @Override
  public int countInstancesCreatedSince(int appId, Instant since) {
    return instances.countCreatedSince(appId, since);
  }

  @Override
  public int createInstance(int appId, String name, InstanceMetadata metadata) {
    OptionalInt created = instances.insert(appId, name, metadata, clock.instant());
    if (created.isPresent()) {
      return created.getAsInt();
    }
    int existing =
        instances
            .find(appId, name)
            .orElseThrow(() -> new IllegalStateException("Instance row missing after a conflict"));
    instances.updateMetadata(existing, metadata);
    return existing;
  }

  @Override
  public boolean updateInstanceMetadata(int instanceId, InstanceMetadata metadata) {
    return instances.updateMetadata(instanceId, metadata);
  }

  @Override
  public UpdateResult applyInstanceUpdate(int instanceId, InstanceChanges changes) {
    if (changes.isEmpty()) {
      return UpdateResult.UNCHANGED;
    }
    try {
      return instances.applyUpdate(instanceId, changes)
          ? UpdateResult.CHANGED
          : UpdateResult.UNCHANGED;
    } catch (DuplicateKeyException e) {
      InstanceChanges rest = changes.withoutName();
      if (!rest.isEmpty()) {
        instances.applyUpdate(instanceId, rest);
      }
      return UpdateResult.NAME_CONFLICT;
    }
  }

  @Override
  public void touchToken(UUID tokenId, Instant now) {
    Instant last = touched.getIfPresent(tokenId);
    if (last != null && last.isAfter(now.minus(TOKEN_TOUCH_INTERVAL))) {
      return;
    }
    tokens.touchLastUsed(tokenId, now, TOKEN_TOUCH_INTERVAL);
    touched.put(tokenId, now);
  }
}
