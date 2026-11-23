/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.AlertNotification.Group;
import io.argos.server.alert.api.AlertNotification.Item;
import io.argos.server.alert.api.AlertNotification.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Groups notifications per (application, environment, severity) (12 §5.4, F-S17 S) with the
 * leading-edge rule of the WO-25 plan §3.3 (PM question 1): when the group has no open window, an
 * item is sent at once and a window of {@code argos.alert.group-window} (30 s) opens; items that
 * arrive while it is open are sent together when it closes, at most {@value
 * AlertNotification#MAX_ITEMS} items plus "and N more". So a condition met at an evaluation is
 * requested within the evaluation interval (N-23: 30 s), and a burst becomes one message.
 *
 * <p>Not thread-safe: the alert engine's thread only.
 */
public final class Grouper {

  private final long windowMs;
  private final Map<Group, Window> windows = new LinkedHashMap<>();

  public Grouper(Duration window) {
    this.windowMs = Math.max(0, window.toMillis());
  }

  /**
   * Offers one item.
   *
   * @param channels the item's channels (its severity's channels of the rule)
   * @return the notification to send now (leading edge), or null when the item waits for the
   *     window's end
   */
  public AlertNotification offer(Group group, Item item, List<UUID> channels, long nowMs) {
    Window w = windows.get(group);
    if (w != null && w.endsMs > nowMs) {
      w.items.add(item);
      w.channels.addAll(channels);
      return null;
    }
    windows.put(group, new Window(nowMs + windowMs));
    return build(group, List.of(item), new ArrayList<>(channels));
  }

  /** The notifications of the windows that closed at {@code nowMs}, in group order. */
  public List<AlertNotification> flush(long nowMs) {
    List<AlertNotification> out = new ArrayList<>();
    for (Iterator<Map.Entry<Group, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<Group, Window> e = it.next();
      Window w = e.getValue();
      if (w.endsMs > nowMs) {
        continue;
      }
      it.remove();
      if (!w.items.isEmpty()) {
        out.add(build(e.getKey(), w.items, new ArrayList<>(w.channels)));
      }
    }
    return out;
  }

  /** Groups with an open window. */
  public int openWindows() {
    return windows.size();
  }

  /**
   * The notification of {@code items} (oldest first): at most 20 of them plus the count left out.
   */
  static AlertNotification build(Group group, List<Item> items, List<UUID> channels) {
    List<Item> shown =
        items.size() > AlertNotification.MAX_ITEMS
            ? items.subList(0, AlertNotification.MAX_ITEMS)
            : items;
    int omitted = items.size() - shown.size();
    Kind kind = shown.get(0).kind();
    for (Item i : items) {
      if (rank(i.kind()) > rank(kind)) {
        kind = i.kind();
      }
    }
    StringBuilder body = new StringBuilder();
    for (Item i : shown) {
      if (body.length() > 0) {
        body.append('\n');
      }
      body.append(i.message());
    }
    if (omitted > 0) {
      body.append('\n').append("and ").append(omitted).append(" more");
    }
    List<UUID> eventIds = new ArrayList<>();
    for (Item i : items) {
      eventIds.add(i.eventId());
    }
    return new AlertNotification(
        group,
        kind,
        shown,
        omitted,
        title(group, kind, shown, items.size()),
        body.toString(),
        AlertNotification.Recipients.of(channels),
        eventIds);
  }

  static String title(Group group, Kind kind, List<Item> shown, int total) {
    StringBuilder t = new StringBuilder("[").append(group.severity().name()).append("] ");
    if (total == 1) {
      Item i = shown.get(0);
      t.append(kind.name())
          .append(": ")
          .append(Objects.toString(i.variables().get("rule"), "alert"));
    } else {
      t.append(total).append(" alerts");
    }
    Set<String> where = new LinkedHashSet<>();
    if (group.appName() != null) {
      where.add(group.appName());
    }
    if (group.env() != null) {
      where.add(group.env());
    }
    if (group.appId() == null) {
      where.add("server");
    }
    if (!where.isEmpty()) {
      t.append(" - ").append(String.join(" ", where));
    }
    return t.toString();
  }

  private static int rank(Kind k) {
    return switch (k) {
      case RESOLVED -> 0;
      case RENOTIFY -> 1;
      case FIRING -> 2;
      case ESCALATED -> 3;
      case SELF -> 4;
    };
  }

  private static final class Window {
    final long endsMs;
    final List<Item> items = new ArrayList<>();
    final Set<UUID> channels = new LinkedHashSet<>();

    Window(long endsMs) {
      this.endsMs = endsMs;
    }
  }
}
