/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.api.query.UntrustedStringsModule;
import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.RealtimePublisher;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.AgentSession;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

/**
 * The realtime bus (12 §3.7) and the server's {@link RealtimePublisher} (the only bean of that
 * type: {@code IngestService} takes it instead of the no-op, ADR-0010 C-128).
 *
 * <p><b>Sinks.</b> One Reactor {@code Sinks.many().multicast().onBackpressureBuffer(1024)} per
 * topic key {@code (topic, app_id, env)} ({@code overview}: {@code (overview, 0, env or "")}),
 * created by the first subscription and emitted only while it has subscribers, always with {@code
 * tryEmitNext} (a failed emission is counted, never waited for). Each subscription consumes its
 * sink and queues pushes in its connection's {@link SubscriberOutbox}, which applies the
 * per-connection backpressure of 14 §5.3 (1 MiB, oldest {@code xlog} pushes dropped first, {@code
 * counter}/{@code active}/{@code overview} latest only).
 *
 * <p><b>Ingest side.</b> {@link #publishXlog} only queues (lock-free, bounded) and never blocks an
 * ingest thread; points more than 60 s older than their receipt are dropped (T-SV-015, bus half).
 * Thread {@code argos-realtime-xlog} drains the queue every 100 ms into one {@link XlogBundle} per
 * application (12 §3.7 "묶음"), feeds the counters by {@code ts} and emits the bundles of subscribed
 * applications with their slow threshold and service names (cached lookups). Thread {@code
 * argos-realtime-periodic} closes the 5-second counter window ({@link CounterAggregator}) and emits
 * {@code counter}, {@code active} and {@code overview}.
 *
 * <p><b>JVM values of {@code counter}</b> (ADR-0010 C-212 (1)). {@link #publishMetrics} keeps the
 * heap, GC pause and CPU samples of applications with a {@code counter} subscription only (the
 * others are dropped at once) and queues them (bounded: {@value #METRIC_QUEUE} batches of at most
 * {@value #MAX_METRIC_POINTS} samples, further ones dropped, never waited for); the xlog thread
 * adds them to the counter seconds by {@code ts}, and the push carries {@code heap_used}, {@code
 * gc_pause_ms} and {@code cpu} of the closed window ({@link CounterAggregator.Jvm}), null without
 * samples (D-23).
 *
 * <p><b>Instance states.</b> {@code counter.per_instance[].state} and {@code
 * overview.apps[].status} replace the {@code instance_state} topic of 12 §3.7: the state of an
 * instance with a session is {@link InstanceStates#state}; an instance without one keeps the state
 * of its last {@link InstanceStateChange} (STALE, then DOWN for {@value #DOWN_KEEP_MINUTES}
 * minutes).
 *
 * <p><b>Subscriptions.</b> The WebSocket handler validates the frame and the role, queues the
 * {@code ack} in the outbox and then calls {@link #subscribe}; the returned handle is cancelled on
 * {@code unsubscribe} and when the connection closes. {@code alert} pushes the alert engine's
 * transitions ({@link #publishAlert}, WO-25); {@code admin} pushes the administration results
 * ({@link #publishAdmin}, WO-26: command state transitions, configuration change results), each to
 * the subscriptions without a filter and to those whose {@code change_id} or {@code command_id}
 * matches.
 */
@Component
public class RealtimeBus implements RealtimePublisher, SmartLifecycle {

  /** The {@code xlog} bundle window (12 §3.7, 14 §5.3). */
  static final long BUNDLE_MS = 100;

  /** How often the periodic thread checks for a closable counter window. */
  static final long PERIODIC_MS = 250;

  /** Elements a sink buffers for a subscriber without demand (12 §3.7). */
  static final int SINK_BUFFER = 1024;

  /** An active snapshot older than this does not count (twice the 5 s period, plus slack). */
  static final long SNAPSHOT_MAX_AGE_MS = 15_000;

  /** Rows of an {@code overview} push at most (the REST limit of {@code GET /overview}). */
  static final int MAX_OVERVIEW_APPS = 500;

  /** An application without data or state change for this long leaves {@code overview}. */
  static final long TRACK_MS = Duration.ofMinutes(10).toMillis();

