/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registration cache of 12 §3.9 ({@link CachingSessionStore}): rows PostgreSQL returned or
 * created answer the app and instance lookups while the database fails; rows it no longer has, and
 * rows never seen, do not.
 */
class CachingSessionStoreTest {

  private final MutableClock clock = new MutableClock();
  private final InMemorySessionStore db = new InMemorySessionStore(clock);
  private final AtomicLong nanos = new AtomicLong();
  private final Ticker ticker = nanos::get;
  private final CachingSessionStore store =
      new CachingSessionStore(db, 100, Duration.ofHours(24), ticker);

  @Test
  @DisplayName("12 §3.9 found and created rows answer while PostgreSQL fails")
  void foundAndCreatedRowsServeTheOutage() {
    AppRef found = db.addApp("orders", "prod");
    assertThat(store.findApp("orders", "prod")).contains(found);
    AppRef created = store.createVariantApp("orders", "stage", found.id(), 8).orElseThrow();
    assertThat(created.registeredFromAppId()).isEqualTo(found.id());
    int instance = store.createInstance(created.id(), "box:1", null);
    assertThat(store.findInstance(found.id(), "missing")).isEmpty();

    db.failing = true;
    assertThat(store.findApp("orders", "prod")).contains(found);
    assertThat(store.findApp("orders", "stage")).contains(created);
    assertThat(store.findInstance(created.id(), "box:1")).isEqualTo(OptionalInt.of(instance));
    assertThatThrownBy(() -> store.findApp("never", "prod"))
        .as("a row never seen needs the database")
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> store.findInstance(found.id(), "missing"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> store.countInstances(found.id()))
        .as("everything else passes through")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("12 §3.9 a row PostgreSQL no longer returns is forgotten")
  void rowNotFoundIsForgotten() {
    AppRef app = db.addApp("orders", "prod");
    assertThat(store.findApp("orders", "prod")).contains(app);
    db.apps.clear();
    assertThat(store.findApp("orders", "prod")).isEmpty();
    db.failing = true;
    assertThatThrownBy(() -> store.findApp("orders", "prod"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("12 §3.9 entries unused for a day are forgotten")
  void idleEntriesExpire() {
    AppRef app = db.addApp("orders", "prod");
    assertThat(store.findApp("orders", "prod")).contains(app);
    nanos.addAndGet(Duration.ofHours(25).toNanos());
    db.failing = true;
    assertThatThrownBy(() -> store.findApp("orders", "prod"))
        .isInstanceOf(IllegalStateException.class);
  }
}
