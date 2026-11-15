/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** A {@link SessionStore} in memory with the PostgreSQL semantics the checks rely on (tests). */
final class InMemorySessionStore implements SessionStore {

  record Instance(int id, int appId, String name, InstanceMetadata metadata, Instant firstSeen) {}

  final Map<String, AppRef> apps = new HashMap<>();
  final List<Instance> instances = new ArrayList<>();
  final List<UUID> touched = new ArrayList<>();
  int metadataWrites;
  int updateWrites;
  boolean failing;
  private int nextApp = 1;
  private int nextInstance = 1;
  private final java.time.Clock clock;

  InMemorySessionStore(java.time.Clock clock) {
    this.clock = clock;
  }

  AppRef addApp(String name, String env) {
    AppRef app = new AppRef(nextApp++, name, env);
    apps.put(name + "/" + env, app);
    return app;
  }

  void addInstance(int appId, String name, Instant firstSeen) {
    instances.add(new Instance(nextInstance++, appId, name, null, firstSeen));
  }

  private void check() {
    if (failing) {
      throw new IllegalStateException("database down");
    }
  }

  @Override
  public synchronized Optional<AppRef> findApp(String name, String env) {
    check();
    return Optional.ofNullable(apps.get(name + "/" + env));
  }

  /** The PostgreSQL semantics of {@code AppRepository.createVariant} (one lock: this store). */
  @Override
  public synchronized Optional<AppRef> createVariantApp(
      String name, String env, int homeAppId, int maxVariants) {
    check();
    if (apps.values().stream().noneMatch(a -> a.id() == homeAppId)) {
      return Optional.empty();
    }
    AppRef existing = apps.get(name + "/" + env);
    if (existing != null) {
      return Optional.of(existing);
    }
    long variants =
        apps.values().stream().filter(a -> a.registeredFromAppId() == homeAppId).count();
    if (variants >= maxVariants) {
      return Optional.empty();
    }
    AppRef created = new AppRef(nextApp++, name, env, homeAppId);
    apps.put(name + "/" + env, created);
    return Optional.of(created);
  }

  /** An app registered as an environment variant of {@code homeAppId} (C-155). */
  AppRef addVariantOf(String name, String env, int homeAppId) {
    AppRef app = new AppRef(nextApp++, name, env, homeAppId);
    apps.put(name + "/" + env, app);
    return app;
  }

  @Override
  public synchronized OptionalInt findInstance(int appId, String name) {
    check();
    return instances.stream()
        .filter(i -> i.appId() == appId && i.name().equals(name))
        .mapToInt(Instance::id)
        .findFirst();
  }

  @Override
  public synchronized int countInstances(int appId) {
    check();
    return (int) instances.stream().filter(i -> i.appId() == appId).count();
  }

  @Override
  public synchronized int countInstancesCreatedSince(int appId, Instant since) {
    check();
    return (int)
        instances.stream()
            .filter(i -> i.appId() == appId && !i.firstSeen().isBefore(since))
            .count();
  }

  @Override
  public synchronized int createInstance(int appId, String name, InstanceMetadata metadata) {
    check();
    OptionalInt existing = findInstance(appId, name);
    if (existing.isPresent()) {
      return existing.getAsInt();
    }
    Instance created = new Instance(nextInstance++, appId, name, metadata, clock.instant());
    instances.add(created);
    return created.id();
  }

  @Override
  public synchronized boolean updateInstanceMetadata(int instanceId, InstanceMetadata metadata) {
    check();
    for (int i = 0; i < instances.size(); i++) {
      Instance current = instances.get(i);
      if (current.id() == instanceId && !metadata.equals(current.metadata())) {
        instances.set(
            i,
            new Instance(
                current.id(), current.appId(), current.name(), metadata, current.firstSeen()));
        metadataWrites++;
        return true;
      }
    }
    return false;
  }

  @Override
  public synchronized UpdateResult applyInstanceUpdate(int instanceId, InstanceChanges changes) {
    check();
    if (changes.isEmpty()) {
      return UpdateResult.UNCHANGED;
    }
    updateWrites++;
    return UpdateResult.CHANGED;
  }

  @Override
  public synchronized void touchToken(UUID tokenId, Instant now) {
    touched.add(tokenId);
  }
}
