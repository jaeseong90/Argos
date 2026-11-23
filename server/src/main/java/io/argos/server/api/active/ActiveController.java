/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.active;

import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.api.transactions.Ids;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The running transactions of an application (14 §4.9, F-U04): the latest active-transaction
 * snapshot of every live session in the collector's in-memory {@link SessionRegistry} (WO-21,
 * 5-second snapshots), filtered, with names. Only instances with a live session are listed, each
 * with its state from the instance state machine ({@link InstanceStates}, 12 §4.2; ADR-0010 C-218
 * (4)), the same value as in {@code GET /apps/{appId}/instances}.
 */
@RestController
@RequiresRole(Role.VIEWER)
public class ActiveController {

  /** Most items of a response (14 §4.9). */
  public static final int MAX_ITEMS = 2_000;

  private static final Set<String> STATES = Set.of("SQL", "HTTP", "DBC", "METHOD", "ASYNC");

  private final SessionRegistry sessions;
  private final DictionaryNames names;
  private final AppDirectory directory;
  private final AppAccessPolicy access;
  private final InstanceStates states;

  public ActiveController(
      SessionRegistry sessions,
      DictionaryNames names,
      AppDirectory directory,
      AppAccessPolicy access,
      InstanceStates states) {
    this.sessions = sessions;
    this.names = names;
    this.directory = directory;
    this.access = access;
    this.states = states;
  }

  /** The running transactions (14 §4.9). */
  @GetMapping(value = "/api/v1/apps/{appId}/active", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "active_get",
      summary = "Running transactions of an application",
      description =
          "The latest 5-second snapshot of every live agent session (memory). At most 2,000"
              + " items, longest first (meta.truncated). per_instance lists only the instances"
              + " with a live session, each with its instance state (UP, WARN, STALE, DOWN) as in"
              + " GET /apps/{appId}/instances.")
  @ArgosLimits(rowsMax = MAX_ITEMS)
  @ArgosSource({"memory:active_snapshots", "postgres:dictionary"})
  public ResponseEnvelope<ActiveTransactions> active(
      @PathVariable("appId") int appId,
      @Parameter(description = "Instances, repeatable, at most 100")
          @RequestParam(value = "instance", required = false)
          List<Integer> instance,
      @Parameter(description = "HTTP (default), BATCH, MESSAGE, ALL")
          @RequestParam(value = "kind", required = false)
          String kind,
      @Parameter(description = "SQL, HTTP, DBC, METHOD, ASYNC")
          @RequestParam(value = "state", required = false)
          String state,
      @RequestParam(value = "min_elapsed_ms", required = false) Long minElapsedMs) {
    access.check(ArgosPrincipal.current().orElse(null), appId);
    if (appId <= 0 || directory.app(appId).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND).detail("appId", "unknown");
    }
    Set<Integer> instances =
        new HashSet<>(QueryLimits.array("instance", instance, QueryLimits.MAX_ARRAY_VALUES));
    QueryLimits.TxKind kinds = QueryLimits.kind(kind);
    String wantedState = state == null ? null : state.strip().toUpperCase(Locale.ROOT);
    if (wantedState != null && !STATES.contains(wantedState)) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("state", "unsupported_value");
    }
    long minElapsed = minElapsedMs == null ? 0 : minElapsedMs;
    if (minElapsed < 0) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("min_elapsed_ms", "out_of_range");
    }

    List<ActiveTransactions.PerInstance> perInstance = new ArrayList<>();
    List<Pending> pending = new ArrayList<>();
    Map<Kind, Set<Long>> keys = new EnumMap<>(Kind.class);
    Instant newest = null;
    List<AgentSession> live = new ArrayList<>(sessions.byApp(appId));
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
          String st = state(t.getState());
          if (!kinds.storedValues().contains(kind(t.getKind()))
              || (wantedState != null && !wantedState.equals(st))
              || e < minElapsed) {
            continue;
          }
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
              states.state(s.instanceId()),
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
    boolean truncated = pending.size() > MAX_ITEMS;
    List<Pending> shown = truncated ? pending.subList(0, MAX_ITEMS) : pending;
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
              Ids.text(t.getTxId()),
              name(resolved, Kind.SERVICE, t.getServiceId()),
              Ids.keyOrNull(t.getServiceId()),
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
    return ResponseEnvelope.builder(new ActiveTransactions(newest, perInstance, items))
        .truncated(truncated)
        .counts(items.size(), pending.size())
        .build();
  }

  private record Pending(AgentSession session, ActiveTransaction tx, String state) {}

  private static String state(ActiveState state) {
    return switch (state) {
      case IN_SQL -> "SQL";
      case IN_HTTP -> "HTTP";
      case IN_DBC_WAIT -> "DBC";
      case IN_ASYNC -> "ASYNC";
      default -> "METHOD";
    };
  }

  private static String kind(TransactionKind kind) {
    return switch (kind) {
      case BATCH -> "BATCH";
      case MESSAGE -> "MESSAGE";
      default -> "HTTP";
    };
  }

  /** The dictionary of {@code state_name_id} (proto: SQL sql_id, HTTP host, DBC DB target). */
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

  /** The text of 4 or 16 address bytes as the agent's mode sent them; null when none. */
  static String address(byte[] bytes) {
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
