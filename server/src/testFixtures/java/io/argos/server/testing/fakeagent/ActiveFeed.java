/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WO-35 active-transaction feed (development and test only, never shipped): one {@link
 * FakeAgent} session per instance against a real collector (plaintext, {@code
 * argos.collector.allow-plaintext}), sending the deterministic snapshots of {@link
 * ActiveFeedScenario} every interval through {@code SendActiveSnapshot}. The real agent does not
 * produce active snapshots yet (WO-16), so the E2E stack (ui/e2e/stack/seed/activeFeed.ts) and
 * {@code ActiveFeedIT} run the active-transaction screen on this feed; collector, session registry,
 * REST and WebSocket are the real ones.
 *
 * <pre>
 * java -jar server/build/active-feed/active-feed.jar --target 127.0.0.1:9100 --token-file F
 *     [--instances e2e-active-1,e2e-active-2] [--ready-file R] [--duration 30m] [--interval 5s]
 *     [--app e2e-active] [--env e2e]
 * </pre>
 *
 * Per instance: connect (Hello with the profile's user id and address modes, {@code plaintext}),
 * pre-send every dictionary name of the profile (services, threads, SQL texts, hosts, DB targets),
 * a heartbeat at least every 5 s, a snapshot every interval. A session the server ended (a server
 * restart) is opened again with the dictionary sent anew. After the first snapshot of every
 * instance was acknowledged, the ready file gets the scenario as JSON ({@link
 * ActiveFeedScenario#toJson}) with the instance ids. The feed stops after {@code --duration} or on
 * a shutdown signal and cancels its sessions (no Bye: the instances go STALE and DOWN by the
 * server's rules). The token is read from the file and never printed.
 */
public final class ActiveFeed implements AutoCloseable {

  /** Exit code of invalid arguments. */
  static final int EXIT_USAGE = 2;

  /** Longest heartbeat period (11 §9: at most 5 s here, below the 60 s STALE rule). */
  static final Duration MAX_HEARTBEAT = Duration.ofSeconds(5);

  private static final String USAGE =
      "usage: active-feed --target HOST:PORT --token-file FILE [--instances A,B] [--ready-file"
          + " FILE] [--duration 30m] [--interval 5s] [--app e2e-active] [--env e2e]";

  /**
   * Options.
   *
   * @param host collector host
   * @param port collector port
   * @param tokenFile file holding the agent token
   * @param instances instance names
   * @param readyFile written after the first snapshot round, or {@code null}
   * @param duration the feed stops after this
   * @param interval snapshot interval
   * @param app application name
   * @param env environment
   */
  public record Options(
      String host,
      int port,
      Path tokenFile,
      List<String> instances,
      Path readyFile,
      Duration duration,
      Duration interval,
      String app,
      String env) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /** Validates and copies. */
    public Options {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(tokenFile, "tokenFile");
      instances = List.copyOf(instances);
      if (port < 1 || port > 65_535) {
        throw new IllegalArgumentException("port out of range: " + port);
      }
      if (instances.isEmpty() || instances.size() > 64) {
        throw new IllegalArgumentException("1 to 64 instances");
      }
      for (String name : instances) {
        if (!NAME.matcher(name).matches()) {
          throw new IllegalArgumentException("invalid instance name: " + name);
        }
      }
      if (instances.stream().distinct().count() != instances.size()) {
        throw new IllegalArgumentException("duplicate instance names");
      }
      if (interval.compareTo(Duration.ofSeconds(1)) < 0
          || interval.compareTo(Duration.ofHours(1)) > 0) {
        throw new IllegalArgumentException("--interval must be 1 s to 1 h");
      }
      if (duration.compareTo(Duration.ofSeconds(1)) < 0
          || duration.compareTo(Duration.ofHours(24)) > 0) {
        throw new IllegalArgumentException("--duration must be 1 s to 24 h");
      }
      if (!NAME.matcher(app).matches() || !NAME.matcher(env).matches()) {
        throw new IllegalArgumentException("invalid app or env name");
      }
    }

    /** Parses the command line (see the class documentation). */
    public static Options parse(String... args) {
      String target = null;
      Path token = null;
      List<String> instances = ActiveFeedScenario.DEFAULT_INSTANCES;
      Path ready = null;
      Duration duration = Duration.ofMinutes(30);
      Duration interval = Duration.ofSeconds(5);
      String app = ActiveFeedScenario.APP;
      String env = ActiveFeedScenario.ENV;
      for (int i = 0; i < args.length; i++) {
        String key = args[i];
        String value;
        int eq = key.indexOf('=');
        if (key.startsWith("--") && eq > 0) {
          value = key.substring(eq + 1);
          key = key.substring(0, eq);
        } else {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("missing value of " + key);
          }
          value = args[++i];
        }
        switch (key) {
          case "--target" -> target = value;
          case "--token-file" -> token = Path.of(value);
          case "--instances" ->
              instances =
                  Arrays.stream(value.split(","))
                      .map(String::strip)
                      .filter(s -> !s.isEmpty())
                      .toList();
          case "--ready-file" -> ready = Path.of(value);
          case "--duration" -> duration = duration(value);
          case "--interval" -> interval = duration(value);
          case "--app" -> app = value;
          case "--env" -> env = value;
          default -> throw new IllegalArgumentException("unknown option " + key);
        }
      }
      if (target == null || token == null) {
        throw new IllegalArgumentException("--target and --token-file are required");
      }
      int colon = target.lastIndexOf(':');
      if (colon <= 0) {
        throw new IllegalArgumentException("--target must be HOST:PORT");
      }
      int port;
      try {
        port = Integer.parseInt(target.substring(colon + 1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("--target must be HOST:PORT", e);
      }
      String host = target.substring(0, colon);
      if (host.startsWith("[") && host.endsWith("]")) {
        host = host.substring(1, host.length() - 1);
      }
      return new Options(host, port, token, instances, ready, duration, interval, app, env);
    }

    private static final Pattern SHORT = Pattern.compile("(\\d{1,9})(ms|s|m|h)");

    /** {@code 500ms}, {@code 5s}, {@code 30m}, {@code 1h} or ISO-8601 ({@code PT5S}). */
    static Duration duration(String text) {
      Matcher m = SHORT.matcher(text.strip().toLowerCase(Locale.ROOT));
      if (m.matches()) {
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
          case "ms" -> Duration.ofMillis(n);
          case "s" -> Duration.ofSeconds(n);
          case "m" -> Duration.ofMinutes(n);
          default -> Duration.ofHours(n);
        };
      }
      try {
        return Duration.parse(text.strip());
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("invalid duration: " + text, e);
      }
    }
  }

  /** One fake instance. */
  private static final class Instance {
    final ActiveFeedScenario.Profile profile;
    final Records records;
    final FakeAgent agent;
    final int threadIdBase;
    volatile boolean needsConnect = true;
    volatile String lastProblem;

    Instance(ActiveFeedScenario.Profile profile, Records records, FakeAgent agent) {
      this.profile = profile;
      this.records = records;
      this.agent = agent;
      this.threadIdBase = 1_000 * (profile.index() + 1);
    }
  }

  /**
   * The last snapshot sent.
   *
   * @param number snapshot number (0-based, the same for every instance of a round)
   * @param snapshotTsMs its time
   */
  public record Round(long number, long snapshotTsMs) {}

  private final Options options;
  private final Consumer<String> log;
  private final ActiveFeedScenario scenario;
  private final List<Instance> instances = new ArrayList<>();
  private final ScheduledExecutorService scheduler;
  private final List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong nextNumber = new AtomicLong();
  private volatile Round lastRound;

  private ActiveFeed(Options options, Consumer<String> log, String token, long feedStartMs) {
    this.options = options;
    this.log = log;
    this.scenario = new ActiveFeedScenario(feedStartMs, options.instances());
    for (ActiveFeedScenario.Profile p : scenario.profiles()) {
      AgentClock clock = AgentClock.system();
      Records records = new Records(clock, p.index() + 1, p.index());
      ActiveFeedScenario.registerNames(p, records);
      ManagedChannel channel = AgentChannels.plaintext(options.host(), options.port());
      FakeAgent agent =
          FakeAgent.builder(channel)
              .ownsChannel(true)
              .token(token)
              .clock(clock)
              .records(records)
              .appName(options.app())
              .env(options.env())
              .instanceName(p.name())
              .plaintextFlag(true)
              .maxRecorded(1_000)
              .hello(h -> h.setUserIdMode(p.userIdMode()).setIpMode(p.ipMode()))
              .build();
      instances.add(new Instance(p, records, agent));
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "active-feed");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Starts the feed: connects every instance, sends the first snapshot round (it must be
   * acknowledged), writes the ready file and schedules heartbeats, snapshots and the stop.
   *
   * @param log progress lines (never the token)
   * @throws IllegalStateException when an instance is refused or its first snapshot is not
   *     acknowledged
   */
  public static ActiveFeed start(Options options, Consumer<String> log) {
    String token;
    try {
      token = Files.readString(options.tokenFile(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the token file", e);
    }
    if (token.isEmpty()) {
      throw new IllegalArgumentException("the token file is empty");
    }
    ActiveFeed feed = new ActiveFeed(options, log, token, System.currentTimeMillis());
    try {
      feed.firstRound();
    } catch (RuntimeException e) {
      feed.close();
      throw e;
    }
    feed.schedule();
    return feed;
  }

  /** The scenario of this feed. */
  public ActiveFeedScenario scenario() {
    return scenario;
  }

  /** The instance ids of the accepted sessions, by instance index (0 before a session). */
  public List<Integer> instanceIds() {
    return instances.stream().map(i -> i.agent.instanceId()).toList();
  }

  /** The last snapshot round sent, {@code null} before the first. */
  public Round lastRound() {
    return lastRound;
  }

  /** Waits until the feed stopped (duration over or closed). */
  public boolean awaitDone(Duration timeout) throws InterruptedException {
    return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void firstRound() {
    for (Instance i : instances) {
      String problem = connect(i);
      if (problem != null) {
        throw new IllegalStateException(problem);
      }
    }
    String problem = round();
    if (problem != null) {
      throw new IllegalStateException(problem);
    }
    log.accept(
        "[active-feed] first snapshot acknowledged for "
            + instances.size()
            + " instances, ids "
            + instanceIds());
    if (options.readyFile() != null) {
      writeReady(
          options.readyFile(), scenario.toJson(options.interval().toMillis(), instanceIds()));
    }
  }

  private void schedule() {
    long interval = options.interval().toMillis();
    long heartbeat = Math.min(interval, MAX_HEARTBEAT.toMillis());
    tasks.add(
        scheduler.scheduleAtFixedRate(
            this::heartbeats, heartbeat, heartbeat, TimeUnit.MILLISECONDS));
    tasks.add(
        scheduler.scheduleAtFixedRate(
            this::roundQuietly, interval, interval, TimeUnit.MILLISECONDS));
    tasks.add(
        scheduler.schedule(
            () -> {
              log.accept("[active-feed] duration " + options.duration() + " over, stopping");
              close();
            },
            options.duration().toMillis(),
            TimeUnit.MILLISECONDS));
  }

  /** Opens the session of {@code i} and pre-sends its dictionary; the problem or {@code null}. */
  private String connect(Instance i) {
    HelloResult hello = i.agent.connect();
    if (!hello.accepted()) {
      return "instance " + i.profile.name() + " refused: " + hello;
    }
    i.agent.sendDictionaryEntries(i.records.dictionaryEntries());
    i.agent.heartbeat();
    i.needsConnect = false;
    log.accept(
        "[active-feed] instance " + i.profile.name() + " connected, id " + i.agent.instanceId());
    return null;
  }

  /** One snapshot of every instance; the first problem or {@code null}. */
  private String round() {
    long number = nextNumber.getAndIncrement();
    long ts = System.currentTimeMillis();
    String first = null;
    for (Instance i : instances) {
      if (i.needsConnect || !i.agent.sessionOpen()) {
        String problem = connect(i);
        if (problem != null) {
          report(i, problem);
          first = first == null ? problem : first;
          continue;
        }
      }
      List<ActiveTransaction> items = new ArrayList<>();
      for (ActiveFeedScenario.Item item : scenario.items(i.profile.index(), number, ts)) {
        items.add(ActiveFeedScenario.toProto(item, i.threadIdBase, i.records));
      }
      ActiveTransactionSnapshot snapshot =
          ActiveTransactionSnapshot.newBuilder()
              .setHeader(i.agent.batches().header(BatchKind.ACTIVE))
              .setSnapshotTsMs(ts)
              .setTotalActive(items.size())
              .addAllItems(items)
              .setTruncated(false)
              .build();
      CallResult result = i.agent.sendActive(snapshot);
      if (!result.ok()) {
        String problem = "snapshot of " + i.profile.name() + " not acknowledged: " + result.code();
        report(i, problem);
        i.needsConnect = true;
        first = first == null ? problem : first;
      } else if (i.lastProblem != null) {
        log.accept("[active-feed] instance " + i.profile.name() + " recovered");
        i.lastProblem = null;
      }
    }
    lastRound = new Round(number, ts);
    return first;
  }

  private void report(Instance i, String problem) {
    if (!problem.equals(i.lastProblem)) {
      log.accept("[active-feed] " + problem);
      i.lastProblem = problem;
    }
  }

  private void roundQuietly() {
    try {
      round();
    } catch (RuntimeException e) {
      log.accept("[active-feed] snapshot round failed: " + e);
    }
  }

  private void heartbeats() {
    for (Instance i : instances) {
      try {
        if (!i.needsConnect && i.agent.sessionOpen()) {
          i.agent.heartbeat();
        } else {
          i.needsConnect = true;
        }
      } catch (RuntimeException e) {
        i.needsConnect = true;
      }
    }
  }

  /** Writes {@code json} to {@code file} atomically (a reader never sees half a file). */
  static void writeReady(Path file, String json) {
    try {
      Path dir = file.toAbsolutePath().getParent();
      if (dir != null) {
        Files.createDirectories(dir);
      }
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the ready file", e);
    }
  }

  /** Stops the schedule and cancels every session (no Bye). */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    tasks.forEach(t -> t.cancel(false));
    scheduler.shutdownNow();
    for (Instance i : instances) {
      try {
        i.agent.close();
      } catch (RuntimeException e) {
        log.accept("[active-feed] closing " + i.profile.name() + " failed: " + e);
      }
    }
    done.countDown();
  }

  /** The command line (see the class documentation); exit 0 after the duration, 2 on usage. */
  public static void main(String[] args) throws InterruptedException {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("active-feed: " + e.getMessage());
      System.err.println(USAGE);
      System.exit(EXIT_USAGE);
      return;
    }
    ActiveFeed feed;
    try {
      feed = start(options, System.out::println);
    } catch (RuntimeException e) {
      System.err.println("active-feed: " + e.getMessage());
      System.exit(1);
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "active-feed-stop"));
    feed.awaitDone(options.duration().plusMinutes(1));
    feed.close();
    System.out.println("[active-feed] stopped");
  }
}
