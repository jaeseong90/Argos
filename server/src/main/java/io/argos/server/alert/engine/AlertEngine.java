/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.AlertNotification.Group;
import io.argos.server.alert.api.AlertNotification.Item;
import io.argos.server.alert.api.AlertNotification.Recipients;
import io.argos.server.alert.api.AlertNotification.Severity;
import io.argos.server.alert.api.ChannelHealthEvent;
import io.argos.server.alert.api.NotificationSink;
import io.argos.server.alert.engine.StateMachine.Change;
import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.metric.AlertSampleIntake;
import io.argos.server.alert.metric.ClickHouseProvider;
import io.argos.server.alert.metric.CounterProvider;
import io.argos.server.alert.metric.MetricRequest;
import io.argos.server.alert.metric.MetricRouter;
import io.argos.server.alert.metric.MetricValue;
import io.argos.server.alert.metric.RegistryProvider;
import io.argos.server.alert.metric.SelfProvider;
import io.argos.server.alert.metric.WindowProvider;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleDurations;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.alert.template.DeepLinks;
import io.argos.server.alert.template.TemplateRenderer;
import io.argos.server.alert.templates.TemplateService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.realtime.RealtimeBus;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.storage.postgres.alert.rule.AlertEventRepository;
import io.argos.server.storage.postgres.alert.rule.AlertEventRepository.EventRow;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.AlertRuleRow;
import io.argos.server.storage.postgres.alert.rule.AlertStateRepository;
import io.argos.server.storage.postgres.alert.rule.AlertStateRepository.StateRow;
import io.argos.server.storage.postgres.alert.rule.MuteRepository;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.argos.server.storage.query.lookup.AppDirectory.AppInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

/**
 * The alert engine (12 §5.4, F-S06, F-S17, F-S25, F-S26; WO-25): one thread ({@code
 * argos-alert-eval}) that every second drains the ingest samples into the windows and handles the
 * queued instance state and channel health events, every {@code argos.alert.evaluation-interval}
 * (10 s) evaluates every enabled rule and unit (gates, metric value, {@link StateMachine}),
 * persists the transitions ({@code alert_states}, {@code alert_events}), pushes them on the
 * WebSocket topic {@code alert}, groups the notifications ({@link Grouper}) and hands them to the
 * delivery side ({@link NotificationSink}); every minute it applies the recommended rules to new
 * applications and drops idle windows.
 *
 * <p>Event-driven rules: {@code instance_state} fires when the instance becomes DOWN and resolves
 * when it is up again (12 §5.4: no evaluation; an {@code AgentBye} followed by a reconnect within
 * the 5-minute grace never becomes DOWN, so it never fires, S-17); {@code
 * argos_notification_failures_total} fires on a failing channel ({@link ChannelHealthEvent}) and
 * notifies every other enabled channel (12 §5.5).
 *
 * <p>State lives in memory and is restored from {@code alert_states} at the start. Calls from other
 * threads ({@link #ack}, {@link #reload}, {@link #evaluateNow}) run on the engine thread and wait.
 */
public final class AlertEngine implements SmartLifecycle {

  /** Lifecycle phase: after the session monitor, before the collector. */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 700;

  /** Units per rule at most (instances, services, hosts of one application). */
  public static final int MAX_UNITS_PER_RULE = 200;

  /** The engine thread's tick. */
  static final long TICK_MS = 1_000;

  /** Housekeeping and the recommended rules job. */
  static final long HOUSEKEEPING_MS = 60_000;

  /** How long a call from another thread waits for the engine thread. */
  static final long CALL_TIMEOUT_SECONDS = 10;

  private static final Logger LOG = LoggerFactory.getLogger(AlertEngine.class);

