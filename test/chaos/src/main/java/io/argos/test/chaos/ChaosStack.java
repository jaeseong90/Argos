/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ContainerApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.sample.extstub.ExtStub;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.TestCertificates;
import io.argos.server.testing.load.LoadServerJvm;
import io.argos.server.testing.load.PrometheusText;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;

/**
 * The chaos stack of one run (18 §11.1): PostgreSQL and ClickHouse ({@link ServerTestEnvironment},
 * Testcontainers), the server boot jar as a child JVM with its TLS collector ({@link
 * LoadServerJvm}, WO-29), toxiproxy in front of the collector ({@link Toxiproxy}), one application
 * with its agent token, the ext-stub target, shop-boot3 with the agent on JDK 21 ({@link
 * AppLauncher}, the agent's {@code server.address} = the toxiproxy port) and a viewer signed in to
 * the API.
 *
 * <p>The stack is started once per run and shared by the scenarios in a fixed order. The server can
 * be stopped and started again on the same databases and WAL ({@link #stopServer}, {@link
 * #startServer}); the collector port does not change, so toxiproxy keeps its upstream. On POSIX the
 * stop is graceful (SIGTERM: the server closes the sessions with {@code SERVER_SHUTDOWN} and writes
 * its {@code stop} event); on Windows {@link Process#destroy} terminates the JVM at once, like a
 * crash ({@link #gracefulStops()}).
 *
 * <p>The collector's TLS certificate is the run's own, generated at run time in a private temporary
 * directory ({@link #secrets()}, deleted with the stack): its names are {@code localhost}, {@code
 * 127.0.0.1}, toxiproxy's network alias ({@link Toxiproxy#ALIAS}, container apps) and {@link
 * #COLLECTOR_NAME} (T-CH-006 resolves it through a hosts file), never {@link #MISMATCH_NAME}
 * (T-CH-007). The server watches {@link #ingestFaultFile()} ({@code
 * argos.testing.ingest-fault-file}, T-CH-004).
 *
 * <p>The probes ({@link #xlogCount()}, {@link #agentCounters()}, {@link #agentLog()}, {@link
 * #instances()}, ...) read the current <em>target</em> application: the main app, or a scenario's
 * own app ({@link #startApp}, {@link #use}) while that scenario runs.
 */
public final class ChaosStack implements AutoCloseable {

  /** The application of the run ({@code apps.name}). */
  public static final String APP_NAME = "argos-chaos";

  /** The application's environment ({@code apps.env}). */
  public static final String APP_ENV = "chaos";

  /** Heap of the server JVM. */
  static final String SERVER_HEAP = "1g";

  /** Heap of the sample app. */
  static final String APP_HEAP = "512m";

  /** The agent classes' package prefix (relocated libraries included): the agent heap. */
  public static final String AGENT_PACKAGE = "io.argos.agent.";

  /** The main app's instance name. */
  public static final String MAIN_INSTANCE = "chaos-1";

  /**
   * A name of the collector certificate that no DNS knows: T-CH-006 maps it to 127.0.0.1 through
   * {@code -Djdk.net.hosts.file} and removes it again.
   */
  public static final String COLLECTOR_NAME = "argos-chaos-collector.test";

  /** A name the collector certificate does not carry (T-CH-007), mapped to 127.0.0.1 likewise. */
  public static final String MISMATCH_NAME = "argos-chaos-mismatch.test";

  /**
   * One {@code docker exec}.
   *
   * @param exitCode the exit code (-1 when unknown)
   * @param stdout standard output
   * @param stderr standard error
   */
  public record Exec(int exitCode, String stdout, String stderr) {}

  private final ChaosOptions options;
  private final Path work;
  private final Consumer<String> log;
  private final String runId;
  private ServerTestEnvironment env;
  private LoadServerJvm server;
  private int serverStarts;
  private Duration lastServerStart = Duration.ofMinutes(1);
  private Map<String, String> serverProperties = Map.of();
  private Toxiproxy toxiproxy;
  private ExtStub extStub;
  private ChaosApp main;
  private ChaosApp target;
  private String mainToken;
  private Path secrets;
  private TestCertificates.TestPki pki;
  private Network dockerNetwork;
  private TestUsers.TestUser viewer;
  private ApiTestClient api;
  private String clickHouseContainer;