  /** Minutes a DOWN instance without a session stays in {@code counter.per_instance}. */
  static final int DOWN_KEEP_MINUTES = 10;

  /** Metric batches waiting for the xlog thread at most. */
  static final int METRIC_QUEUE = 4_096;

  /** JVM samples kept of one metric batch at most. */
  static final int MAX_METRIC_POINTS = 1_000;

  /** The slow threshold of an application whose settings cannot be read ({@code apps} default). */
  static final int DEFAULT_SLOW_THRESHOLD_MS = 1_000;

  private static final Logger LOG = LoggerFactory.getLogger(RealtimeBus.class);

  private static final TxKind[] KINDS = TxKind.values();

  /** A topic key of 12 §3.7. */
  record TopicKey(Topic topic, int appId, String env) {}

  /** The one sink of the {@code alert} topic: subscriptions filter by env and app (WO-25). */
  static final TopicKey ALERT_KEY = new TopicKey(Topic.ALERT, 0, "");

  /**
   * One {@code alert} push (WO-25).
   *
   * @param appId the alert's application, null for a server self alert
   * @param env the alert's environment, null for a server self alert
   * @param tMs server time of the transition
   * @param dataJson the push's {@code data} object ({@code {"event": {...}}})
   */
  record AlertPush(Integer appId, String env, long tMs, String dataJson) {}

  /** The one sink of the {@code admin} topic: subscriptions filter by change or command (WO-26). */
  static final TopicKey ADMIN_KEY = new TopicKey(Topic.ADMIN, 0, "");

  /** One {@code admin} push: a change or a command (one of the ids is null). */
  record AdminPush(String changeId, String commandId, long tMs, String dataJson) {}

  /** What a {@code counter} sink emits: the {@code data} JSON per kind choice. */
  record CounterTick(long tMs, Map<TxKind, String> data) {}

  /** What an {@code overview} sink emits: the {@code data} JSON per kind choice. */
  record OverviewTick(long tMs, Map<TxKind, String> data) {}

  /** What an {@code active} sink emits: build your payload now. */
  record ActiveTick(long tMs) {}

  private record KnownInstance(int appId, String env, InstanceState state, long atMs) {}

  private record TrackedApp(String env, long seenMs) {}

  private record NameKey(int appId, long id) {}

  private record PendingMetrics(int appId, int instanceId, List<MetricPoint> points) {}

  /** A subscription of one connection. */
  public interface BusSubscription {
    /** The subscription id of the frame. */
    String id();

    /** The topic. */
    Topic topic();

    /** Stops the pushes (idempotent) and forgets a waiting latest-only push. */
    void cancel();
  }

