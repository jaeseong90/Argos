/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.server.api.dashboard.MetricKeys;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.config.ArgosProperties;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionSettings;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the application and instance views of 14 §4.3 from PostgreSQL ({@link CatalogRepository}),
 * the session registry and the instance state machine ({@link InstanceStates}, 12 §4.2) and the
 * agent drop series of ClickHouse.
 */
@Service
public class AppsService {

  /** Most applications of a list (14 §4.3). */
  public static final int MAX_APPS = 500;

  /** Most instances of a list (14 §4.3). */
  public static final int MAX_INSTANCES = 1_000;

  /** How far back {@code kinds} looks (ADR-0010 C-205 (4): kinds of the last 7 days). */
  public static final Duration KINDS_WINDOW = Duration.ofDays(7);

  private static final Logger LOG = LoggerFactory.getLogger(AppsService.class);

  private final CatalogRepository catalog;
  private final InstanceStates states;
  private final MetricKeys metrics;
  private final TimeQueries queries;
  private final Map<String, Boolean> autoRegisterDefaults;

  public AppsService(
      CatalogRepository catalog,
      InstanceStates states,
      MetricKeys metrics,
      TimeQueries queries,
      ArgosProperties properties) {
    this.autoRegisterDefaults = properties.agent().autoRegister();
    this.catalog = catalog;
    this.states = states;
    this.metrics = metrics;
    this.queries = queries;
  }

  /**
   * The views of the applications of a list ({@code kinds} is not read there: null); the instance
   * ids of all of them are read in one query for their up counts.
   */
  public List<AppViews.App> views(List<AppRecord> apps) {
    List<Integer> appIds = apps.stream().map(AppRecord::id).toList();
    Map<Integer, List<Integer>> ids =
        catalog.instanceIdsByApp(appIds, InstanceStates.SUMMARY_INSTANCES_MAX);
    Map<Integer, Integer> tokens = catalog.activeTokenCounts(appIds, queries.now());
    return apps.stream()
        .map(
            a -> view(a, ids.getOrDefault(a.id(), List.of()), tokens.getOrDefault(a.id(), 0), null))
        .toList();
  }

  /**
   * The view of one application ({@code GET /apps/{appId}}) with the transaction kinds of the last
   * 7 days ({@link #kinds}).
   */
  public AppViews.App appDetail(AppRecord a) {
    List<Integer> ids =
        catalog
            .instanceIdsByApp(List.of(a.id()), InstanceStates.SUMMARY_INSTANCES_MAX)
            .getOrDefault(a.id(), List.of());
    int tokens = catalog.activeTokenCounts(List.of(a.id()), queries.now()).getOrDefault(a.id(), 0);
    return view(a, ids, tokens, kinds(a.id()));
  }

  /**
   * {@code instancesUp}: the instances in {@code UP} or {@code WARN} ({@link InstanceStates});
   * {@code tokensActive}: the usable agent tokens ({@link CatalogRepository#activeTokenCounts}).
   */
  private AppViews.App view(
      AppRecord a, List<Integer> instanceIds, int tokensActive, List<String> kinds) {
    return new AppViews.App(
        a.id(),
        a.name(),
        a.env(),
        a.group(),
        a.instancesTotal(),
        states.summarize(instanceIds).up(),
        tokensActive,
        effectiveAutoRegister(a) ? "auto" : "approval",
        a.autoRegister() == null,
        a.recommendedRulesApplied(),
        a.lastSeenAt(),
        a.apdexTMs(),
        a.slowThresholdMs(),
        a.createdAt(),
        a.registeredFromAppId(),
        kinds,
        a.appPackages());
  }

  /**
   * The registration policy that applies to the application: its own ({@code apps.auto_register}),
   * else the environment default {@code argos.agent.auto-register.<env>} (ADR-0010 C-231 (7)).
   */
  private boolean effectiveAutoRegister(AppRecord a) {
    Boolean own = a.autoRegister();
    return own != null ? own : SessionSettings.autoRegisters(autoRegisterDefaults, a.env());
  }

  /**
   * The transaction kinds the application recorded in the last {@link #KINDS_WINDOW} (the hour
   * rollup {@code service_stat_1h}, template {@code apps/kinds}), sorted; null when ClickHouse
   * cannot answer (the application view stays available, the kinds are unknown).
   */
  List<String> kinds(int appId) {
    Instant from = queries.now().minus(KINDS_WINDOW).truncatedTo(ChronoUnit.HOURS);
    try {
      List<String> out = new ArrayList<>();
      for (QueryRow row :
          queries.run(
              "apps/kinds", QueryParams.create().uint32("app", appId).dateTime("from", from))) {
        out.add(row.getString("kind"));
      }
      return List.copyOf(out);
    } catch (ApiException e) {
      if (!e.errorCode().serverError()) {
        throw e;
      }
      LOG.debug("Transaction kinds unavailable for app {}: {}", appId, e.errorCode().code());
      return null;
    }
  }

