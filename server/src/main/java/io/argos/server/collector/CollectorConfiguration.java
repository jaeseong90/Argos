/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.server.collector.api.DedupWindow;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.auth.IpAuthFailureLimiter;
import io.argos.server.collector.auth.TokenAuthInterceptor;
import io.argos.server.collector.auth.TokenCache;
import io.argos.server.collector.batch.QuotaTrimmer;
import io.argos.server.collector.batch.RequiredEnumFilter;
import io.argos.server.collector.dedup.InMemoryDedupWindow;
import io.argos.server.collector.grpc.AgentServiceDefinition;
import io.argos.server.collector.grpc.CollectorListenerPhase;
import io.argos.server.collector.grpc.TransportRejectionCounter;
import io.argos.server.collector.limits.CollectorLimits;
import io.argos.server.collector.limits.InstanceQuotas;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.collector.testing.IngestFaultInjector;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import io.argos.server.config.TestingHooks;
import io.argos.server.ingest.api.ApdexProvider;
import io.argos.server.ingest.api.DictionaryIntake;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.ops.tls.CertReloader;
import io.argos.server.session.AgentVersionRange;
import io.argos.server.session.CachingSessionStore;
import io.argos.server.session.HelloService;
import io.argos.server.session.InMemorySessionRegistry;
import io.argos.server.session.InstanceRetentionJob;
import io.argos.server.session.InstanceRetentionStore;
import io.argos.server.session.InstanceStateMachine;
import io.argos.server.session.InstanceStateStore;
import io.argos.server.session.InstanceStateTracker;
import io.argos.server.session.InstanceStateWriter;
import io.argos.server.session.RegistrySessionMessenger;
import io.argos.server.session.SessionClockOffsets;
import io.argos.server.session.SessionHandler;
import io.argos.server.session.SessionMonitor;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.SessionSettings;
import io.argos.server.session.SessionStore;
import io.argos.server.session.api.ClockOffsetProvider;
import io.argos.server.storage.api.ClickHouseWriter;
import io.argos.server.storage.postgres.token.AgentTokenRepository;
import io.argos.server.wal.api.WalWriter;
import io.grpc.ServerInterceptors;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of the gRPC collector and the minimal agent session (WO-21; 12 §3.1-§3.3, §4.1,
 * ADR-0010 C-128). Each contract another lane or work order consumes is its own bean: {@link
 * DedupWindow} (restored by the WAL recovery before the listener starts), {@link TokenLookup}
 * (invalidated by WO-26), {@link SessionRegistry} ({@code closeByToken} for WO-26), {@link
 * ClockOffsetProvider} (the C-84 correction) and {@link
 * io.argos.server.session.api.SessionMessenger} (dictionary requests). The collector itself
 * consumes the ingest path's {@link WalWriter}, {@link IngestPipeline}, {@link ApdexProvider} and
 * {@link DictionaryIntake}, and the writers' queue fill ({@link ClickHouseWriter}, optional) for
 * the ack throttle hint.
 *
 * <p>Interceptor order (12 §3.1, fixed): address block → token → session → metrics; limits,
 * deduplication, the required-enum filter and the quota run in {@link AgentServiceImpl} in the 12
 * §3.2 order (the quota after deduplication, so that a duplicate never consumes quota).
 */
@Configuration(proxyBeanMethods = false)
public class CollectorConfiguration {

  /** The collector's meters. */
  @Bean
  public CollectorMetrics collectorMetrics(MeterRegistry registry) {
    return new CollectorMetrics(registry);
  }

  /** The shutdown flag. */
  @Bean
  public CollectorState collectorState() {
    return new CollectorState();
  }

  /** The configured caps (11 §4.2, §4.4). */
  @Bean
  public LimitsChecker collectorLimitsChecker(ArgosProperties properties) {
    return new LimitsChecker(CollectorLimits.of(properties.collector()));
  }

  /** The batch deduplication window (C-3), restored by the WAL recovery. */
  @Bean
  public InMemoryDedupWindow dedupWindow() {
    return new InMemoryDedupWindow();
  }

  /** The agent token lookup (cache over {@code agent_tokens}). */
  @Bean
  public TokenCache tokenLookup(AgentTokenRepository tokens) {
    return new TokenCache(tokens::findByHash);
  }