  private ChaosStack(ChaosOptions options, Path work, String runId, Consumer<String> log) {
    this.options = options;
    this.work = work;
    this.runId = runId;
    this.log = log;
  }

  /** Starts every part; on a failure stops what was started and rethrows. */
  public static ChaosStack start(
      ChaosOptions options, Path work, String runId, Consumer<String> log) throws Exception {
    ChaosStack stack = new ChaosStack(options, work, runId, log);
    try {
      stack.startAll();
      return stack;
    } catch (Exception | Error e) {
      stack.close();
      throw e;
    }
  }

  private void startAll() throws Exception {
    Files.createDirectories(work);
    require(options.bootJar(), "argos.chaos.bootJar (./gradlew :server:bootJar)");
    require(options.agentJar(), "argos.chaos.agentJar (./gradlew :agent:agent-dist:agentJar)");
    require(options.loadJar(), "argos.chaos.loadJar (./gradlew :sample-apps:load:sampleAppJar)");
    log.accept("starting PostgreSQL and ClickHouse");
    env = ServerTestEnvironment.builder().startupTimeout(Duration.ofMinutes(5)).start();
    clickHouseContainer = publishing(URI.create(env.clickHouseUrl()).getPort());
    boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    // The netem variant (T-CH-003) and the container app (T-CH-005) need toxiproxy on a bridge.
    boolean needBridge = options.netemImage() != null || options.selected(LogDiskFullFault.ID);
    Toxiproxy.Network network =
        Toxiproxy.defaultNetwork(linux, Toxiproxy.dockerDesktop(), needBridge);
    secrets = Files.createTempDirectory("argos-chaos-secrets");
    pki =
        TestCertificates.generate(
            secrets.resolve("collector-pki"),
            TestCertificates.KeyAlgorithm.EC,
            List.of("localhost", Toxiproxy.ALIAS, COLLECTOR_NAME),
            List.of("127.0.0.1"));
    Map<String, String> props = new LinkedHashMap<>();
    if (network == Toxiproxy.Network.BRIDGE && linux && !Toxiproxy.dockerDesktop()) {
      // A Linux Docker Engine container reaches the host through the bridge gateway, not through
      // loopback: the collector listens on every interface for the bridge mode (TLS and an
      // agent token still guard it; the port is random and lives for the run).
      props.put("argos.collector.bind", "0.0.0.0");
    }
    props.put("argos.collector.tls.cert-file", pki.serverCertificate().toString());
    props.put("argos.collector.tls.key-file", pki.serverKey().toString());
    props.put("argos.testing.ingest-fault-file", ingestFaultFile().toString());
    serverProperties = Map.copyOf(props);
    startServer();
    if (network == Toxiproxy.Network.BRIDGE) {
      dockerNetwork =
          Network.builder()
              .createNetworkCmdModifier(
                  cmd ->
                      cmd.withName("argos-chaos-" + runId)
                          .withLabels(Map.of("io.argos.test", "chaos")))
              .build();
    }
    log.accept("starting toxiproxy (" + network + ", " + Toxiproxy.image() + ")");
    toxiproxy = Toxiproxy.start(network, runId, env.collectorPort(), dockerNetwork);
    extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
    log.accept("starting shop-boot3 with the agent (JDK 21) -> " + toxiproxy.proxyAddress());
    main =
        startApp(
            APP_NAME,
            MAIN_INSTANCE,
            toxiproxy.proxyAddress(),
            Map.of(),
            UnaryOperator.identity(),
            work.resolve("app"),
            false);
    target = main;
    viewer = new TestUsers(env).create(Role.VIEWER);
    signIn();
    awaitInstanceUp(Duration.ofMinutes(2));
  }

