/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.api.active.ActiveTransactions;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.session.AgentSession;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The {@code data} of an {@code active} push: the schema of {@code GET /apps/{appId}/active} (14
 * §4.9, {@link ActiveTransactions}), built from the same source (the latest snapshot of each live
 * session) with the same rules as {@code api.active.ActiveController}: buckets over all items, the
 * kind and instance filters on the items, at most {@value #MAX_ITEMS} items longest first, names
 * from the dictionary. The instance state comes from the instance state machine (WO-22).
 */
final class ActivePayloads {

  /** Most items of a push (the REST limit of 14 §4.9). */
  static final int MAX_ITEMS = 2_000;

  private ActivePayloads() {}

  private record Pending(AgentSession session, ActiveTransaction tx, String state) {}

  /**
   * The payload for one subscription.
   *
   * @param sessions the live sessions of the application
   * @param kinds kind filter of the items
   * @param instances instance filter, empty for all
   * @param states the state of an instance ({@code UP}, {@code WARN}, ...)
   */
  static ActiveTransactions build(
      int appId,
      Collection<AgentSession> sessions,
      TxKind kinds,
      Set<Integer> instances,
      IntFunction<String> states,
      DictionaryNames names) {
    List<ActiveTransactions.PerInstance> perInstance = new ArrayList<>();
    List<Pending> pending = new ArrayList<>();
    Map<Kind, Set<Long>> keys = new EnumMap<>(Kind.class);
    Instant newest = null;
    List<AgentSession> live = new ArrayList<>(sessions);
    live.sort(Comparator.comparingInt(AgentSession::instanceId));
    for (AgentSession s : live) {
      if (!instances.isEmpty() && !instances.contains(s.instanceId())) {
        continue;
      }
      AgentSession.ActiveSnapshot latest = s.latestSnapshot();
      ActiveTransactionSnapshot snap = latest == null ? null : latest.snapshot();
      Instant at = snap == null ? null : Instant.ofEpochMilli(snap.getSnapshotTsMs());
      if (at != null && (newest == null || at.isAfter(newest))) {
        newest = at;
      }
      long[] buckets = new long[4];
      long max = -1;
      if (snap != null) {
        for (ActiveTransaction t : snap.getItemsList()) {
          long e = t.getElapsedMs();
          buckets[e < 1_000 ? 0 : e < 3_000 ? 1 : e < 8_000 ? 2 : 3]++;
          max = Math.max(max, e);
          if (!kinds.storedValues().contains(kind(t.getKind()))) {
            continue;
          }
          String st = state(t.getState());
          pending.add(new Pending(s, t, st));
          add(keys, Kind.SERVICE, t.getServiceId());
          add(keys, Kind.THREAD, t.getThreadNameId());
          Kind detail = detailKind(st);
          if (detail != null) {
            add(keys, detail, t.getStateNameId());
          }
        }
      }
      perInstance.add(
          new ActiveTransactions.PerInstance(
              s.instanceId(),
              clean(s.instanceName()),
              states.apply(s.instanceId()),
              at,
              snap == null ? 0 : snap.getTotalActive(),
              max < 0 ? null : max,
              List.of(buckets[0], buckets[1], buckets[2], buckets[3]),
              snap != null && snap.getTruncated()));
    }
    pending.sort(
        Comparator.comparingLong((Pending p) -> p.tx().getElapsedMs())
            .reversed()
            .thenComparingInt(p -> p.session().instanceId())
            .thenComparing(p -> Long.toUnsignedString(p.tx().getTxId())));
    List<Pending> shown = pending.size() > MAX_ITEMS ? pending.subList(0, MAX_ITEMS) : pending;
    Map<Kind, Map<Long, String>> resolved = new EnumMap<>(Kind.class);
    keys.forEach((k, ids) -> resolved.put(k, names.names(appId, k, ids)));
    List<ActiveTransactions.Item> items = new ArrayList<>(shown.size());
    for (Pending p : shown) {
      ActiveTransaction t = p.tx();
      Kind detail = detailKind(p.state());
      String userId = t.getUserId();
      items.add(
          new ActiveTransactions.Item(
              p.session().instanceId(),
              Long.toUnsignedString(t.getTxId()),
              name(resolved, Kind.SERVICE, t.getServiceId()),
              t.getServiceId() == 0 ? null : Long.toUnsignedString(t.getServiceId()),
              t.getElapsedMs(),
              p.state(),
              detail == null ? null : name(resolved, detail, t.getStateNameId()),
              name(resolved, Kind.THREAD, t.getThreadNameId()),
              t.getVirtualThread(),
              userId.isEmpty() ? null : userId,
              p.session().userIdMode().storedName(),
              address(t.getRemoteIp().toByteArray()),
              p.session().ipMode().storedName(),
              kind(t.getKind()),
              Instant.ofEpochMilli(t.getStartTsMs())));
    }
    return new ActiveTransactions(newest, perInstance, items);
  }

  static String state(ActiveState state) {
    return switch (state) {
      case IN_SQL -> "SQL";
      case IN_HTTP -> "HTTP";
      case IN_DBC_WAIT -> "DBC";
      case IN_ASYNC -> "ASYNC";
      default -> "METHOD";
    };
  }

  static String kind(TransactionKind kind) {
    return switch (kind) {
      case BATCH -> "BATCH";
      case MESSAGE -> "MESSAGE";
      default -> "HTTP";
    };
  }

  private static Kind detailKind(String state) {
    return switch (state) {
      case "SQL" -> Kind.SQL;
      case "HTTP" -> Kind.HOST;
      case "DBC" -> Kind.DB_TARGET;
      default -> null;
    };
  }

  private static void add(Map<Kind, Set<Long>> keys, Kind kind, long id) {
    if (id != 0) {
      keys.computeIfAbsent(kind, k -> new HashSet<>()).add(id);
    }
  }

  private static String name(Map<Kind, Map<Long, String>> resolved, Kind kind, long id) {
    if (id == 0) {
      return null;
    }
    Map<Long, String> m = resolved.get(kind);
    String text = m == null ? null : m.get(id);
    return clean(text == null ? DictionaryNames.unknown(id) : text);
  }

  private static String clean(String raw) {
    return raw == null ? null : UntrustedStrings.name(raw).value();
  }

  private static String address(byte[] bytes) {
    if (bytes.length != 4 && bytes.length != 16) {
      return null;
    }
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