  /** The live sessions. */
  @Bean
  public InMemorySessionRegistry sessionRegistry(CollectorMetrics metrics) {
    InMemorySessionRegistry registry = new InMemorySessionRegistry();
    metrics.bindActiveSessions(registry::size);
    return registry;
  }

  /**
   * The PostgreSQL writer of instance states and events (WO-22); discards writes when no {@link
   * InstanceStateStore} exists (a collector test context).
   */
  @Bean(destroyMethod = "close")
  public InstanceStateWriter instanceStateWriter(
      ObjectProvider<InstanceStateStore> store, MeterRegistry meters) {
    return new InstanceStateWriter(store.getIfAvailable(), meters);
  }

  /**
   * The instance states (12 §4.2, WO-22): STALE, DOWN and the {@code AgentBye} grace from {@code
   * argos.collector.stale-after}, {@code down-after} and {@code bye-grace}; transitions published
   * as {@link io.argos.server.session.api.InstanceStateChange}.
   */
  @Bean
  public InstanceStateTracker instanceStateTracker(
      ArgosProperties properties,
      Clock clock,
      SessionRegistry registry,
      ServerBuild build,
      InstanceStateWriter writer,
      ApplicationEventPublisher events) {
    ArgosProperties.Collector c = properties.collector();
    return new InstanceStateTracker(
        new InstanceStateMachine(
            clock, c.staleAfter(), c.downAfter(), c.byeGrace(), InstanceStateMachine.SKEW_WARN_MS),
        registry,
        new AgentVersionRange(build.version()),
        writer,
        events::publishEvent);
  }

  /** The 10 s state monitor, the server's start and stop events and the instance meters. */
  @Bean(destroyMethod = "close")
  public SessionMonitor sessionMonitor(
      InstanceStateTracker tracker,
      InstanceStateWriter writer,
      ObjectProvider<InstanceStateStore> store,
      Clock clock,
      MeterRegistry meters) {
    return new SessionMonitor(tracker, writer, store.getIfAvailable(), clock, meters);
  }

  /** The clock offsets of live instances (input of the C-84 correction). */
  @Bean
  public SessionClockOffsets clockOffsetProvider(SessionRegistry registry) {
    return new SessionClockOffsets(registry);
  }

  /** Server-to-agent messages of the ingest path. */
  @Bean
  public RegistrySessionMessenger sessionMessenger(SessionRegistry registry) {
    return new RegistrySessionMessenger(registry);
  }

  /** The per-address authentication failure block (16 §2.5). */
  @Bean
  public IpAuthFailureLimiter ipAuthFailureLimiter(
      ArgosProperties properties, Clock clock, CollectorMetrics metrics) {
    ArgosProperties.Collector.AuthFail authFail = properties.collector().authFail();
    return new IpAuthFailureLimiter(
        authFail.perIpPerMinute(), Duration.ofMinutes(authFail.blockMinutes()), clock, metrics);
  }

  /** The five data RPCs. */
  @Bean
  public AgentServiceImpl agentService(
      ArgosProperties properties,
      LimitsChecker limits,
      DedupWindow dedup,
      CollectorMetrics metrics,
      WalWriter wal,
      IngestPipeline pipeline,
      ClockOffsetProvider offsets,
      ApdexProvider apdex,
      ObjectProvider<ClickHouseWriter> writer,
      Clock clock,
      InstanceStateTracker states,
      ServerBuild build,
      ObjectProvider<TestingHooks> testingHooks) {
    AgentServiceImpl service =
        new AgentServiceImpl(
            limits,
            dedup,
            new RequiredEnumFilter(metrics),
            new QuotaTrimmer(new InstanceQuotas(properties.collector().quota(), System::nanoTime)),
            wal,
            pipeline,
            offsets,
            apdex,
            new ThrottleAdvisor(
                wal,
                () -> {
                  ClickHouseWriter w = writer.getIfAvailable();
                  return w == null ? 0 : w.maxQueueRatio();
                },
                properties.wal().warnRatio()),
            metrics,
            clock);
    service.instanceStates(states);
    // 18 §19: development builds only (a release build ignores argos.testing.*, TestingHooks).
    service.ingestFaults(
        IngestFaultInjector.from(
            testingHooks.getIfAvailable(() -> new TestingHooks(properties, build))));
    return service;
  }