  /** Starts the server (again) on the same databases, WAL and collector port. */
  public void startServer() throws IOException, InterruptedException {
    serverStarts++;
    log.accept(
        "starting the server (" + options.bootJar().getFileName() + ", #" + serverStarts + ")");
    long t0 = System.nanoTime();
    server =
        LoadServerJvm.start(
            env,
            work.resolve("server-" + serverStarts),
            new LoadServerJvm.Options(
                options.bootJar(),
                SERVER_HEAP,
                "256m",
                false,
                serverProperties,
                Duration.ofMinutes(5)));
    lastServerStart = Duration.ofNanos(System.nanoTime() - t0);
    log.accept("server up in " + lastServerStart.toSeconds() + " s");
    if (viewer != null) {
      signIn();
    }
  }

  /** How long the last server start took (spawn to the ports open). */
  public Duration lastServerStart() {
    return lastServerStart;
  }

  /** Stops the server ({@link LoadServerJvm#close}). */
  public void stopServer() {
    if (server != null) {
      log.accept("stopping the server" + (gracefulStops() ? "" : " (forced: Windows)"));
      server.close();
    }
  }

  /**
   * Whether {@link #stopServer} is graceful: POSIX (SIGTERM). On Windows the JVM is terminated at
   * once; the server writes no {@code stop} event and sends no {@code SessionClose}.
   */
  public boolean gracefulStops() {
    return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** The server JVM that runs now. */
  public LoadServerJvm server() {
    return server;
  }

  /** {@code docker pause} of ClickHouse. */
  public void pauseClickHouse() {
    DockerClientFactory.instance().client().pauseContainerCmd(clickHouseContainer).exec();
  }

  /** {@code docker unpause} of ClickHouse. */
  public void unpauseClickHouse() {
    DockerClientFactory.instance().client().unpauseContainerCmd(clickHouseContainer).exec();
  }

  // --- applications
  // -------------------------------------------------------------------------------

  /** The file the server watches as {@code argos.testing.ingest-fault-file} (absent: no fault). */
  public Path ingestFaultFile() {
    return work.resolve("ingest-fault");
  }

  /** The port toxiproxy's proxy listens on (127.0.0.1 on the host). */
  public int proxyPort() {
    String address = toxiproxy.proxyAddress();
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }

  /**
   * Starts shop-boot3 on JDK 21 with the agent for application {@code appName} (created with a new
   * agent token when missing), instance {@code instance}, sending to {@code serverAddress} with the
   * run's collector CA; {@code agentOverrides} win over the defaults and {@code customize} adds
   * launcher settings (JVM options). A container app ({@code container}) runs on the run's Docker
   * network, reaches the ext stub as {@code host.docker.internal}, and finds its token and CA in
   * its work directory (its only bind mount: {@link #deleteAgentSecrets} removes them when it is
   * stopped); a process app reads them from the private secrets directory.
   */
  public ChaosApp startApp(
      String appName,
      String instance,
      String serverAddress,
      Map<String, String> agentOverrides,
      UnaryOperator<AppLauncher> customize,
      Path appWork,
      boolean container)
      throws Exception {
    if (container && dockerNetwork == null) {
      throw new IllegalStateException(
          "a container app needs toxiproxy on the run's Docker network (bridge mode)");
    }
    int id;
    String token;
    try (Connection c = env.connect(PgAccount.APP)) {
      id = AgentTokens.ensureApp(c, appName, APP_ENV);
      token = AgentTokens.issue(c, id).token();
    }
    if (main == null) {
      mainToken = token;
    }
    Path files =
        Files.createDirectories(
            container ? appWork.resolve("agent-secrets") : secrets.resolve("agent-" + appName));
    Path tokenFile = files.resolve("token");
    writeOwnerOnly(tokenFile, token + "\n");
    Path ca = files.resolve("ca.pem");
    Files.copy(pki.caCertificate(), ca, StandardCopyOption.REPLACE_EXISTING);
    Map<String, String> agent = new LinkedHashMap<>();
    agent.put("app.name", appName);
    agent.put("app.env", APP_ENV);
    agent.put("app.instance", instance);
    agent.put("server.address", serverAddress);
    agent.put("server.tls", "true");
    agent.put("server.tls.ca-file", ca.toString());
    agent.put("server.token-file", tokenFile.toString());
    // The harness writes JSONL by default; the chaos run sends to the collector (C-88).
    agent.put("test.exporter", "grpc");
    agent.putAll(agentOverrides);
    String ext =
        container
            ? "http://host.docker.internal:" + extStub.baseUri().getPort() + "/"
            : extStub.baseUri().toString();
    AppLauncher launcher =
        AppLauncher.of(SampleApp.named("shop-boot3"), Jdk.of(Jdk.J21))
            .withAgent(options.agentJar(), agent)
            .withEnv("SAMPLE_EXT_URL", ext)
            .withJvmOptions("-Xms" + APP_HEAP, "-Xmx" + APP_HEAP)
            .workDir(appWork);
    if (container) {
      launcher.container(true, null, "1g").containerNetwork(dockerNetwork.getId());
    }
    RunningApp running = customize.apply(launcher).start(Duration.ofMinutes(3));
    return new ChaosApp(appName, id, running, null, main != null);
  }

  /** Removes the token and CA copy a container app read from its work directory. */
  public static void deleteAgentSecrets(ChaosApp app) {
    deleteTree(app.app().workDir().resolve("agent-secrets"));
  }

  /** The probes read {@code app} from now on (a scenario's own app). */
  public synchronized void use(ChaosApp app) {
    this.target = app;
  }

  /** The probes read the main app again. */
  public synchronized void useMain() {
    this.target = main;
  }

  /** The main app. */
  public ChaosApp mainApp() {
    return main;
  }

  /** The app the probes read now. */
  public synchronized ChaosApp target() {
    return target;
  }

  /** The id of the application the probes read ({@link #target()}). */
  public int appId() {
    return target().appId();
  }

  /** The databases. */
  public ServerTestEnvironment environment() {
    return env;
  }

  /** toxiproxy. */
  public Toxiproxy toxiproxy() {
    return toxiproxy;
  }

  /** The JVM of the application the probes read ({@link #target()}). */
  public RunningApp app() {
    return target().app();
  }

  /** The base URL of the target app (the load generator's target). */
  public String appUrl() {
    return target().baseUrl();
  }

  /** Where this run keeps its files. */
  public Path work() {
    return work;
  }

  /** The private directory of the run's secrets (deleted when the stack stops). */
  public Path secrets() {
    return secrets;
  }

  /** The run id. */
  public String runId() {
    return runId;
  }

  /** {@code docker exec} in container {@code containerId}: exit code, stdout and stderr. */
  public static Exec exec(String containerId, String... command) throws InterruptedException {
    DockerClient docker = DockerClientFactory.instance().client();
    String execId =
        docker
            .execCreateCmd(containerId)
            .withCmd(command)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec()
            .getId();
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    docker
        .execStartCmd(execId)
        .exec(
            new ResultCallback.Adapter<Frame>() {
              @Override
              public void onNext(Frame frame) {
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (frame.getStreamType() == StreamType.STDERR) {
                  err.append(text);
                } else {
                  out.append(text);
                }
              }
            })
        .awaitCompletion(2, TimeUnit.MINUTES);
    Long code = docker.inspectExecCmd(execId).exec().getExitCodeLong();
    return new Exec(code == null ? -1 : code.intValue(), out.toString(), err.toString());
  }

  /** The id of a container app's container. */
  public static String containerId(ChaosApp app) {
    return ((ContainerApp) app.app()).containerId();
  }

  // --- T-CH-008: PostgreSQL reset
  // -----------------------------------------------------------------

  /**
   * Drops and creates the {@code argos} database and runs {@code deploy/postgres/roles.sql} again
   * as the installer does (the roles survive; the database is empty), with the server stopped.
   * ClickHouse, the WAL and the agents stay; the server's next start migrates the empty database.
   */
  public void resetPostgres() throws Exception {
    var pg = env.postgresContainer();
    String db = ServerTestEnvironment.DATABASE;
    psql(pg, "postgres", "DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
    psql(pg, "postgres", "CREATE DATABASE " + db);
    Path roles =
        options.repositoryRoot().resolve("deploy").resolve("postgres").resolve("roles.sql");
    String file = "/tmp/argos-chaos-roles.sql";
    pg.copyFileToContainer(Transferable.of(Files.readAllBytes(roles)), file);
    var result =
        pg.execInContainer(
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "owner_role=" + PgAccount.OWNER.user(),
            "-v",
            "owner_password=" + env.password(PgAccount.OWNER),
            "-v",
            "app_password=" + env.password(PgAccount.APP),
            "-v",
            "audit_password=" + env.password(PgAccount.AUDIT),
            "-v",
            "backup_password=" + env.password(PgAccount.BACKUP),
            "-U",
            "postgres",
            "-d",
            db,
            "-f",
            file);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          "roles.sql after the reset failed (exit " + result.getExitCode() + ")");
    }
    // The viewer is gone with the database: restoreMainRegistration() creates a new one.
    viewer = null;
    log.accept("PostgreSQL database recreated (ClickHouse and the WAL kept)");
  }

