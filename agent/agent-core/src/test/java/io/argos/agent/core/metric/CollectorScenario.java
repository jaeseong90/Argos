/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.PoolKind;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.transport.TransportCounters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.tomcat.util.net.NioEndpoint;

/**
 * An agent runtime with every source of the 10 §11 table: a HikariCP pool over in-memory H2 (max 7)
 * and a Tomcat endpoint with an executor (max 17 threads, 123 connections) registered like the pool
 * and Tomcat modules do, a pool wait source, the self-diagnostics with the transport counters, a
 * busy agent thread and the cgroup v2 fixture as the file system root.
 */
final class CollectorScenario implements AutoCloseable {
  final AgentCounters counters = new AgentCounters();
  final TraceRuntime runtime;
  final HikariDataSource dataSource;
  final NioEndpoint endpoint;
  final MetricCollector collector;
  private final ArgosThread agentThread;
  private final CountDownLatch stop = new CountDownLatch(1);

  /** Keeps the busy loop of the agent thread from being optimised away. */
  volatile long busyResult;

  CollectorScenario() throws Exception {
    counters.setModuleNames(new String[] {"servlet", "jdbc"});
    runtime = TraceRuntime.create(AgentConfig.of(Collections.<String, String>emptyMap()), counters);

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:wo16-metrics;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    config.setPoolName("orders-pool");
    config.setMaximumPoolSize(7);
    dataSource = new HikariDataSource(config);
    runtime.tracerImpl().pools().register(dataSource.getHikariPoolMXBean(), PoolKind.HIKARI);
    if (!(dataSource.getHikariPoolMXBean() instanceof HikariPool)) {
      throw new IllegalStateException("not a HikariPool");
    }

    endpoint = new NioEndpoint();
    endpoint.setName("http-nio-18081");
    endpoint.setPort(18081);
    endpoint.setMinSpareThreads(2);
    endpoint.setMaxThreads(17);
    endpoint.setMaxConnections(123);
    endpoint.createExecutor();
    runtime.tracerImpl().connectors().register(endpoint);

    PoolWaitSource waits =
        new PoolWaitSource() {
          @Override
          public Map<String, long[]> poolWaits() {
            Map<String, long[]> out = new LinkedHashMap<String, long[]>();
            out.put("orders-pool", new long[] {4, 1_500_000_000L});
            return out;
          }
        };
    collector =
        new MetricCollector(
            500,
            runtime,
            waits,
            CgroupReaderTest.fixture("v2"),
            true,
            OsBean.fixed(2_000_000_000L, 0.2, 0.3, 42, 4096),
            new GcNameMapper(() -> {}));
    SelfDiagnosticsCollector diagnostics =
        new SelfDiagnosticsCollector(runtime::selfValues, counters, new String[0]);
    final TransportCounters transport = new TransportCounters();
    diagnostics.extraCounters(transport::snapshot);
    collector.selfDiagnostics(diagnostics);

    agentThread =
        new ArgosThread(
            () -> {
              long x = 0;
              for (int i = 0; i < 2_000_000; i++) {
                x += i * 31L;
              }
              busyResult = x;
              try {
                stop.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "argos-test-busy");
    agentThread.start();
  }

  @Override
  public void close() {
    stop.countDown();
    try {
      agentThread.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    endpoint.shutdownExecutor();
    dataSource.close();
  }
}