  private final BusSources sources;
  private final Clock clock;
  private final JsonMapper json;
  private final XlogBundler bundler = new XlogBundler();
  private final BlockingQueue<PendingMetrics> metricQueue = new ArrayBlockingQueue<>(METRIC_QUEUE);
  private final CounterAggregator counters;
  private final Map<TopicKey, Sinks.Many<Object>> sinks = new ConcurrentHashMap<>();
  private final Map<Integer, KnownInstance> known = new ConcurrentHashMap<>();
  private final Map<Integer, TrackedApp> tracked = new ConcurrentHashMap<>();
  private final Map<Topic, AtomicInteger> subscribers = new EnumMap<>(Topic.class);
  private final Cache<Integer, AppDirectory.AppInfo> apps =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
  private final Cache<Integer, Boolean> appMisses =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
  private final Cache<NameKey, String> names =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();
  private final Cache<NameKey, Boolean> nameMisses =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(5)).build();
  private final Counter droppedLate;
  private final Counter droppedBusFull;
  private final Counter droppedSink;
  private final Counter droppedBackpressure;
  private final Counter droppedCounterLate;
  private final Counter droppedMetric;
  private final AtomicLong lastFailureLogMs = new AtomicLong(Long.MIN_VALUE);
  private volatile ScheduledExecutorService xlogThread;

  /**
   * Active alerts per application, {@code [critical, warn]}, from the alert engine (WO-25); null
   * until the engine reports, then the overview rows carry {@code firing}.
   */
  private volatile Map<Integer, int[]> firing;

  private volatile ScheduledExecutorService periodicThread;

  /** The server's bus. */
  @Autowired
  public RealtimeBus(
      InstanceStates states,
      AppDirectory directory,
      DictionaryNames dictionary,
      MeterRegistry registry,
      Clock clock,
      ObjectProvider<JsonMapper> jsonMapper) {
    this(
        new ServerBusSources(states, directory, dictionary),
        registry,
        clock,
        jsonMapper.getIfAvailable(
            () -> JsonMapper.builder().addModule(new UntrustedStringsModule()).build()));
  }

  /** A bus over other sources (tests). The threads start with {@link #start()}. */
  RealtimeBus(BusSources sources, MeterRegistry registry, Clock clock, JsonMapper json) {
    this.sources = Objects.requireNonNull(sources, "sources");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.json = Objects.requireNonNull(json, "json");
    this.counters = new CounterAggregator(clock.millis());
    for (Topic t : Topic.values()) {
      AtomicInteger n = new AtomicInteger();
      subscribers.put(t, n);
      Gauge.builder("argos.realtime.subscribers", n, AtomicInteger::get)
          .description("Realtime subscriptions by topic")
          .tag("topic", t.wire())
          .register(registry);
    }
    droppedLate = dropped(registry, "late");
    droppedBusFull = dropped(registry, "bus_full");
    droppedSink = dropped(registry, "sink");
    droppedBackpressure = dropped(registry, "backpressure");
    droppedCounterLate = dropped(registry, "counter_late");
    droppedMetric = dropped(registry, "metric");
  }

  private static Counter dropped(MeterRegistry registry, String reason) {
    return Counter.builder("argos.realtime.dropped")
        .description(
            "XLog points and JVM metric samples (reason metric) not delivered in real time, by"
                + " reason")
        .tag("reason", reason)
        .register(registry);
  }

  // --- RealtimePublisher (ingest threads: never block) ---------------------------------------

  @Override
  public void publishXlog(SessionContext session, List<XlogPoint> points) {
    XlogBundler.Offered o = bundler.offer(session, points);
    if (o.late() > 0) {
      droppedLate.increment(o.late());
    }
    if (o.overflow() > 0) {
      droppedBusFull.increment(o.overflow());
    }
  }

  @Override
  public void publishActive(SessionContext session, ActiveTransactionSnapshot snapshot) {
    tracked.put(session.appId(), new TrackedApp(session.env(), clock.millis()));
  }

  /**
   * Queues the JVM samples of a metric batch for the {@code counter} push of the session's
   * application; dropped when that application has no {@code counter} subscription or the queue is
   * full (never blocks).
   */
  @Override
  public void publishMetrics(SessionContext session, List<MetricPoint> points) {
    if (points.isEmpty()
        || !subscribed(new TopicKey(Topic.COUNTER, session.appId(), session.env()))) {
      return;
    }
    List<MetricPoint> kept =
        points.size() > MAX_METRIC_POINTS
            ? List.copyOf(points.subList(0, MAX_METRIC_POINTS))
            : List.copyOf(points);
    if (points.size() > MAX_METRIC_POINTS) {
      droppedMetric.increment(points.size() - MAX_METRIC_POINTS);
    }
    if (!metricQueue.offer(new PendingMetrics(session.appId(), session.instanceId(), kept))) {
      droppedMetric.increment(kept.size());
    }
  }

  /**
   * Pushes one alert transition on the {@code alert} topic (14 §5.2 {@code {"event": {...}}}, the
   * 4.13 event schema; WO-25): to the subscriptions without {@code env}/{@code app} and to those
   * that match. Never blocks (the outbox queues it like a control frame, so it is never trimmed);
   * serialized because the alert engine calls it from its thread and from the instance state
   * listener.
   *
   * @param appId the alert's application, null for a server self alert
   * @param env the alert's environment, null for a server self alert
   * @param tMs server time of the transition
   * @param eventJson the event object as JSON
   */
  public synchronized void publishAlert(Integer appId, String env, long tMs, String eventJson) {
    emit(ALERT_KEY, new AlertPush(appId, env, tMs, "{\"event\":" + eventJson + "}"));
  }

  /**
   * One {@code admin} push (WO-26, 14 §5.2 {@code {"change_id", "instance_id", "status"}}): a
   * command state transition ({@code commandId} set) or a configuration change result ({@code
   * changeId} set). The data is the push's {@code data} object, without collected data or secrets.
   *
   * @param changeId the configuration change, or null
   * @param commandId the command, or null
   * @param tMs server time of the transition
   * @param dataJson the {@code data} object
   */
  public synchronized void publishAdmin(
      String changeId, String commandId, long tMs, String dataJson) {
    emit(ADMIN_KEY, new AdminPush(changeId, commandId, tMs, dataJson));
  }

  /**
   * The active alerts per application, {@code [critical, warn]} (the alert engine after every
   * change of its active alerts, WO-25): the {@code firing} of the {@code overview} rows (14 §4.2
   * {@code {critical, warn}}). Applications without an entry have none.
   */
  public void updateFiring(Map<Integer, int[]> countsByApp) {
    Map<Integer, int[]> copy = new HashMap<>();
    countsByApp.forEach((app, c) -> copy.put(app, new int[] {c[0], c[1]}));
    firing = copy;
  }

  /** The {@code firing} of an overview row, or null before the alert engine reported. */
  private Map<String, Integer> firingOf(int appId) {
    Map<Integer, int[]> f = firing;
    if (f == null) {
      return null;
    }
    int[] c = f.get(appId);
    Map<String, Integer> out = new LinkedHashMap<>();
    out.put("critical", c == null ? 0 : c[0]);
    out.put("warn", c == null ? 0 : c[1]);
    return out;
  }

  /** Keeps the state of instances that lose their session (listener: no blocking). */
  @EventListener
  public void onInstanceState(InstanceStateChange change) {
    long at = change.at().toEpochMilli();
    known.put(
        change.instanceId(), new KnownInstance(change.appId(), change.env(), change.to(), at));
    tracked.put(change.appId(), new TrackedApp(change.env(), clock.millis()));
  }

  // --- Subscriptions ----------------------------------------------------------------------------

  /**
   * Starts the pushes of one subscription into {@code outbox}.
   *
   * @param id the subscription id of the frame
   * @throws InvalidTopicParams for an unknown application or an {@code env} that is not the
   *     application's
   */
  public BusSubscription subscribe(String id, TopicRequest request, SubscriberOutbox outbox) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(outbox, "outbox");
    TopicKey key = key(request);
    Consumer<Object> consumer =
        switch (request) {
          case TopicRequest.Xlog x -> {
            XlogSubscription s = new XlogSubscription(id, x, outbox, json);
            yield v -> s.onBundle((XlogBundle) v);
          }
          case TopicRequest.Counter c ->
              v -> {
                CounterTick t = (CounterTick) v;
                outbox.offerLatest(
                    id,
                    ServerFrames.push(
                        id, Topic.COUNTER, t.tMs(), t.data().get(c.kind()), null, null));
              };
          case TopicRequest.Overview o ->
              v -> {
                OverviewTick t = (OverviewTick) v;
                outbox.offerLatest(
                    id,
                    ServerFrames.push(
                        id, Topic.OVERVIEW, t.tMs(), t.data().get(o.kind()), null, null));
              };
          case TopicRequest.Active a ->
              v -> {
                ActiveTick t = (ActiveTick) v;
                String data =
                    json.writeValueAsString(sources.active(a.app(), a.kind(), a.instances()));
                outbox.offerLatest(
                    id, ServerFrames.push(id, Topic.ACTIVE, t.tMs(), data, null, null));
              };
          case TopicRequest.Alert al ->
              v -> {
                AlertPush p = (AlertPush) v;
                if ((al.env() == null || al.env().equals(p.env()))
                    && (al.app() == null || al.app().equals(p.appId()))) {
                  outbox.offerControl(
                      ServerFrames.push(id, Topic.ALERT, p.tMs(), p.dataJson(), null, null));
                }
              };
          case TopicRequest.Admin ad ->
              v -> {
                AdminPush p = (AdminPush) v;
                boolean unfiltered = ad.changeId() == null && ad.commandId() == null;
                if (unfiltered
                    || (ad.changeId() != null && ad.changeId().equals(p.changeId()))
                    || (ad.commandId() != null && ad.commandId().equals(p.commandId()))) {
                  outbox.offerControl(
                      ServerFrames.push(id, Topic.ADMIN, p.tMs(), p.dataJson(), null, null));
                }
              };
        };
    Disposable d = consumer == null ? null : attach(key, consumer);
    subscribers.get(request.topic()).incrementAndGet();
    return new Handle(id, request.topic(), key, d, outbox);
  }

  private final class Handle implements BusSubscription {
    private final String id;
    private final Topic topic;
    private final TopicKey key;
    private final Disposable disposable;
    private final SubscriberOutbox outbox;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    Handle(String id, Topic topic, TopicKey key, Disposable disposable, SubscriberOutbox outbox) {
      this.id = id;
      this.topic = topic;
      this.key = key;
      this.disposable = disposable;
      this.outbox = outbox;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public Topic topic() {
      return topic;
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      if (disposable != null) {
        disposable.dispose();
        sinks.computeIfPresent(key, (k, s) -> s.currentSubscriberCount() == 0 ? null : s);
      }
      outbox.forget(id);
      subscribers.get(topic).decrementAndGet();
    }
  }

  /**
   * Checks the application of a request before the WebSocket handler acknowledges it (so that an
   * {@code ack} is never followed by an {@code error} for the same subscription).
   *
   * @throws InvalidTopicParams as {@link #subscribe}
   */
  public void validate(TopicRequest request) {
    key(request);
  }

  /** The sink key of a request; validates the application. Null for the topics without data. */
  private TopicKey key(TopicRequest request) {
    return switch (request) {
      case TopicRequest.Overview o ->
          new TopicKey(Topic.OVERVIEW, 0, o.env() == null ? "" : o.env());
      case TopicRequest.Alert a -> ALERT_KEY;
      case TopicRequest.Admin a -> ADMIN_KEY;
      case TopicRequest.Xlog x -> appKey(Topic.XLOG, x.app(), x.env());
      case TopicRequest.Counter c -> appKey(Topic.COUNTER, c.app(), c.env());
      case TopicRequest.Active a -> appKey(Topic.ACTIVE, a.app(), a.env());
    };
  }

  private TopicKey appKey(Topic topic, int appId, String env) {
    AppDirectory.AppInfo app = apps.getIfPresent(appId);
    if (app == null) {
      Optional<AppDirectory.AppInfo> read;
      try {
        read = sources.app(appId);
      } catch (RuntimeException e) {
        throw new InvalidTopicParams("app", "unavailable");
      }
      app = read.orElseThrow(() -> new InvalidTopicParams("app", "unknown"));
      apps.put(appId, app);
      appMisses.invalidate(appId);
    }
    if (env != null && !env.equals(app.env())) {
      throw new InvalidTopicParams("env", "not_the_application_env");
    }
    return new TopicKey(topic, appId, app.env());
  }

  private Disposable attach(TopicKey key, Consumer<Object> consumer) {
    Disposable[] out = new Disposable[1];
    sinks.compute(
        key,
        (k, s) -> {
          Sinks.Many<Object> sink =
              s != null ? s : Sinks.many().multicast().onBackpressureBuffer(SINK_BUFFER, false);
          out[0] =
              sink.asFlux()
                  .subscribe(
                      v -> {
                        try {
                          consumer.accept(v);
                        } catch (RuntimeException e) {
                          failure("A realtime subscription failed on " + k.topic().wire(), e);
                        }
                      },
                      e -> failure("A realtime sink failed", e));
          return sink;
        });
    return out[0];
  }

  private boolean subscribed(TopicKey key) {
    Sinks.Many<Object> sink = sinks.get(key);
    return sink != null && sink.currentSubscriberCount() > 0;
  }

  private void emit(TopicKey key, Object value) {
    Sinks.Many<Object> sink = sinks.get(key);
    if (sink == null || sink.currentSubscriberCount() == 0) {
      return;
    }
    Sinks.EmitResult r = sink.tryEmitNext(value);
    if (r.isFailure()) {
      droppedSink.increment(value instanceof XlogBundle b ? b.points().size() : 0);
    }
  }

  /** The outbox metric hook: points of pushes dropped by backpressure. */
  public void backpressureDropped(long points) {
    droppedBackpressure.increment((double) points);
  }

  /** A new outbox for a connection, its drops counted by this bus. */
  public SubscriberOutbox outbox(Runnable wakeUp) {
    return new SubscriberOutbox(wakeUp, this::backpressureDropped);
  }

  // --- Bus threads ------------------------------------------------------------------------------

  /** One {@code xlog} round: drain, count, emit (thread {@code argos-realtime-xlog}). */
  void bundleTick(long nowMs) {
    PendingMetrics m;
    while ((m = metricQueue.poll()) != null) {
      int excluded = counters.addMetrics(m.appId(), m.instanceId(), m.points(), nowMs);
      if (excluded > 0) {
        droppedMetric.increment(excluded);
      }
    }
    for (XlogBundle b : bundler.drain(nowMs)) {
      int excluded = counters.add(b.appId(), b.points(), nowMs);
      if (excluded > 0) {
        droppedCounterLate.increment(excluded);
      }
      tracked.put(b.appId(), new TrackedApp(b.env(), nowMs));
      TopicKey key = new TopicKey(Topic.XLOG, b.appId(), b.env());
      if (!subscribed(key)) {
        continue;
      }
      emit(key, b.resolved(slowThreshold(b.appId()), serviceNames(b)));
    }
  }

  /** One periodic round: close the counter window, emit the 5-second topics. */
  void periodicTick(long nowMs) {
    housekeeping(nowMs);
    CounterAggregator.Window w = counters.close(nowMs);
    if (w == null) {
      return;
    }
    for (TopicKey key : List.copyOf(sinks.keySet())) {
      if (!subscribed(key)) {
        continue;
      }
      switch (key.topic()) {
        case COUNTER -> emit(key, counterTick(key.appId(), w, nowMs));
        case ACTIVE -> emit(key, new ActiveTick(nowMs));
        case OVERVIEW -> emit(key, overviewTick(key.env(), w, nowMs));
        default -> {}
      }
    }
  }

  private CounterTick counterTick(int appId, CounterAggregator.Window w, long nowMs) {
    Collection<AgentSession> sessions = sources.sessions(appId);
    Long active = activeSum(sessions, nowMs);
    List<TopicPayloads.Instance> rows = instances(appId, sessions, nowMs);
    CounterAggregator.Jvm jvm = w.jvm(appId);
    Map<TxKind, String> data = new EnumMap<>(TxKind.class);
    for (TxKind k : KINDS) {
      CounterAggregator.Stats s = w.stats(appId, k);
      boolean live = !sessions.isEmpty() || s.count() > 0;
      data.put(
          k,
          json.writeValueAsString(
              new TopicPayloads.Counter(
                  live ? s.tps() : null,
                  s.avgMs(),
                  s.p95Ms(),
                  s.p99Ms(),
                  s.errorRate(),
                  active,
                  jvm.heapUsed(),
                  jvm.gcPauseMs(),
                  jvm.cpu(),
                  rows)));
    }
    return new CounterTick(nowMs, data);
  }

  private OverviewTick overviewTick(String env, CounterAggregator.Window w, long nowMs) {
    List<Integer> ids = new ArrayList<>();
    tracked.forEach(
        (app, t) -> {
          if (env.isEmpty() || env.equals(t.env())) {
            ids.add(app);
          }
        });
    ids.sort(Comparator.naturalOrder());
    List<Integer> shown = ids.size() > MAX_OVERVIEW_APPS ? ids.subList(0, MAX_OVERVIEW_APPS) : ids;
    Map<Integer, Collection<AgentSession>> sessions = new LinkedHashMap<>();
    for (int app : shown) {
      sessions.put(app, sources.sessions(app));
    }
    Map<TxKind, String> data = new EnumMap<>(TxKind.class);
    for (TxKind k : KINDS) {
      List<TopicPayloads.OverviewApp> rows = new ArrayList<>(shown.size());
      for (int app : shown) {
        Collection<AgentSession> live = sessions.get(app);
        CounterAggregator.Stats s = w.stats(app, k);
        rows.add(
            new TopicPayloads.OverviewApp(
                app,
                worst(app, live),
                !live.isEmpty() || s.count() > 0 ? s.tps() : null,
                s.p95Ms(),
                s.errorRate(),
                activeSum(live, nowMs),
                firingOf(app)));
      }
      data.put(k, json.writeValueAsString(new TopicPayloads.Overview(rows)));
    }
    return new OverviewTick(nowMs, data);
  }

  /** The active transactions of the recent snapshots, null without any (D-23). */
  private static Long activeSum(Collection<AgentSession> sessions, long nowMs) {
    long sum = 0;
    boolean any = false;
    for (AgentSession s : sessions) {
      Long a = active(s, nowMs);
      if (a != null) {
        sum += a;
        any = true;
      }
    }
    return any ? sum : null;
  }

  private static Long active(AgentSession s, long nowMs) {
    AgentSession.ActiveSnapshot snap = s.latestSnapshot();
    if (snap == null || nowMs - snap.receivedAtMs() > SNAPSHOT_MAX_AGE_MS) {
      return null;
    }
    return (long) snap.snapshot().getTotalActive();
  }

  /** Every known instance of an application: live sessions, then those that lost theirs. */
  private List<TopicPayloads.Instance> instances(
      int appId, Collection<AgentSession> sessions, long nowMs) {
    List<TopicPayloads.Instance> rows = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    List<AgentSession> sorted = new ArrayList<>(sessions);
    sorted.sort(Comparator.comparingInt(AgentSession::instanceId));
    for (AgentSession s : sorted) {
      seen.add(s.instanceId());
      rows.add(
          new TopicPayloads.Instance(
              s.instanceId(), active(s, nowMs), sources.state(s.instanceId())));
    }
    List<Integer> lost = new ArrayList<>();
    known.forEach(
        (instance, k) -> {
          if (k.appId() == appId && !seen.contains(instance)) {
            lost.add(instance);
          }
        });
    lost.sort(Comparator.naturalOrder());
    for (int instance : lost) {
      KnownInstance k = known.get(instance);
      if (k != null) {
        rows.add(new TopicPayloads.Instance(instance, null, k.state().name()));
      }
    }
    return rows;
  }

  /** The worst instance state of an application (DOWN > STALE > WARN > UP), DOWN with none. */
  private String worst(int appId, Collection<AgentSession> sessions) {
    InstanceState worst = null;
    Set<Integer> seen = new HashSet<>();
    for (AgentSession s : sessions) {
      seen.add(s.instanceId());
      worst = worse(worst, parse(sources.state(s.instanceId())));
    }
    for (Map.Entry<Integer, KnownInstance> e : known.entrySet()) {
      if (e.getValue().appId() == appId && !seen.contains(e.getKey())) {
        worst = worse(worst, e.getValue().state());
      }
    }
    return (worst == null ? InstanceState.DOWN : worst).name();
  }

  private static InstanceState parse(String state) {
    try {
      return state == null ? InstanceState.DOWN : InstanceState.valueOf(state);
    } catch (IllegalArgumentException e) {
      return InstanceState.DOWN;
    }
  }

  private static InstanceState worse(InstanceState a, InstanceState b) {
    if (a == null) {
      return b;
    }
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(InstanceState s) {
    return switch (s) {
      case UP -> 0;
      case WARN -> 1;
      case STALE -> 2;
      case DOWN -> 3;
    };
  }

  private void housekeeping(long nowMs) {
    long downKeep = Duration.ofMinutes(DOWN_KEEP_MINUTES).toMillis();
    known
        .entrySet()
        .removeIf(
            e ->
                nowMs - e.getValue().atMs()
                    > (e.getValue().state() == InstanceState.DOWN ? downKeep : TRACK_MS * 6));
    tracked.entrySet().removeIf(e -> nowMs - e.getValue().seenMs() > TRACK_MS);
  }

  /** The application's slow threshold (cached; the default when it cannot be read). */
  int slowThreshold(int appId) {
    AppDirectory.AppInfo app = apps.getIfPresent(appId);
    if (app != null) {
      return app.slowThresholdMs();
    }
    if (appMisses.getIfPresent(appId) != null) {
      return DEFAULT_SLOW_THRESHOLD_MS;
    }
    try {
      Optional<AppDirectory.AppInfo> read = sources.app(appId);
      if (read.isPresent()) {
        apps.put(appId, read.get());
        return read.get().slowThresholdMs();
      }
    } catch (RuntimeException e) {
      failure("Reading application settings for the realtime bus failed", e);
    }
    appMisses.put(appId, Boolean.TRUE);
    return DEFAULT_SLOW_THRESHOLD_MS;
  }

  /** The known names of the services of a bundle (cached; misses looked up in one call). */
  private Map<Long, String> serviceNames(XlogBundle b) {
    Map<Long, String> out = new LinkedHashMap<>();
    Set<Long> missing = new HashSet<>();
    for (BusPoint p : b.points()) {
      long id = p.serviceId();
      if (id == 0 || out.containsKey(id) || missing.contains(id)) {
        continue;
      }
      NameKey k = new NameKey(b.appId(), id);
      String name = names.getIfPresent(k);
      if (name != null) {
        out.put(id, name);
      } else if (nameMisses.getIfPresent(k) == null) {
        missing.add(id);
      }
    }
    if (missing.isEmpty()) {
      return out;
    }
    try {
      Map<Long, String> read = sources.serviceNames(b.appId(), missing);
      for (Long id : missing) {
        String raw = read.get(id);
        NameKey k = new NameKey(b.appId(), id);
        if (raw == null) {
          nameMisses.put(k, Boolean.TRUE);
        } else {
          String clean = UntrustedStrings.name(raw).value();
          names.put(k, clean);
          out.put(id, clean);
        }
      }
    } catch (RuntimeException e) {
      failure("Reading service names for the realtime bus failed", e);
      missing.forEach(id -> nameMisses.put(new NameKey(b.appId(), id), Boolean.TRUE));
    }
    return out;
  }

  /** Logs a failure of a bus round at most once a minute (the class name only). */
  private void failure(String what, Throwable e) {
    long now = System.currentTimeMillis();
    long last = lastFailureLogMs.get();
    if (now - last >= 60_000 && lastFailureLogMs.compareAndSet(last, now)) {
      LOG.warn("{}: {}", what, e.getClass().getName());
    }
  }

  // --- SmartLifecycle ---------------------------------------------------------------------------

  @Override
  public synchronized void start() {
    if (xlogThread != null) {
      return;
    }
    xlogThread = Executors.newSingleThreadScheduledExecutor(r -> thread(r, "argos-realtime-xlog"));
    periodicThread =
        Executors.newSingleThreadScheduledExecutor(r -> thread(r, "argos-realtime-periodic"));
    var unusedXlog =
        xlogThread.scheduleAtFixedRate(
            () -> round(() -> bundleTick(clock.millis())),
            BUNDLE_MS,
            BUNDLE_MS,
            TimeUnit.MILLISECONDS);
    var unusedPeriodic =
        periodicThread.scheduleAtFixedRate(
            () -> round(() -> periodicTick(clock.millis())),
            PERIODIC_MS,
            PERIODIC_MS,
            TimeUnit.MILLISECONDS);
  }

  private void round(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      failure("A realtime bus round failed", e);
    }
  }

  private static Thread thread(Runnable r, String name) {
    Thread t = new Thread(r, name);
    t.setDaemon(true);
    return t;
  }

  @Override
  public synchronized void stop() {
    if (xlogThread != null) {
      xlogThread.shutdownNow();
      periodicThread.shutdownNow();
      xlogThread = null;
      periodicThread = null;
    }
  }

  @Override
  public boolean isRunning() {
    return xlogThread != null;
  }

  /** Subscriptions of a topic now. */
  int subscriptions(Topic topic) {
    return subscribers.get(topic).get();
  }

  /**
   * JVM samples dropped (no room in the queue, beyond the bounds, outside the open window): {@code
   * argos_realtime_dropped_total{reason="metric"}} (ADR-0010 C-220 (9)).
   */
  long metricsDropped() {
    return (long) droppedMetric.count();
  }

  /** Points waiting for the next bundle. */
  int pendingPoints() {
    return bundler.pending();
  }
}