  private static void psql(
      org.testcontainers.postgresql.PostgreSQLContainer pg, String database, String sql)
      throws Exception {
    var r = pg.execInContainer("psql", "-X", "-q", "-U", "postgres", "-d", database, "-c", sql);
    if (r.getExitCode() != 0) {
      throw new IllegalStateException(
          "psql '" + sql + "' failed (exit " + r.getExitCode() + "): " + r.getStderr().strip());
    }
  }

  /**
   * After {@link #resetPostgres()} and a server start that migrated the empty database: the main
   * application gets its {@code apps} row back under its old id (ClickHouse keeps that id), its
   * agent token (the same text, so the running agent's {@code server.token-file} stays valid), and
   * a new viewer signs in. Instances are not restored: the agent registers again.
   */
  public void restoreMainRegistration() throws Exception {
    try (Connection c = env.connect(PgAccount.OWNER)) {
      try (PreparedStatement insert =
          c.prepareStatement("INSERT INTO argos.apps (id, name, env) VALUES (?, ?, ?)")) {
        insert.setInt(1, main.appId());
        insert.setString(2, APP_NAME);
        insert.setString(3, APP_ENV);
        insert.executeUpdate();
      }
      try (Statement s = c.createStatement()) {
        s.execute(
            "SELECT setval(pg_get_serial_sequence('argos.apps', 'id'),"
                + " (SELECT max(id) FROM argos.apps))");
      }
      try (PreparedStatement insert =
          c.prepareStatement(
              "INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, status)"
                  + " VALUES (?, ?, ?, ?, 'ACTIVE')")) {
        insert.setObject(1, java.util.UUID.randomUUID());
        insert.setInt(2, main.appId());
        insert.setString(3, AgentTokens.sha256Hex(mainToken));
        insert.setString(4, AgentTokens.prefix(mainToken));
        insert.executeUpdate();
      }
    }
    viewer = new TestUsers(env).create(Role.VIEWER);
    signIn();
  }

