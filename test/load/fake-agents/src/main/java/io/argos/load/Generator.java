/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.grpc.ManagedChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fake agents of the load (18 §8): {@code --instances} sessions over the apps (round robin),
 * TLS channels shared by up to four sessions each (HTTP/2 multiplexing, as many agents behind one
 * load generator host), each instance with its own process epoch, {@code tx_id} node and agent
 * clock: a uniform skew within {@code +-clock-skew}, and {@code skewed-instances} instances at
 * {@code skew-outlier} (+60 s, D-15). Every instance ticks once a second on a shared scheduler.
 */
final class Generator implements AutoCloseable {

  /**
   * Sessions per shared channel: the collector allows 8 concurrent calls per connection
   * (GrpcCollectorServer.MAX_CALLS_PER_CONNECTION), and an instance holds its session stream plus
   * one data call at a time.
   */
  private static final int SESSIONS_PER_CHANNEL = 4;

  private final LoadCounters counters;
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final List<SimInstance> instances = new ArrayList<>();
  private final List<Long> skewsMs = new ArrayList<>();
  private final ScheduledExecutorService scheduler;
  private final List<ScheduledFuture<?>> ticks = new ArrayList<>();

  Generator(
      LoadOptions options,
      List<LoadApp> apps,
      int collectorPort,
      Path collectorCa,
      LoadCounters counters) {
    this.counters = counters;
    int threads =
        Math.max(4, Math.min(options.instances(), 2 * Runtime.getRuntime().availableProcessors()));
    this.scheduler = Executors.newScheduledThreadPool(threads, daemonThreads("load-agent-"));
    Workload workload =
        new Workload(
            options.services(),
            Math.max(50, options.sqlRowsPerWindow()),
            Math.max(10, options.extRowsPerWindow()),
            options.metricsPerInstance());
    SplittableRandom random = new SplittableRandom(options.seed());
    long epochBase = System.currentTimeMillis() * 1_000;
    for (int i = 0; i < options.instances(); i++) {
      if (i % SESSIONS_PER_CHANNEL == 0) {
        channels.add(AgentChannels.tls("localhost", collectorPort, collectorCa));
      }
      ManagedChannel channel = channels.get(channels.size() - 1);
      LoadApp app = apps.get(i % apps.size());
      long skewMs =
          i < options.skewedInstances()
              ? options.skewOutlier().toMillis()
              : Math.round((random.nextDouble() * 2 - 1) * options.clockSkew().toMillis());
      skewsMs.add(skewMs);
      AgentClock clock = AgentClock.skewed(Duration.ofMillis(skewMs));
      FakeAgent agent =
          FakeAgent.builder(channel)
              .ownsChannel(false)
              .token(app.token().token())
              .appName(app.name())
              .env(app.env())
              .instanceName(app.name() + "-" + app.env() + "-i" + i)
              .clock(clock)
              .records(new Records(clock, 1 + i, random.nextLong()))
              .agentEpoch(epochBase + i)
              .callDeadline(Duration.ofSeconds(30))
              .helloTimeout(Duration.ofSeconds(30))
              .maxRecorded(32)
              .build();
      instances.add(new SimInstance(i, app, agent, workload, options, counters, random.nextLong()));
    }
  }

  /** The clock skew of every instance in ms (for the report). */
  List<Long> skewsMs() {
    return List.copyOf(skewsMs);
  }

  /** The instances. */
  List<SimInstance> instances() {
    return List.copyOf(instances);
  }

  /** Channels shared by the sessions. */
  int channelCount() {
    return channels.size();
  }

  /** Opens every session; returns the number the server accepted. */
  int connectAll() {
    int accepted = 0;
    for (SimInstance s : instances) {
      if (s.connect()) {
        accepted++;
      }
    }
    return accepted;
  }

  /** Starts the ticks, spread over the first second. */
  void start() {
    long n = instances.size();
    for (SimInstance s : instances) {
      long offsetMs = s.index() * 1_000L / n;
      ticks.add(scheduler.scheduleAtFixedRate(s::tick, offsetMs, 1_000, TimeUnit.MILLISECONDS));
    }
  }

  /** Stops the ticks and waits for running ones; then sends every held-back transaction. */
  void stop() throws InterruptedException {
    ticks.forEach(t -> t.cancel(false));
    scheduler.shutdown();
    if (!scheduler.awaitTermination(2, TimeUnit.MINUTES)) {
      counters.failure("generator ticks did not end within 2 minutes");
    }
    for (SimInstance s : instances) {
      s.flushLate();
    }
  }

  /** Transactions still held back (0 after {@link #stop}). */
  int lateHeld() {
    return instances.stream().mapToInt(SimInstance::lateHeld).sum();
  }

  /** Ends every session and shuts the channels down. */
  @Override
  public void close() {
    scheduler.shutdownNow();
    for (SimInstance s : instances) {
      try {
        s.agent().close();
      } catch (RuntimeException e) {
        // closing anyway
      }
    }
    for (ManagedChannel c : channels) {
      c.shutdownNow();
      try {
        c.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
