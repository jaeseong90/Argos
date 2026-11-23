/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * One {@code xlog} subscription: filters each {@link XlogBundle} of its application with its
 * parameters (kind, service, instance) and queues the push (14 §5.2): the point columns of 14 §4.5
 * ({@code tx_id} and {@code service_id} unsigned decimal strings, ADR-0010 C-175 (1); {@code ts}
 * epoch ms; {@code status} 0/1/2; {@code instance_id}; {@code kind} 1/2/3), {@code dict.service}
 * with the names of the services this subscription has not been told yet, and {@code dropped}.
 *
 * <p>A bundle of more than {@value #MAX_POINTS_PER_PUSH} matching points is split into several
 * pushes, so that one push never fills the connection's send budget. The set of services already
 * named is bounded ({@value #MAX_NAMED_SERVICES}, oldest forgotten first: a forgotten name is only
 * sent again); a service whose name is not known yet is left out of {@code dict} and named by a
 * later push. When the outbox drops a push, its points are added to the next push's {@code dropped}
 * and the names it carried are sent again.
 */
final class XlogSubscription implements SubscriberOutbox.XlogOwner {

  /** Most points of one push. */
  static final int MAX_POINTS_PER_PUSH = 5_000;

  /** Services whose names a subscription remembers having sent. */
  static final int MAX_NAMED_SERVICES = 10_000;

  private final String id;
  private final TopicRequest.Xlog request;
  private final SubscriberOutbox outbox;
  private final JsonMapper json;
  private final AtomicLong dropped = new AtomicLong();

  /** Guarded by {@code this}. */
  private final Map<Long, Boolean> named =
      new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
          return size() > MAX_NAMED_SERVICES;
        }
      };

  XlogSubscription(String id, TopicRequest.Xlog request, SubscriberOutbox outbox, JsonMapper json) {
    this.id = id;
    this.request = request;
    this.outbox = outbox;
    this.json = json;
  }

  /** Filters a bundle and queues its push(es); nothing when no point matches. */
  void onBundle(XlogBundle bundle) {
    List<BusPoint> matching = new ArrayList<>();
    for (BusPoint p : bundle.points()) {
      if (request.matches(p)) {
        matching.add(p);
      }
    }
    for (int from = 0; from < matching.size(); from += MAX_POINTS_PER_PUSH) {
      List<BusPoint> chunk =
          matching.subList(from, Math.min(matching.size(), from + MAX_POINTS_PER_PUSH));
      Set<Long> fresh = new LinkedHashSet<>();
      Map<String, String> names = new TreeMap<>();
      synchronized (this) {
        for (BusPoint p : chunk) {
          long service = p.serviceId();
          if (service == 0 || named.containsKey(service) || fresh.contains(service)) {
            continue;
          }
          String name = bundle.serviceNames().get(service);
          if (name != null) {
            fresh.add(service);
            names.put(Long.toUnsignedString(service), name);
            named.put(service, Boolean.TRUE);
          }
        }
      }
      String data = columns(chunk, bundle.slowThresholdMs());
      String dict = json.writeValueAsString(Map.of("service", names));
      outbox.offerXlog(this, id, bundle.createdAtMs(), data, dict, chunk.size(), fresh);
    }
  }

  /**
   * The push {@code data} of {@code points} as JSON: the seven columns of 14 §4.5 with equal
   * lengths. Written directly (numbers and unsigned decimal strings need no escaping).
   */
  static String columns(List<BusPoint> points, int slowThresholdMs) {
    StringBuilder b = new StringBuilder(32 + points.size() * 80);
    b.append("{\"tx_id\":[");
    each(b, points, p -> b.append('"').append(Long.toUnsignedString(p.txId())).append('"'));
    b.append("],\"ts\":[");
    each(b, points, p -> b.append(p.tsMs()));
    b.append("],\"elapsed_ms\":[");
    each(b, points, p -> b.append(p.elapsedMs()));
    b.append("],\"status\":[");
    each(b, points, p -> b.append(p.status(slowThresholdMs)));
    b.append("],\"instance_id\":[");
    each(b, points, p -> b.append(p.instanceId()));
    b.append("],\"service_id\":[");
    each(b, points, p -> b.append('"').append(Long.toUnsignedString(p.serviceId())).append('"'));
    b.append("],\"kind\":[");
    each(b, points, p -> b.append(p.kind()));
    return b.append("]}").toString();
  }

  private static void each(StringBuilder b, List<BusPoint> points, Consumer<BusPoint> column) {
    for (int i = 0; i < points.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      column.accept(points.get(i));
    }
  }

  @Override
  public void dropped(int points, Set<Long> unsentServices) {
    dropped.addAndGet(points);
    if (!unsentServices.isEmpty()) {
      synchronized (this) {
        unsentServices.forEach(named::remove);
      }
    }
  }

  @Override
  public long takeDropped() {
    return dropped.getAndSet(0);
  }
}