  /** The {@code instances.id} of {@code name} in {@code appId}, or -1 (PostgreSQL). */
  public int instanceId(int appId, String name) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement q =
            c.prepareStatement("SELECT id FROM argos.instances WHERE app_id = ? AND name = ?")) {
      q.setInt(1, appId);
      q.setString(2, name);
      try (ResultSet rs = q.executeQuery()) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  // --- probes -----------------------------------------------------------------------------------

  private ClickHouseHttp reader() {
    return env.clickHouse(Account.READER).withRequestTimeout(Duration.ofSeconds(30));
  }

  /** {@code count()} of the application's xlog rows. */
  public long xlogCount() {
    return reader().scalarLong("SELECT count() FROM argos.xlog WHERE app_id = " + appId());
  }

  /** {@code uniqExact(tx_id)} of the application's xlog rows. */
  public long xlogUniqueTransactions() {
    return reader().scalarLong("SELECT uniqExact(tx_id) FROM argos.xlog WHERE app_id = " + appId());
  }

  /** xlog rows of the application whose transaction time is at or after {@code from}. */
  public long xlogSince(Instant from) {
    String ts = DateTimeFormatter.ISO_INSTANT.format(from.truncatedTo(ChronoUnit.MILLIS));
    return reader()
        .scalarLong(
            "SELECT count() FROM argos.xlog WHERE app_id = "
                + appId()
                + " AND ts >= parseDateTime64BestEffort('"
                + ts
                + "', 3, 'UTC')");
  }

  /** The distinct {@code instance_id} values of the application's xlog rows in [from, to). */
  public List<Long> xlogInstances(Instant from, Instant to) {
    String a = DateTimeFormatter.ISO_INSTANT.format(from.truncatedTo(ChronoUnit.MILLIS));
    String b = DateTimeFormatter.ISO_INSTANT.format(to.truncatedTo(ChronoUnit.MILLIS));
    String text =
        reader()
            .scalar(
                "SELECT arrayStringConcat(arraySort(groupUniqArray(instance_id)), ',') FROM"
                    + " argos.xlog WHERE app_id = "
                    + appId()
                    + " AND ts >= parseDateTime64BestEffort('"
                    + a
                    + "', 3, 'UTC') AND ts < parseDateTime64BestEffort('"
                    + b
                    + "', 3, 'UTC')")
            .strip();
    return text.isEmpty()
        ? List.of()
        : Arrays.stream(text.split(",")).map(Long::parseLong).toList();
  }

  /** The target agent's counters of its self-diagnostics snapshot (development build, C-95). */
  public Map<String, Long> agentCounters() {
    return target().agentCounters();
  }

  /** The target agent's log so far. */
  public AgentLog agentLog() {
    return target().agentLog();
  }

  /** Bytes of the agent's classes on the app heap after a full GC ({@code GC.class_histogram}). */
  public long agentHeapBytes() {
    return app().jcmd().classHistogram().bytesOf(AGENT_PACKAGE);
  }

  /** {@code argos_wal_bytes} of the server, empty when it cannot be read. */
  public OptionalDouble walBytes() {
    try {
      PrometheusText text = server.scraper().scrape();
      return text.has("argos_wal_bytes")
          ? OptionalDouble.of(text.sum("argos_wal_bytes"))
          : OptionalDouble.empty();
    } catch (RuntimeException e) {
      return OptionalDouble.empty();
    }
  }

  /** {@code GET /api/v1/apps/{id}/instances} ({@code data}), signing in again once on a 401. */
  public JsonNode instances() {
    return instances(appId());
  }

  /** {@code GET /api/v1/apps/{appId}/instances} ({@code data}) of any application of the run. */
  public JsonNode instances(int appId) {
    return apiGet("/api/v1/apps/" + appId + "/instances").path("data");
  }

  /**
   * {@code GET /api/v1/apps/{id}/xlog} of [from, to) ({@code data}: the points and the instance
   * dictionary the UI names them with).
   */
  public JsonNode xlog(Instant from, Instant to) {
    return apiGet(
            "/api/v1/apps/"
                + appId()
                + "/xlog?kind=ALL&from="
                + encode(from.truncatedTo(ChronoUnit.SECONDS))
                + "&to="
                + encode(to.truncatedTo(ChronoUnit.SECONDS)))
        .path("data");
  }

  /** {@code GET /api/v1/apps/{id}/collection-health} of [from, to] ({@code data}). */
  public JsonNode collectionHealth(Instant from, Instant to) {
    return apiGet(
            "/api/v1/apps/"
                + appId()
                + "/collection-health?from="
                + encode(from.truncatedTo(ChronoUnit.SECONDS))
                + "&to="
                + encode(to.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)))
        .path("data");
  }

  /** The state of the run's instance ({@code UP}, {@code WARN}, ...), or null when not listed. */
  public String instanceState() {
    JsonNode first = instances().path(0);
    return first.isMissingNode() ? null : first.path("state").asString();
  }

  /** Whether the instance counts as up: {@code UP} or {@code WARN} (up with a condition). */
  public static boolean isUp(String state) {
    return "UP".equals(state) || "WARN".equals(state);
  }

  private JsonNode apiGet(String path) {
    ApiResponse r = api.get(path);
    if (r.status() == 401) {
      signIn();
      r = api.get(path);
    }
    if (r.status() != 200) {
      throw new IllegalStateException("GET " + path + ": " + r.status() + " " + r.body());
    }
    return r.json();
  }

  /** Signs the viewer in (again) to the server that runs now. */
  public void signIn() {
    api = ApiTestClient.of(server.baseUrl());
    ApiResponse login = api.login(viewer.username(), viewer.password());
    if (login.status() != 200) {
      throw new IllegalStateException("viewer login failed: " + login.status());
    }
  }

  /** Waits until the target's first instance is {@code UP} or {@code WARN}. */
  public void awaitInstanceUp(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String state = null;
    while (System.nanoTime() < deadline) {
      try {
        state = instanceState();
      } catch (RuntimeException e) {
        state = "error: " + e.getMessage();
      }
      if (isUp(state)) {
        return;
      }
      Thread.sleep(1_000);
    }
    throw new IllegalStateException(
        "the agent's instance is not up after " + timeout + " (last state " + state + ")");
  }

  private static String encode(Instant t) {
    return URLEncoder.encode(t.toString(), StandardCharsets.UTF_8);
  }

  /**
   * The id of the running container that publishes host port {@code hostPort}. Like the server
   * fixtures' {@code DockerContainers.publishing}, but a container without port data (host network,
   * another run's containers) is skipped instead of failing with a NullPointerException.
   */
  static String publishing(int hostPort) {
    for (Container c : DockerClientFactory.instance().client().listContainersCmd().exec()) {
      ContainerPort[] ports = c.getPorts();
      if (ports == null) {
        continue;
      }
      for (ContainerPort p : ports) {
        if (p != null && p.getPublicPort() != null && p.getPublicPort() == hostPort) {
          return c.getId();
        }
      }
    }
    throw new IllegalStateException("no container publishes host port " + hostPort);
  }

  private static void require(Path file, String what) {
    if (file == null || !Files.isRegularFile(file)) {
      throw new IllegalStateException("missing " + what + ": " + file);
    }
  }

  private static void writeOwnerOnly(Path file, String value) throws IOException {
    Files.deleteIfExists(file);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(file);
    }
    Files.writeString(file, value, StandardCharsets.UTF_8);
  }

  /** The files a report keeps: the app's work directory and the server work directories. */
  public List<Path> logDirectories() {
    return List.of(work);
  }

  /** Stops everything that was started (app, ext-stub, toxiproxy, server, databases). */
  @Override
  public void close() {
    if (main != null) {
      try {
        main.app().close();
      } catch (RuntimeException e) {
        log.accept("app close failed: " + e);
      }
    }
    if (extStub != null) {
      extStub.close();
    }
    if (toxiproxy != null) {
      try {
        Files.write(work.resolve("toxiproxy.log"), toxiproxy.logLines(), StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException e) {
        log.accept("toxiproxy log not saved: " + e);
      }
      toxiproxy.close();
    }
    if (dockerNetwork != null) {
      try {
        dockerNetwork.close();
      } catch (RuntimeException e) {
        log.accept("docker network not removed: " + e);
      }
    }
    if (server != null) {
      server.close();
    }
    if (env != null) {
      try {
        unpauseClickHouse();
      } catch (RuntimeException e) {
        // not paused
      }
      env.close();
    }
    deleteTree(secrets);
  }

  /** Deletes {@code dir} and everything below it, quietly (temporary files). */
  static void deleteTree(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException | RuntimeException e) {
      // best effort
    }
  }
}