  /**
   * The 11 §5 checks, reading apps and instances through the registration cache (12 §3.9: known
   * instances start sessions while PostgreSQL is down and the token is cached).
   */
  @Bean
  public HelloService helloService(
      ArgosProperties properties,
      SessionStore store,
      SessionRegistry registry,
      ServerBuild build,
      LimitsChecker limits,
      CollectorState state,
      CollectorMetrics metrics,
      Clock clock) {
    return new HelloService(
        new CachingSessionStore(store),
        registry,
        SessionSettings.of(properties, limits.limits().toProto()),
        new AgentVersionRange(build.version()),
        limits,
        state,
        metrics,
        clock);
  }

  /**
   * The Hello timeout timer (not a {@code ScheduledExecutorService} bean: scheduling stays
   * Spring's).
   */
  @Bean(destroyMethod = "close")
  public SessionTimer collectorSessionTimer() {
    return new SessionTimer();
  }

  /**
   * The session stream; its idle timeout is 3 heartbeat intervals ({@code
   * argos.collector.heartbeat-interval}, 90 s by default; ADR-0002 C-19, 11 open item 6).
   */
  @Bean
  public SessionHandler sessionHandler(
      ArgosProperties properties,
      HelloService hellos,
      SessionRegistry registry,
      SessionStore store,
      LimitsChecker limits,
      DictionaryIntake dictionary,
      CollectorMetrics metrics,
      Clock clock,
      SessionTimer timer,
      InstanceStateTracker states) {
    return new SessionHandler(
        hellos,
        registry,
        store,
        limits,
        dictionary,
        metrics,
        clock,
        timer.executor(),
        SessionSettings.HELLO_TIMEOUT,
        states,
        properties.collector().heartbeatInterval().multipliedBy(SessionSettings.IDLE_HEARTBEATS),
        System::nanoTime);
  }

  /**
   * The daily instance retention (12 §4.3: {@code argos.instance.archive-after}, {@code
   * purge-after}); does nothing without an {@link InstanceRetentionStore} (a collector test
   * context).
   */
  @Bean
  public InstanceRetentionJob instanceRetentionJob(
      ArgosProperties properties,
      ObjectProvider<InstanceRetentionStore> store,
      SessionRegistry registry,
      Clock clock) {
    ArgosProperties.Instance i = properties.instance();
    return new InstanceRetentionJob(
        store.getIfAvailable(), registry, clock, i.archiveAfter(), i.purgeAfter());
  }

  /** The listener with the interceptor chain, its start and its 12 §3.9 stop. */
  @Bean
  public CollectorLifecycle collectorLifecycle(
      ArgosProperties properties,
      AgentServiceImpl service,
      SessionHandler sessions,
      IpAuthFailureLimiter limiter,
      TokenLookup tokens,
      SessionRegistry registry,
      CollectorState state,
      CollectorMetrics metrics,
      Clock clock,
      ObjectProvider<CertReloader> certificates) {
    ArgosProperties.Collector c = properties.collector();
    CertReloader tls = certificates.getIfAvailable(() -> CertReloader.standalone(properties));
    return new CollectorLifecycle(
        c.bind(),
        c.port(),
        c.maxMessageBytes(),
        c.tls().enabled(),
        tls::grpcContext,
        c.allowPlaintext(),
        ServerInterceptors.interceptForward(
            AgentServiceDefinition.of(service, sessions, c.maxMessageBytes()),
            limiter,
            new TokenAuthInterceptor(tokens, limiter, clock, metrics),
            new SessionInterceptor(registry, state, metrics),
            new MetricsInterceptor(metrics)),
        new TransportRejectionCounter(metrics),
        registry,
        state,
        Duration.ofSeconds(CollectorLifecycle.DRAIN_SECONDS));
  }

  /** The {@code LISTENERS} start step. */
  @Bean
  public CollectorListenerPhase collectorListenerPhase(CollectorLifecycle lifecycle) {
    return new CollectorListenerPhase(lifecycle);
  }

  /** The single-thread timer of the Hello timeouts ({@code argos-session-timer}). */
  public static final class SessionTimer implements AutoCloseable {
    private final ScheduledExecutorService executor;

    SessionTimer() {
      AtomicInteger next = new AtomicInteger(1);
      this.executor =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "argos-session-timer-" + next.getAndIncrement());
                t.setDaemon(true);
                return t;
              });
    }

    /** The executor. */
    public ScheduledExecutorService executor() {
      return executor;
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }
}