  /**
   * Applications matching the filters, at most {@value #MAX_APPS} (one more is read to know whether
   * the list was cut).
   */
  public List<AppRecord> apps(String env, String group, String q) {
    return catalog.apps(
        new CatalogRepository.AppFilter(
            blankToNull(QueryLimits.text("env", env)),
            blankToNull(QueryLimits.text("group", group)),
            blankToNull(QueryLimits.text("q", q))),
        MAX_APPS + 1);
  }

  /** The instance views of an application (non-archived unless asked), filtered by state. */
  public List<AppViews.Instance> instances(AppRecord app, boolean archived, String state) {
    List<InstanceRecord> rows = catalog.instances(app.id(), archived, MAX_INSTANCES + 1);
    Map<Integer, Long> drops = drops24h(app.id(), List.of());
    String majority = majorityHash(rows);
    List<AppViews.Instance> out = new ArrayList<>();
    for (InstanceRecord r : rows) {
      AppViews.Instance view = view(app, r, drops, majority);
      if (state == null || state.equals(view.state())) {
        out.add(view);
      }
    }
    return out;
  }

  /** The view of one instance. */
  public AppViews.Instance instance(AppRecord app, InstanceRecord instance) {
    List<InstanceRecord> rows = catalog.instances(app.id(), false, MAX_INSTANCES);
    return view(app, instance, drops24h(app.id(), List.of(instance.id())), majorityHash(rows));
  }

  private AppViews.Instance view(
      AppRecord app, InstanceRecord r, Map<Integer, Long> drops, String majority) {
    Optional<AgentSession> session = states.session(r.id());
    String hash =
        session
            .map(AgentSession::configHash)
            .filter(h -> h != null && !h.isBlank())
            .orElse(r.configHash());
    Boolean drift = (hash == null || majority == null) ? null : !hash.equals(majority);
    return new AppViews.Instance(
        r.id(),
        new AppViews.AppRef(app.id(), app.name(), app.env()),
        r.name(),
        r.host(),
        r.pid(),
        states.state(r.id()),
        session.map(AgentSession::agentVersion).orElse(r.agentVersion()),
        states.compat(session.map(AgentSession::agentVersion).orElse(r.agentVersion())),
        hash,
        drift,
        session.map(s -> Instant.ofEpochMilli(s.lastHeartbeatMs())).orElse(r.lastSeenAt()),
        session.isPresent() ? Long.valueOf(session.get().clock().estimateMs()) : storedSkew(r),
        drops.get(r.id()),
        states.modulesDisabled(r.id()),
        r.ipMode(),
        r.userIdMode(),
        r.queryStringMode(),
        r.sqlParamMode(),
        session.map(AgentSession::virtualThreads).orElse(r.virtualThreads()),
        r.cgroup(),
        r.jvmVersion(),
        r.jvmVendor(),
        r.os(),
        r.protocolVersion(),
        r.remoteConfigAllow(),
        r.startedAt(),
        session.map(s -> Instant.ofEpochMilli(s.acceptedAtMs())).orElse(null),
        r.firstSeenAt(),
        r.lastSeenAt(),
        r.archivedAt(),
        session.map(AgentSession::appVersion).filter(v -> !v.isBlank()).orElse(r.versionTag()));
  }

  /**
   * The most common configuration hash of the application's instances (12 §4.2 drift rule; a tie
   * goes to the smaller hash), or null without hashes.
   */
  static String majorityHash(Collection<InstanceRecord> rows) {
    Map<String, Long> counts =
        rows.stream()
            .map(InstanceRecord::configHash)
            .filter(h -> h != null && !h.isBlank())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    return counts.entrySet().stream()
        .max(
            Comparator.comparing(Map.Entry<String, Long>::getValue)
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  /**
   * The agent drops of the last 24 h per instance ({@code argos_agent_dropped_total}); empty when
   * ClickHouse cannot answer (the instance list stays available, the value is unknown: null).
   */
  Map<Integer, Long> drops24h(int appId, List<Integer> instances) {
    Instant now = queries.now();
    try {
      SourcePlan plan =
          queries
              .sources()
              .plan(
                  now.minus(Duration.ofHours(24)),
                  now,
                  null,
                  QueryLimits.SERIES_RANGE_MAX,
                  queries.sources().metrics(),
                  null);
      Map<Integer, Long> out = new HashMap<>();
      for (MetricKeys.InstanceValue v :
          metrics.perInstance(appId, instances, plan, Set.of(MetricKeys.AGENT_DROPS))) {
        if (v.increase() != null) {
          out.merge(v.instanceId(), Math.round(v.increase()), Long::sum);
        }
      }
      return out;
    } catch (ApiException e) {
      if (!e.errorCode().serverError()) {
        throw e;
      }
      LOG.debug("Agent drops unavailable for app {}: {}", appId, e.errorCode().code());
      return Map.of();
    }
  }

  private static Long storedSkew(InstanceRecord r) {
    return r.clockOffsetMs() == null ? null : Long.valueOf(r.clockOffsetMs());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
