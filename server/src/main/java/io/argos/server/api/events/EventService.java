/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import io.argos.server.storage.postgres.catalog.CatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Events and collection outages of an application (14 §4.12, §4.4, §4.15; D-18) from PostgreSQL
 * {@code events}. Other endpoints (dashboard, collection health, XLog {@code meta.outages}) use
 * {@link #outages} so that every response shows the same spans.
 */
@Service
public class EventService {

  /** Most events of a list (14 §4.12). */
  public static final int MAX_EVENTS = 1_000;

  /** Most state events read for the outages of one range. */
  static final int MAX_STATE_EVENTS = 20_000;

  private final CatalogRepository catalog;
  private final Clock clock;

  public EventService(CatalogRepository catalog, Clock clock) {
    this.catalog = catalog;
    this.clock = clock;
  }

  /**
   * The events of an application in {@code [from, to)}, newest first, of the API {@code types} (all
   * when empty), at most {@code limit}.
   */
  public List<EventViews.Event> events(
      int appId, Instant from, Instant to, Collection<String> types, int limit) {
    Set<String> stored = new LinkedHashSet<>();
    for (String t : types) {
      stored.addAll(EventViews.storedTypes(t));
    }
    return catalog.events(appId, from, to, stored, limit).stream().map(EventViews::of).toList();
  }

  /**
   * The collection outages of an application in {@code [from, to)} (rules: {@link Outages}), of
   * {@code instances} (all when empty) and of the server.
   */
  public List<EventViews.Outage> outages(
      int appId, Instant from, Instant to, Set<Integer> instances) {
    return Outages.derive(
            catalog.stateEvents(appId, from, to, MAX_STATE_EVENTS),
            from,
            to,
            instances,
            clock.instant())
        .stream()
        .map(EventViews::of)
        .toList();
  }
}