  private static final DateTimeFormatter STARTED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT);

  private final Clock clock;
  private final AlertRuleRepository ruleStore;
  private final AlertStateRepository stateStore;
  private final AlertEventRepository eventStore;
  private final MuteRepository muteStore;
  private final AppDirectory apps;
  private final DictionaryNames names;
  private final AlertSampleIntake intake;
  private final TemplateService templates;
  private final List<NotificationSink> sinks;
  private final ObjectProvider<RealtimeBus> bus;
  private final boolean recommended;
  private final String publicUrl;
  private final ZoneId zone;
  private final long intervalNanos;

  private final WindowProvider windows;
  private final CounterProvider counters;
  private final RegistryProvider registry;
  private final SelfProvider self;
  private final MetricRouter router;
  private final Grouper grouper;

  private final Map<UUID, LoadedRule> rules = new LinkedHashMap<>();
  private final Map<String, UnitState> units = new LinkedHashMap<>();
  private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
  private final Set<Integer> batchApps = ConcurrentHashMap.newKeySet();
  private final Map<Integer, AppInfo> appCache = new HashMap<>();
  private final Map<String, AtomicInteger> stateGauges = new HashMap<>();
  private final Counter evaluations;
  private final Counter evaluationErrors;
  private final Counter notificationsSubmitted;

  private List<MuteRow> mutes = List.of();
  private volatile List<ActiveAlert> active = List.of();
  private volatile boolean running;
  private volatile Thread engineThread;
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> ticks;
  private long lastEvalNanos = Long.MIN_VALUE / 2;
  private long lastHousekeepingNanos = Long.MIN_VALUE / 2;
  private long lastErrorLogNanos = Long.MIN_VALUE / 2;

  /**
   * The engine.
   *
   * @param sinks the delivery side: at least one (the notifier); every sink receives every
   *     notification (tests add a recording sink next to it)
   * @param queries the ClickHouse reader for windows longer than the retention, or null
   */
  public AlertEngine(
      ArgosProperties properties,
      Clock clock,
      MeterRegistry meters,
      AlertRuleRepository ruleStore,
      AlertStateRepository stateStore,
      AlertEventRepository eventStore,
      MuteRepository muteStore,
      AppDirectory apps,
      DictionaryNames names,
      SessionRegistry sessions,
      QueryExecutor queries,
      AlertSampleIntake intake,
      TemplateService templates,
      List<NotificationSink> sinks,
      ObjectProvider<RealtimeBus> bus) {
    if (sinks.isEmpty()) {
      throw new IllegalStateException(
          "No NotificationSink bean: the alert engine needs the notifier to deliver alerts");
    }
    ArgosProperties.Alert a = properties.alert();
    this.clock = clock;
    this.ruleStore = ruleStore;
    this.stateStore = stateStore;
    this.eventStore = eventStore;
    this.muteStore = muteStore;
    this.apps = apps;
    this.names = names;
    this.intake = intake;
    this.templates = templates;
    this.sinks = List.copyOf(sinks);
    this.bus = bus;
    this.recommended = a.recommendedRules().enabled();
    this.publicUrl = properties.web() == null ? null : properties.web().publicUrl();
    this.zone = properties.server() == null ? ZoneId.of("UTC") : properties.server().timezone();
    this.intervalNanos = Math.max(1_000_000_000L, a.evaluationInterval().toNanos());
    this.windows = new WindowProvider(a.windowRetention());
    this.counters = new CounterProvider(a.windowRetention());
    this.registry = new RegistryProvider(sessions, counters);
    this.self = new SelfProvider(meters, a.windowRetention().toMillis());
    this.router =
        new MetricRouter(
            windows,
            counters,
            registry,
            queries == null ? null : new ClickHouseProvider(queries),
            self,
            a.windowRetention());
    this.grouper = new Grouper(a.groupWindow());
    this.evaluations =
        Counter.builder("argos.alert.evaluations")
            .description("Rule unit evaluations of the alert engine (12 §5.4)")
            .register(meters);
    this.evaluationErrors =
        Counter.builder("argos.alert.evaluation.errors")
            .description("Rule evaluations that failed (storage or query errors)")
            .register(meters);
    this.notificationsSubmitted =
        Counter.builder("argos.alert.notifications.submitted")
            .description("Grouped notifications handed to the delivery side")
            .register(meters);
    for (Severity sev : Severity.values()) {
      for (AlertState st :
          List.of(AlertState.PENDING, AlertState.FIRING, AlertState.ACKED, AlertState.NO_DATA)) {
        AtomicInteger n = new AtomicInteger();
        stateGauges.put(sev.wire() + "|" + st.name(), n);
        Gauge.builder("argos.alert.states", n, AtomicInteger::get)
            .description("Rule units per severity and state (12 §5.4)")
            .tag("severity", sev.wire())
            .tag("state", st.name())
            .register(meters);
      }
    }
  }

  // --- lifecycle ------------------------------------------------------------------------------

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    AtomicInteger next = new AtomicInteger(1);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-alert-eval-" + next.getAndIncrement());
              t.setDaemon(true);
              engineThread = t;
              return t;
            });
    executor.execute(this::startOnThread);
    ticks =
        executor.scheduleWithFixedDelay(this::safeCycle, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
  }

  private void startOnThread() {
    try {
      if (recommended) {
        int n = templates.applyServerRules();
        if (n > 0) {
          LOG.info("Created {} default server alert rules", n);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("The default server alert rules could not be created: {}", e.toString());
    }
    try {
      loadRules();
      restoreStates();
    } catch (RuntimeException e) {
      LOG.warn("The alert rules could not be loaded; retried every minute: {}", e.toString());
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    if (ticks != null) {
      ticks.cancel(false);
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  // --- public API -----------------------------------------------------------------------------

  /** The active (FIRING or ACKED) alerts, newest first: the firing panels (C-212 (2)). */
  public List<ActiveAlert> activeAlerts() {
    return active;
  }

  /** Whether the application had BATCH transactions since the start. */
  public boolean sawBatch(int appId) {
    return batchApps.contains(appId);
  }

  /** The outcome of an acknowledgement. */
  public enum AckResult {
    /** FIRING became ACKED. */
    ACKED,
    /** No such event. */
    NOT_FOUND,
    /** The event exists but its alert is not FIRING (14 §4.13: 409). */
    NOT_FIRING
  }

  /**
   * Acknowledges the firing of {@code eventId} (F-S26): FIRING becomes ACKED, renotifies stop, the
   * RESOLVED notification is still sent.
   *
   * @param note the operator's note, or null
   */
  public AckResult ack(UUID eventId, UUID userId, String username, String note) {
    return call(
        () -> {
          for (UnitState s : units.values()) {
            if (eventId.equals(s.eventId) && s.state.active()) {
              LoadedRule lr = rules.get(s.ruleId);
              Change c = StateMachine.ack(s, userId, username, note, clock.millis());
              if (c == null) {
                return AckResult.NOT_FIRING;
              }
              persist(lr, s, c, clock.millis());
              refreshActive(clock.millis());
              return AckResult.ACKED;
            }
          }
          return eventStore.find(eventId).isPresent() ? AckResult.NOT_FIRING : AckResult.NOT_FOUND;
        });
  }

  /** Reloads the rules after a change (resolving the alerts of removed or disabled rules). */
  public void reload() {
    call(
        () -> {
          loadRules();
          refreshActive(clock.millis());
          return null;
        });
  }

  /**
   * One full cycle with an evaluation now, on the engine thread (tests; the scheduled cycle
   * evaluates every {@code evaluation-interval}).
   */
  public void evaluateNow() {
    call(
        () -> {
          cycle(true);
          return null;
        });
  }

  /**
   * The minute job now, on the engine thread: recommended rules of new applications, idle windows
   * (tests; it runs every minute).
   */
  public void housekeepingNow() {
    call(
        () -> {
          housekeeping(clock.millis());
          refreshActive(clock.millis());
          return null;
        });
  }

  /** Queued ingest batches (tests wait for 0 before an evaluation). */
  public int pendingSamples() {
    return intake.pending();
  }

  /** The state of a rule unit, for tests: null when the unit has none. */
  public UnitState state(UUID ruleId, String unitKey) {
    return call(() -> units.get(key(ruleId, unitKey)));
  }

  /** Estimated heap bytes of the windows and rings (12 §15 #3). */
  public long estimatedWindowBytes() {
    return call(() -> windows.estimatedFootprintBytes() + counters.estimatedFootprintBytes());
  }

  // --- events ---------------------------------------------------------------------------------

  /**
   * Instance state transitions (12 §4.2): DOWN fires, up resolves the {@code instance_state} rules.
   */
  @EventListener
  public void onInstanceState(InstanceStateChange change) {
    inbox.add(() -> handleInstanceState(change));
  }

  /** Channel health changes (12 §5.5): the {@code channel_failure} self rule. */
  @EventListener
  public void onChannelHealth(ChannelHealthEvent event) {
    inbox.add(() -> handleChannelHealth(event));
  }

  // --- the cycle ------------------------------------------------------------------------------

  private void safeCycle() {
    try {
      cycle(false);
    } catch (RuntimeException e) {
      evaluationErrors.increment();
      logError("The alert engine cycle failed", e);
    }
  }

  private void cycle(boolean forceEval) {
    long now = clock.millis();
    intake.drain(windows, counters, this::onBatchSeen);
    Runnable task;
    while ((task = inbox.poll()) != null) {
      try {
        task.run();
      } catch (RuntimeException e) {
        evaluationErrors.increment();
        logError("An alert event could not be handled", e);
      }
    }
    long nanos = System.nanoTime();
    if (nanos - lastHousekeepingNanos >= TimeUnit.MILLISECONDS.toNanos(HOUSEKEEPING_MS)) {
      lastHousekeepingNanos = nanos;
      housekeeping(now);
    }
    if (forceEval || nanos - lastEvalNanos >= intervalNanos) {
      lastEvalNanos = nanos;
      evaluateAll(now);
    }
    for (AlertNotification n : grouper.flush(now)) {
      submit(n);
    }
  }

  private void onBatchSeen(int appId) {
    if (!batchApps.add(appId) || !recommended) {
      return;
    }
    inbox.add(
        () -> {
          AppInfo app = app(appId);
          if (app != null && templates.applyBatch(appId, app.env())) {
            loadRules();
          }
        });
  }

  private void housekeeping(long now) {
    resolveGoneTargets(null, now);
    windows.evict(now);
    counters.evict(now);
    registry.evict();
    appCache.clear();
    try {
      if (recommended && templates.applyRecommended(batchApps::contains) > 0) {
        loadRules();
      } else if (rules.isEmpty()) {
        loadRules();
      }
    } catch (RuntimeException e) {
      evaluationErrors.increment();
      logError("The recommended alert rules could not be applied", e);
    }
  }

  private void evaluateAll(long now) {
    self.sample(now);
    try {
      mutes = muteStore.current(Instant.ofEpochMilli(now));
    } catch (RuntimeException e) {
      logError("The alert mutes could not be read", e);
    }
    for (LoadedRule lr : List.copyOf(rules.values())) {
      try {
        evaluateRule(lr, now);
      } catch (RuntimeException e) {
        evaluationErrors.increment();
        logError("An alert rule could not be evaluated", e);
      }
    }
    refreshActive(now);
  }

  private void evaluateRule(LoadedRule lr, long now) {
    RuleSpec r = lr.spec;
    if (!r.enabled()
        || lr.metric.source() == AlertMetric.Source.EVENT
        || lr.metric == AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL) {
      return;
    }
    Integer appId = r.target().appId();
    AppInfo app = null;
    if (appId != null) {
      app = app(appId);
      if (app == null || (r.target().env() != null && !r.target().env().equals(app.env()))) {
        return;
      }
    }
    var local = Instant.ofEpochMilli(now).atZone(zone);
    if (!Gates.active(r, local) || Gates.scheduleMuted(r, local)) {
      return;
    }
    String env = app == null ? r.target().env() : app.env();
    for (Unit u : units(lr, appId, now)) {
      UnitState s = units.computeIfAbsent(key(lr.row.id(), u.key), k -> newUnit(lr, u, env));
      if (Gates.muted(mutes, lr.row.id(), appId, env, u.instanceId(), Instant.ofEpochMilli(now))) {
        continue;
      }
      Set<Integer> excluded = new HashSet<>();
      if (appId != null && lr.metric.source() != AlertMetric.Source.SELF) {
        boolean anyWarming = false;
        for (int inst : registry.liveInstances(appId)) {
          if (Gates.warming(registry.warmupStart(inst), r, now)) {
            excluded.add(inst);
            anyWarming = true;
          }
        }
        if (u.type == TargetType.INSTANCE && excluded.contains(u.instanceId())) {
          continue;
        }
        if ((u.type == TargetType.SERVICE || u.type == TargetType.EXT_HOST) && anyWarming) {
          continue;
        }
      }
      MetricValue v =
          router.read(
              new MetricRequest(
                  lr.metric, appId, r.kind(), u.type, u.id, r.window(), now, excluded));
      evaluations.increment();
      List<Change> changes = UnitEvaluation.evaluate(r, lr.metric, s, v, now);
      if (!changes.isEmpty()) {
        apply(lr, s, changes, now);
      }
    }
  }

  /** The units of a rule: its targets plus every unit with a non-OK state. */
  private List<Unit> units(LoadedRule lr, Integer appId, long now) {
    RuleSpec r = lr.spec;
    Map<String, Unit> out = new LinkedHashMap<>();
    TargetType type = r.target().type();
    switch (type) {
      case SERVER -> out.put("server", new Unit("server", TargetType.SERVER, null));
      case APP -> {
        if (r.evaluationUnit() == RuleSpec.EvaluationUnit.INSTANCE) {
          for (int id : registry.liveInstances(appId)) {
            putUnit(out, TargetType.INSTANCE, Integer.toString(id));
          }
        } else {
          out.put("app", new Unit("app", TargetType.APP, null));
        }
      }
      case INSTANCE -> {
        Collection<String> ids =
            r.target().ids().isEmpty()
                ? registry.liveInstances(appId).stream().map(String::valueOf).toList()
                : r.target().ids();
        ids.forEach(id -> putUnit(out, TargetType.INSTANCE, id));
      }
      case SERVICE -> {
        if (!r.target().ids().isEmpty()) {
          r.target().ids().forEach(id -> putUnit(out, TargetType.SERVICE, id));
        } else {
          List<Long> ids =
              new ArrayList<>(
                  windows.services(appId, r.kind(), now, r.window(), MAX_UNITS_PER_RULE));
          if (lr.metric == AlertMetric.BATCH_ELAPSED_SECONDS) {
            for (long job : registry.runningBatchJobs(appId, now)) {
              if (!ids.contains(job)) {
                ids.add(job);
              }
            }
          }
          ids.forEach(id -> putUnit(out, TargetType.SERVICE, Long.toUnsignedString(id)));
        }
      }
      case EXT_HOST -> {
        if (!r.target().ids().isEmpty()) {
          r.target().ids().forEach(id -> putUnit(out, TargetType.EXT_HOST, id));
        } else {
          windows
              .hosts(appId, now, r.window(), MAX_UNITS_PER_RULE)
              .forEach(id -> putUnit(out, TargetType.EXT_HOST, Long.toUnsignedString(id)));
        }
      }
    }
    for (UnitState s : units.values()) {
      if (s.ruleId.equals(lr.row.id()) && s.state != AlertState.OK && !out.containsKey(s.unitKey)) {
        Unit u = Unit.of(s.unitKey);
        if (u != null) {
          out.put(u.key, u);
        }
      }
    }
    List<Unit> list = new ArrayList<>(out.values());
    return list.size() > MAX_UNITS_PER_RULE ? list.subList(0, MAX_UNITS_PER_RULE) : list;
  }

  private static void putUnit(Map<String, Unit> out, TargetType type, String id) {
    Unit u = Unit.of(type, id);
    out.putIfAbsent(u.key, u);
  }

  // --- event-driven rules ---------------------------------------------------------------------

  private void handleInstanceState(InstanceStateChange c) {
    long now = clock.millis();
    boolean down = c.to() == InstanceState.DOWN;
    boolean up = c.to().up();
    if (!down && !up) {
      return;
    }
    if (down) {
      resolveGoneTargets(Set.of(c.instanceId()), now);
    }
    for (LoadedRule lr : List.copyOf(rules.values())) {
      RuleSpec r = lr.spec;
      if (lr.metric != AlertMetric.INSTANCE_STATE
          || !r.enabled()
          || !Objects.equals(r.target().appId(), c.appId())
          || (r.target().env() != null && !r.target().env().equals(c.env()))) {
        continue;
      }
      String id = Integer.toString(c.instanceId());
      if (r.target().type() == TargetType.INSTANCE
          && !r.target().ids().isEmpty()
          && !r.target().ids().contains(id)) {
        continue;
      }
      Unit u = Unit.of(TargetType.INSTANCE, id);
      UnitState s = units.computeIfAbsent(key(lr.row.id(), u.key), k -> newUnit(lr, u, c.env()));
      List<Change> changes;
      if (down) {
        var local = Instant.ofEpochMilli(now).atZone(zone);
        if (!Gates.active(r, local)
            || Gates.scheduleMuted(r, local)
            || Gates.muted(
                mutes,
                lr.row.id(),
                c.appId(),
                c.env(),
                c.instanceId(),
                Instant.ofEpochMilli(now))) {
          continue;
        }
        Severity sev = r.condition().critical() != null ? Severity.CRITICAL : Severity.WARN;
        changes =
            StateMachine.fireNow(
                s,
                sev,
                1.0,
                StateMachine.threshold(r.condition(), sev),
                AlertNotification.Kind.FIRING,
                now);
      } else {
        changes = StateMachine.resolveNow(s, 0.0, AlertNotification.Kind.RESOLVED, now);
      }
      if (!changes.isEmpty()) {
        apply(lr, s, changes, now);
      }
    }
    refreshActive(now);
  }

  /**
   * Resolves the FIRING (or ACKED) instance units of evaluated {@code no_data: ignore} rules whose
   * instance is gone (ADR-0010 C-226 (12)): without a value they would never recover. Gone means a
   * DOWN transition, or no session and stored DOWN, archived or deleted (checked every
   * housekeeping, which also covers a merged temporary instance and states restored after a
   * restart). The {@code alert} push of the RESOLVED transition carries {@code reason:
   * target_gone}.
   *
   * @param down instances that just became DOWN, or null to look up every candidate's instance
   */
  private void resolveGoneTargets(Set<Integer> down, long now) {
    Map<Integer, List<UnitState>> candidates = new LinkedHashMap<>();
    for (UnitState s : units.values()) {
      LoadedRule lr = rules.get(s.ruleId);
      if (lr == null
          || s.targetType != TargetType.INSTANCE
          || s.targetId == null
          || !s.state.active()
          || lr.spec.noData() != RuleSpec.NoData.IGNORE
          || lr.metric.source() == AlertMetric.Source.EVENT
          || lr.metric == AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL) {
        continue;
      }
      int id = s.targetId.intValue();
      // A DOWN transition is trusted as is; the stored state only without a session (it may lag
      // a reconnect).
      if (down != null ? down.contains(id) : !registry.live(id)) {
        candidates.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
      }
    }
    if (candidates.isEmpty()) {
      return;
    }
    Set<Integer> gone;
    if (down != null) {
      gone = candidates.keySet();
    } else {
      try {
        gone = apps.goneInstances(candidates.keySet());
      } catch (RuntimeException e) {
        evaluationErrors.increment();
        logError("The instances of firing alerts could not be read", e);
        return;
      }
    }
    for (Integer id : gone) {
      for (UnitState s : candidates.getOrDefault(id, List.of())) {
        List<Change> changes = StateMachine.resolveGone(s, now);
        if (!changes.isEmpty()) {
          apply(rules.get(s.ruleId), s, changes, now);
        }
      }
    }
    refreshActive(now);
  }

  private void handleChannelHealth(ChannelHealthEvent e) {
    long now = clock.millis();
    for (LoadedRule lr : List.copyOf(rules.values())) {
      if (lr.metric != AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL || !lr.spec.enabled()) {
        continue;
      }
      Unit u = new Unit("channel:" + e.channelId(), TargetType.SERVER, null);
      UnitState s = units.computeIfAbsent(key(lr.row.id(), u.key), k -> newUnit(lr, u, null));
      s.targetName = e.name();
      List<Change> changes =
          e.failing()
              ? StateMachine.fireNow(
                  s, Severity.CRITICAL, 1.0, 1.0, AlertNotification.Kind.SELF, now)
              : StateMachine.resolveNow(s, 0.0, AlertNotification.Kind.SELF, now);
      if (!changes.isEmpty()) {
        apply(lr, s, changes, now, e.channelId());
      }
    }
    refreshActive(now);
  }

  // --- transitions ----------------------------------------------------------------------------

  private void apply(LoadedRule lr, UnitState s, List<Change> changes, long now) {
    apply(lr, s, changes, now, null);
  }

  private void apply(LoadedRule lr, UnitState s, List<Change> changes, long now, UUID selfChannel) {
    if (s.targetName == null) {
      s.targetName = targetName(s);
    }
    for (Change c : changes) {
      persist(lr, s, c, now);
      if (c.notification() != null) {
        notifyChange(lr, s, c, now, selfChannel);
      }
    }
  }

  private void persist(LoadedRule lr, UnitState s, Change c, long now) {
    try {
      if (c.persist()) {
        eventStore.insert(
            new EventRow(
                c.eventId(),
                s.ruleId,
                s.appId,
                s.env,
                s.targetType.wire(),
                s.targetId,
                s.targetName,
                c.from().name(),
                c.to().name(),
                c.severity() == null ? null : c.severity().wire(),
                s.value,
                s.threshold,
                c.durationS(),
                c.to() == AlertState.ACKED ? s.ackedBy : null,
                c.to() == AlertState.ACKED ? s.ackNote : null,
                Instant.ofEpochMilli(now),
                c.reason() == null ? null : c.reason().wire()));
      }
      stateStore.upsert(row(s));
    } catch (RuntimeException e) {
      evaluationErrors.increment();
      logError("An alert transition could not be stored", e);
    }
    if (c.persist()) {
      publish(lr, s, c, now);
    }
  }

  private void notifyChange(LoadedRule lr, UnitState s, Change c, long now, UUID selfChannel) {
    Severity sev = c.severity() == null ? Severity.WARN : c.severity();
    UUID eventId = c.eventId() != null ? c.eventId() : s.eventId;
    Map<String, String> vars = variables(lr, s, c, now, sev);
    String message = TemplateRenderer.render(lr.spec.messageTemplate(), vars);
    Item item =
        new Item(
            eventId, s.ruleId, c.notification(), sev, Instant.ofEpochMilli(now), vars, message);
    AppInfo app = s.appId == null ? null : app(s.appId);
    Group group = new Group(s.appId, app == null ? null : app.name(), s.env, sev);
    if (selfChannel != null) {
      submit(
          new AlertNotification(
              group,
              AlertNotification.Kind.SELF,
              List.of(item),
              0,
              Grouper.title(group, AlertNotification.Kind.SELF, List.of(item), 1),
              message,
              Recipients.allEnabledExcept(selfChannel),
              List.of(eventId)));
      return;
    }
    List<UUID> channels =
        sev == Severity.CRITICAL ? lr.spec.channels().critical() : lr.spec.channels().warn();
    AlertNotification n = grouper.offer(group, item, channels, now);
    if (n != null) {
      submit(n);
    }
  }

  private void submit(AlertNotification n) {
    if (n.recipients().isEmpty()) {
      return;
    }
    notificationsSubmitted.increment();
    for (NotificationSink sink : sinks) {
      try {
        sink.submit(n);
      } catch (RuntimeException e) {
        logError("A notification could not be queued for delivery", e);
      }
    }
  }

  private Map<String, String> variables(
      LoadedRule lr, UnitState s, Change c, long now, Severity sev) {
    Map<String, String> v = new LinkedHashMap<>();
    AppInfo app = s.appId == null ? null : app(s.appId);
    AlertMetric m = lr.metric;
    v.put("app", app == null ? null : app.name());
    v.put("env", s.env);
    switch (s.targetType) {
      case INSTANCE -> v.put("instance", s.targetName);
      case SERVICE -> v.put("service", s.targetName);
      case EXT_HOST -> v.put("host", s.targetName);
      default -> {}
    }
    v.put("metric", m.wire());
    v.put("value", format(m, s.value));
    v.put("threshold", format(m, s.threshold));
    v.put("severity", sev.wire());
    int seconds =
        c.durationS() > 0
            ? c.durationS()
            : StateMachine.seconds(now - (s.firingSinceMs >= 0 ? s.firingSinceMs : now));
    v.put("duration", RuleDurations.human(Duration.ofSeconds(seconds)));
    long firing = s.firingSinceMs >= 0 ? s.firingSinceMs : now;
    v.put("started_at", STARTED_AT.format(Instant.ofEpochMilli(firing).atZone(zone)));
    v.put("link", link(s, firing));
    v.put("runbook", lr.spec.runbookUrl());
    v.put("rule", lr.spec.name());
    return v;
  }

  private String link(UnitState s, long firingMs) {
    UUID id = s.eventId != null ? s.eventId : UUID.randomUUID();
    return DeepLinks.alert(
        publicUrl, s.appId, s.env, s.targetType, s.targetId, Instant.ofEpochMilli(firingMs), id);
  }

  /** A value with its unit, for messages. */
  static String format(AlertMetric m, Double value) {
    if (value == null) {
      return null;
    }
    double v = value;
    return switch (m.unit()) {
      case MS -> String.format(Locale.ROOT, "%.0f ms", v);
      case SECONDS -> String.format(Locale.ROOT, "%.1f s", v);
      case DAYS -> String.format(Locale.ROOT, "%.1f days", v);
      case RATIO -> String.format(Locale.ROOT, "%.1f%%", v * 100);
      case SCORE -> String.format(Locale.ROOT, "%.2f", v);
      case PER_SECOND -> String.format(Locale.ROOT, "%.1f/s", v);
      case STATE -> v >= 1 ? "DOWN" : "UP";
      case COUNT ->
          v == Math.rint(v)
              ? String.format(Locale.ROOT, "%.0f", v)
              : String.format(Locale.ROOT, "%.2f", v);
    };
  }

  private void publish(LoadedRule lr, UnitState s, Change c, long now) {
    RealtimeBus b = bus.getIfAvailable();
    if (b == null) {
      return;
    }
    try {
      AppInfo app = s.appId == null ? null : app(s.appId);
      long firing = s.firingSinceMs >= 0 ? s.firingSinceMs : now;
      AlertPushEvent e =
          new AlertPushEvent(
              c.eventId(),
              now,
              s.appId,
              app == null ? null : app.name(),
              s.env,
              s.targetType.wire(),
              s.targetId,
              s.targetName,
              lr.row.id(),
              lr.spec.name(),
              lr.metric.wire(),
              c.severity() == null ? null : c.severity().wire(),
              c.to().name(),
              s.value,
              s.threshold,
              c.durationS(),
              // The same value as the alert_events row of this transition (C-228 (2)).
              c.reason() == null ? null : c.reason().wire(),
              DeepLinks.alert(
                  publicUrl,
                  s.appId,
                  s.env,
                  s.targetType,
                  s.targetId,
                  Instant.ofEpochMilli(firing),
                  c.eventId()));
      b.publishAlert(s.appId, s.env, now, e.toJson());
    } catch (RuntimeException ex) {
      LOG.debug("The alert push failed: {}", ex.toString());
    }
  }

  private void refreshActive(long now) {
    List<ActiveAlert> out = new ArrayList<>();
    Map<String, Integer> counts = new HashMap<>();
    for (UnitState s : units.values()) {
      if (s.severity != null) {
        counts.merge(s.severity.wire() + "|" + s.state.name(), 1, Integer::sum);
      }
      if (!s.state.active()) {
        continue;
      }
      LoadedRule lr = rules.get(s.ruleId);
      if (lr == null) {
        continue;
      }
      AppInfo app = s.appId == null ? null : app(s.appId);
      long firing = s.firingSinceMs >= 0 ? s.firingSinceMs : now;
      out.add(
          new ActiveAlert(
              s.eventId,
              Instant.ofEpochMilli(firing),
              s.appId,
              app == null ? null : app.name(),
              s.env,
              s.targetType.wire(),
              s.targetId == null ? null : Long.toUnsignedString(s.targetId),
              s.targetName,
              s.ruleId,
              lr.spec.name(),
              lr.metric.wire(),
              s.severity == null ? Severity.WARN.wire() : s.severity.wire(),
              s.state.name(),
              s.value,
              s.threshold,
              StateMachine.seconds(now - firing),
              s.ackedBy,
              s.ackedByName,
              s.ackedAtMs >= 0 ? Instant.ofEpochMilli(s.ackedAtMs) : null,
              s.ackNote,
              DeepLinks.alert(
                  publicUrl,
                  s.appId,
                  s.env,
                  s.targetType,
                  s.targetId,
                  Instant.ofEpochMilli(firing),
                  s.eventId)));
    }
    out.sort(Comparator.comparing(ActiveAlert::ts).reversed());
    active = List.copyOf(out);
    RealtimeBus b = bus.getIfAvailable();
    if (b != null) {
      Map<Integer, int[]> firingByApp = new HashMap<>();
      for (ActiveAlert a : out) {
        if (a.appId() != null) {
          int[] c = firingByApp.computeIfAbsent(a.appId(), k -> new int[2]);
          c["critical".equals(a.severity()) ? 0 : 1]++;
        }
      }
      b.updateFiring(firingByApp);
    }
    stateGauges.forEach((k, n) -> n.set(counts.getOrDefault(k, 0)));
  }

  // --- rules and state ------------------------------------------------------------------------

  private void loadRules() {
    Map<UUID, LoadedRule> loaded = new LinkedHashMap<>();
    Set<UUID> live = new HashSet<>();
    for (AlertRuleRow row : ruleStore.listLive()) {
      live.add(row.id());
      try {
        RuleSpec spec = RuleCodec.parseStored(row.specJson());
        AlertMetric metric = AlertMetric.of(spec.metric());
        if (metric != null) {
          loaded.put(row.id(), new LoadedRule(row, spec, metric));
        }
      } catch (RuntimeException e) {
        LOG.warn("Alert rule {} has an invalid stored definition and is skipped", row.id());
      }
    }
    long now = clock.millis();
    Set<UUID> gone = new HashSet<>();
    for (UnitState s : List.copyOf(units.values())) {
      LoadedRule lr = loaded.get(s.ruleId);
      if (lr != null && lr.spec.enabled()) {
        continue;
      }
      LoadedRule old = rules.get(s.ruleId);
      if (s.state.active() && old != null) {
        // Still a live row: disabled, or no longer evaluable (invalid definition, unknown metric).
        AlertReason reason =
            live.contains(s.ruleId) ? AlertReason.RULE_DISABLED : AlertReason.RULE_DELETED;
        List<Change> changes =
            StateMachine.resolveNow(
                s, s.value == null ? 0 : s.value, AlertNotification.Kind.RESOLVED, reason, now);
        apply(old, s, changes, now);
      }
      units.remove(key(s.ruleId, s.unitKey));
      gone.add(s.ruleId);
    }
    for (UUID id : gone) {
      try {
        stateStore.deleteRule(id);
      } catch (RuntimeException e) {
        logError("Alert states could not be removed", e);
      }
    }
    rules.clear();
    rules.putAll(loaded);
  }

  private void restoreStates() {
    for (StateRow row : stateStore.loadAll()) {
      LoadedRule lr = rules.get(row.ruleId());
      if (lr == null) {
        continue;
      }
      Unit u = Unit.of(row.unitKey());
      UnitState s = new UnitState(row.ruleId(), row.unitKey());
      s.appId = row.appId();
      s.env = row.env();
      s.targetType = u == null ? TargetType.APP : u.type;
      s.targetId = row.targetId();
      s.targetName = row.targetName();
      AlertState st = AlertState.of(row.state());
      s.state = st == AlertState.RESOLVED ? AlertState.OK : st;
      s.severity = s.state == AlertState.OK ? null : severity(row.severity());
      s.stateSinceMs = ms(row.stateSince());
      s.pendingSinceMs = ms(row.pendingSince());
      s.firingSinceMs = ms(row.firingSince());
      s.recoverSinceMs = ms(row.recoverSince());
      s.lastNotifiedMs = ms(row.lastNotifiedAt());
      s.deescalateTo = severity(row.deescalateTo());
      s.beforeNoData = row.beforeNoData() == null ? null : AlertState.of(row.beforeNoData());
      s.eventId = row.eventId();
      s.eventTsMs = ms(row.eventTs());
      s.value = row.value();
      s.threshold = row.threshold();
      s.ackedBy = row.ackedBy();
      s.ackedAtMs = ms(row.ackedAt());
      s.ackNote = row.ackNote();
      units.put(key(row.ruleId(), row.unitKey()), s);
    }
    refreshActive(clock.millis());
  }

  private UnitState newUnit(LoadedRule lr, Unit u, String env) {
    UnitState s = new UnitState(lr.row.id(), u.key);
    s.appId = lr.spec.target().appId();
    s.env = env;
    s.targetType = u.type;
    s.targetId = u.id == null ? null : Long.parseUnsignedLong(u.id);
    s.stateSinceMs = clock.millis();
    return s;
  }

  private String targetName(UnitState s) {
    try {
      return switch (s.targetType) {
        case INSTANCE -> {
          var info = apps.instances(List.of(s.targetId.intValue())).get(s.targetId.intValue());
          yield info == null ? s.unitKey : info.name();
        }
        case SERVICE -> names.name(s.appId, DictionaryNames.Kind.SERVICE, s.targetId);
        case EXT_HOST -> names.name(s.appId, DictionaryNames.Kind.HOST, s.targetId);
        case APP -> {
          AppInfo app = s.appId == null ? null : app(s.appId);
          yield app == null ? null : app.name();
        }
        case SERVER -> "server";
      };
    } catch (RuntimeException e) {
      return null;
    }
  }

  private AppInfo app(int appId) {
    AppInfo a = appCache.get(appId);
    if (a == null && !appCache.containsKey(appId)) {
      a = apps.app(appId).orElse(null);
      appCache.put(appId, a);
    }
    return a;
  }

  private static StateRow row(UnitState s) {
    return new StateRow(
        s.ruleId,
        s.unitKey,
        s.appId,
        s.env,
        s.targetType.wire(),
        s.targetId,
        s.targetName == null
            ? null
            : clip(s.targetName, AlertEventRepository.MAX_TARGET_NAME_CHARS),
        s.state.name(),
        s.severity == null ? null : s.severity.wire(),
        Instant.ofEpochMilli(s.stateSinceMs),
        instant(s.pendingSinceMs),
        instant(s.firingSinceMs),
        instant(s.recoverSinceMs),
        instant(s.lastNotifiedMs),
        s.deescalateTo == null ? null : s.deescalateTo.wire(),
        s.beforeNoData == null ? null : s.beforeNoData.name(),
        s.eventId,
        instant(s.eventTsMs),
        s.value,
        s.threshold,
        s.ackedBy,
        instant(s.ackedAtMs),
        s.ackNote == null ? null : clip(s.ackNote, AlertEventRepository.MAX_NOTE_CHARS));
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static Instant instant(long ms) {
    return ms < 0 ? null : Instant.ofEpochMilli(ms);
  }

  private static long ms(Instant i) {
    return i == null ? -1 : i.toEpochMilli();
  }

  private static Severity severity(String wire) {
    if (wire == null) {
      return null;
    }
    return wire.equals("critical") ? Severity.CRITICAL : Severity.WARN;
  }

  static String key(UUID ruleId, String unitKey) {
    return ruleId + "|" + unitKey;
  }

  private <T> T call(Callable<T> task) {
    if (Thread.currentThread() == engineThread) {
      try {
        return task.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    ScheduledExecutorService ex = executor;
    if (ex == null || !running) {
      throw new IllegalStateException("The alert engine is not running");
    }
    Future<T> f = ex.submit(task);
    try {
      return f.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the alert engine", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(e.getCause());
    } catch (TimeoutException e) {
      f.cancel(false);
      throw new IllegalStateException("The alert engine did not answer in time", e);
    }
  }

  private void logError(String what, RuntimeException e) {
    long now = System.nanoTime();
    if (now - lastErrorLogNanos >= TimeUnit.MINUTES.toNanos(1)) {
      lastErrorLogNanos = now;
      LOG.warn("{}: {} (further failures this minute are only counted)", what, e.toString());
    } else {
      LOG.debug("{}: {}", what, e.toString());
    }
  }

  private record LoadedRule(AlertRuleRow row, RuleSpec spec, AlertMetric metric) {}

  /**
   * One evaluation unit.
   *
   * @param key the unit key
   * @param type the target type of the unit
   * @param id the instance, service or host id (decimal), null for the application or server
   */
  private record Unit(String key, TargetType type, String id) {

    static Unit of(TargetType type, String id) {
      String prefix =
          switch (type) {
            case INSTANCE -> "instance:";
            case SERVICE -> "service:";
            case EXT_HOST -> "host:";
            default -> "";
          };
      return new Unit(prefix + id, type, id);
    }

    static Unit of(String key) {
      if (key.equals("app")) {
        return new Unit(key, TargetType.APP, null);
      }
      if (key.equals("server") || key.startsWith("channel:")) {
        return new Unit(key, TargetType.SERVER, null);
      }
      int colon = key.indexOf(':');
      if (colon < 0) {
        return null;
      }
      String id = key.substring(colon + 1);
      return switch (key.substring(0, colon)) {
        case "instance" -> new Unit(key, TargetType.INSTANCE, id);
        case "service" -> new Unit(key, TargetType.SERVICE, id);
        case "host" -> new Unit(key, TargetType.EXT_HOST, id);
        default -> null;
      };
    }

    Integer instanceId() {
      return type == TargetType.INSTANCE ? Integer.valueOf(id) : null;
    }
  }
}
