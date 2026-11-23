/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.collector;

import io.argos.server.collector.CollectorConfiguration;
import io.argos.server.collector.CollectorLifecycle;
import io.argos.server.collector.api.DedupWindow;
import io.argos.server.collector.grpc.CollectorListenerPhase;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import io.argos.server.ingest.api.ApdexProvider;
import io.argos.server.ingest.api.DictionaryIntake;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.ops.migration.PostgresMigrationPhase;
import io.argos.server.storage.postgres.app.AppRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository;
import io.argos.server.storage.postgres.instance.InstanceRepository;
import io.argos.server.storage.postgres.instance.PostgresSessionStore;
import io.argos.server.storage.postgres.token.AgentTokenRepository;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.TestCertificates;
import io.argos.server.wal.api.WalWriter;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The gRPC collector of WO-21 as its tests run it: the production {@link CollectorConfiguration}
 * (interceptor chain, {@code AgentServiceImpl}, the Hello checks, the session registry, the
 * listener phase) in a Spring context of its own, on a real Netty listener (TLS with a test CA
 * generated at run time, or plaintext) on 127.0.0.1, against the real PostgreSQL of a {@link
 * ServerTestEnvironment} (V001+ migrated by the server's own Flyway step, the runtime account
 * {@code argos_app}). The ingest path is replaced by a {@link StubWalWriter} and a {@link
 * RecordingIngest}, so that protocol tests do not depend on the durable path (lane durable-ingest).
 *
 * <p>{@link #restart()} stops the collector (12 §3.9) and starts a new context on the same port
 * with a new session registry and deduplication window, restored from the stub WAL's records as
 * {@code WalRecovery} restores them: a server restart for the contract vectors.
 */
public final class CollectorHarness implements AutoCloseable {

  private static final Set<ServerTestEnvironment> MIGRATED =
      Collections.newSetFromMap(new WeakHashMap<>());
  private static volatile TestCertificates.TestPki sharedPki;

  private final ServerTestEnvironment env;
  private final Map<String, String> properties;
  private final Clock clock;
  private final boolean tls;
  private final String serverVersion;
  private final Object meterRegistry;
  private final StubWalWriter wal = new StubWalWriter();
  private final RecordingIngest ingest = new RecordingIngest();
  private final int port;
  private final TestCertificates.TestPki pki;
  private AnnotationConfigApplicationContext context;

  private CollectorHarness(Builder b) {
    this.env = b.env;
    this.clock = b.clock;
    this.tls = b.tls;
    this.serverVersion = b.serverVersion;
    this.meterRegistry = b.meterRegistry != null ? b.meterRegistry : newSimpleMeterRegistry();
    this.port = freePort();
    this.pki = tls ? pki() : null;
    Map<String, String> p = new LinkedHashMap<>(env.springProperties());
    p.put("argos.collector.bind", "127.0.0.1");
    p.put("argos.collector.port", Integer.toString(port));
    p.put("argos.collector.tls.enabled", Boolean.toString(tls));
    if (tls) {
      p.put("argos.collector.tls.cert-file", pki.serverCertificate().toString());
      p.put("argos.collector.tls.key-file", pki.serverKey().toString());
    }
    p.putAll(b.properties);
    this.properties = Collections.unmodifiableMap(p);
  }

  /** A harness on {@code env}. */
  public static Builder builder(ServerTestEnvironment env) {
    return new Builder(env);
  }

  /** Starts a harness with the defaults (TLS, system clock). */
  public static CollectorHarness start(ServerTestEnvironment env) {
    return builder(env).start();
  }

  private CollectorHarness startContext() {
    migrate();
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    ArgosProperties argos =
        new Binder(new MapConfigurationPropertySource(properties))
            .bindOrCreate("argos", ArgosProperties.class);
    ctx.registerBean(ArgosProperties.class, () -> argos);
    ctx.registerBean(Clock.class, () -> clock);
    registerMeterRegistry(ctx);
    ctx.registerBean(DataSource.class, () -> new EnvDataSource(env));
    ctx.registerBean(AgentTokenRepository.class);
    ctx.registerBean(AppRepository.class);
    ctx.registerBean(InstanceRepository.class);
    ctx.registerBean(PendingRegistrationRepository.class);
    ctx.registerBean(PostgresSessionStore.class);
    ctx.registerBean(ServerBuild.class, () -> new ServerBuild(serverVersion, "test", "1"));
    ctx.registerBean(WalWriter.class, () -> wal);
    ctx.registerBean(IngestPipeline.class, () -> ingest);
    DictionaryIntake intake = ingest.dictionaryIntake();
    ctx.registerBean(DictionaryIntake.class, () -> intake);
    ctx.registerBean(ApdexProvider.class, () -> (ApdexProvider) appId -> 500);
    ctx.register(CollectorConfiguration.class);
    ctx.refresh();
    DedupWindow dedup = ctx.getBean(DedupWindow.class);
    for (StubWalWriter.Appended a : wal.appended()) {
      dedup.restore(a.key());
    }
    try {
      ctx.getBean(CollectorListenerPhase.class).run();
    } catch (Exception e) {
      ctx.close();
      throw new IllegalStateException("The collector did not start: " + e.getMessage(), e);
    }
    this.context = ctx;
    return this;
  }

  @SuppressWarnings("unchecked")
  private void registerMeterRegistry(AnnotationConfigApplicationContext ctx) {
    Class<Object> type = (Class<Object>) meterRegistry.getClass();
    ctx.registerBean("meterRegistry", type, () -> meterRegistry);
  }

  private void migrate() {
    synchronized (MIGRATED) {
      if (MIGRATED.contains(env)) {
        return;
      }
      ArgosProperties argos =
          new Binder(new MapConfigurationPropertySource(env.springProperties()))
              .bindOrCreate("argos", ArgosProperties.class);
      try {
        new PostgresMigrationPhase(argos).run();
      } catch (Exception e) {
        throw new IllegalStateException("PostgreSQL migration failed: " + e.getMessage(), e);
      }
      MIGRATED.add(env);
    }
  }

  /** Stops the collector (12 §3.9) and starts a new one on the same port (a server restart). */
  public void restart() {
    stop();
    startContext();
  }

  /** Stops the collector: SessionClose SERVER_SHUTDOWN, drain, shutdown (12 §3.9). */
  public void stop() {
    if (context != null) {
      context.getBean(CollectorLifecycle.class).shutdownCollector();
      context.close();
      context = null;
    }
  }

  /** The listener port (127.0.0.1). */
  public int port() {
    return port;
  }

  /** Whether the listener uses TLS. */
  public boolean tls() {
    return tls;
  }

  /** The test CA (PEM) the agents trust; {@code null} for a plaintext harness. */
  public Path caCertificate() {
    return pki == null ? null : pki.caCertificate();
  }

  /** The stub WAL. */
  public StubWalWriter wal() {
    return wal;
  }

  /** What the collector handed to the ingest path. */
  public RecordingIngest ingest() {
    return ingest;
  }

  /** A bean of the running collector context (the meter registry is {@code MeterRegistry}). */
  public <T> T bean(Class<T> type) {
    return Objects.requireNonNull(context, "not started").getBean(type);
  }

  /** The configuration of the collector. */
  public Map<String, String> properties() {
    return properties;
  }

  /** The environment. */
  public ServerTestEnvironment env() {
    return env;
  }

  /** A new channel to the listener ({@link #tls()} decides TLS). */
  public ManagedChannel channel() {
    return channel(AgentChannels.Options.defaults());
  }

  /** A new channel with {@code options}. */
  public ManagedChannel channel(AgentChannels.Options options) {
    return tls
        ? AgentChannels.tls("localhost", port, pki.caCertificate(), options)
        : AgentChannels.plaintext("127.0.0.1", port, options);
  }

  /** A fake agent builder on a new channel it owns, with {@code token}. */
  public FakeAgent.Builder agent(String token) {
    return FakeAgent.builder(channel()).ownsChannel(true).token(token);
  }

  /** A token for app ({@code name}, {@code env}), creating the app when it is missing. */
  public AgentTokens.IssuedToken token(String appName, String appEnv) {
    return token(appName, appEnv, AgentTokens.Spec.active());
  }

  /** A token with {@code spec} for app ({@code name}, {@code env}). */
  public AgentTokens.IssuedToken token(String appName, String appEnv, AgentTokens.Spec spec) {
    migrate();
    try (Connection c = env.connect(PgAccount.APP)) {
      int appId = AgentTokens.ensureApp(c, appName, appEnv);
      return AgentTokens.insert(c, appId, spec);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    stop();
  }

  private static synchronized TestCertificates.TestPki pki() {
    if (sharedPki == null) {
      try {
        java.nio.file.Path dir = Files.createTempDirectory("argos-collector-tls");
        sharedPki = TestCertificates.generate(dir);
        // The test PKI (a private key) lives as long as the test JVM, never longer: no key file
        // stays behind in build/tmp (gitleaks "dir" scans build/ too).
        Runtime.getRuntime()
            .addShutdownHook(Thread.ofPlatform().unstarted(() -> deleteRecursively(dir)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return sharedPki;
  }

  private static void deleteRecursively(java.nio.file.Path dir) {
    try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir)) {
      for (java.nio.file.Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      // best effort at JVM exit
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Object newSimpleMeterRegistry() {
    try {
      return Class.forName("io.micrometer.core.instrument.simple.SimpleMeterRegistry")
          .getConstructor()
          .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Micrometer is not on the test runtime classpath", e);
    }
  }

  /** Configuration of a {@link CollectorHarness}. */
  public static final class Builder {
    private final ServerTestEnvironment env;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private Clock clock = Clock.systemUTC();
    private boolean tls = true;
    private String serverVersion = "0.0.0-SNAPSHOT";
    private Object meterRegistry;

    private Builder(ServerTestEnvironment env) {
      this.env = Objects.requireNonNull(env, "env");
    }

    /** A collector setting ({@code argos.collector.*}, {@code argos.agent.*}). */
    public Builder property(String key, Object value) {
      properties.put(key, String.valueOf(value));
      return this;
    }

    /** The collector's clock (default the system clock). */
    public Builder clock(Clock value) {
      this.clock = Objects.requireNonNull(value, "clock");
      return this;
    }

    /**
     * A plaintext listener ({@code argos.collector.tls.enabled=false}) with {@code
     * allow-plaintext}.
     */
    public Builder plaintext(boolean allowPlaintext) {
      this.tls = false;
      properties.put("argos.collector.allow-plaintext", Boolean.toString(allowPlaintext));
      return this;
    }

    /** The server version of the 11 §5 agent version rule (default {@code 0.0.0-SNAPSHOT}). */
    public Builder serverVersion(String version) {
      this.serverVersion = version;
      return this;
    }

    /** The meter registry (a Micrometer {@code MeterRegistry}; default a new simple registry). */
    public Builder meterRegistry(Object registry) {
      this.meterRegistry = registry;
      return this;
    }

    /** Starts the collector. */
    public CollectorHarness start() {
      return new CollectorHarness(this).startContext();
    }
  }

  /** The runtime account {@code argos_app} as a data source without a pool. */
  static final class EnvDataSource implements DataSource {
    private final ServerTestEnvironment env;

    EnvDataSource(ServerTestEnvironment env) {
      this.env = env;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return env.connect(PgAccount.APP);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new SQLException("Only the argos_app account");
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getLogger("io.argos.server.testing.collector");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
