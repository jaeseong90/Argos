/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A minimal Spring Boot web application with the real {@link StartupSequence} and probe steps, for
 * the start-order tests: embedded Tomcat, Actuator on a separate management port, no database
 * (DataSource auto-configuration off). The server's own beans are not scanned.
 *
 * <p>System properties: {@code argos.it.http-port} and {@code argos.it.management-port} are probed
 * by every step; {@code argos.it.fail-phase} makes the step of that phase fail with a {@link
 * StartupPhaseException} whose cause carries {@code argos.it.sentinel} (which must never be
 * logged).
 *
 * <p>Deliberately not a {@code @SpringBootConfiguration} (nor any other {@code @Component}): this
 * class sits in {@code io.argos.server.bootstrap} of the integration test class path, where the
 * component scan of {@code ServerApplication} would otherwise pick it up, add its probe steps and
 * its auto-configuration exclusions to every real server started by the other integration tests,
 * and fill {@link #EVENTS} from their starts. As the primary source of a {@code SpringApplication}
 * it is still a (lite) configuration class: its {@code @Bean} methods, {@code @Import} and {@code
 * EnableAutoConfiguration} apply.
 */
@EnableAutoConfiguration(
    excludeName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@Import(StartupSequence.class)
public class SequenceTestApplication {

  /** What each probe step saw: phase name and whether the two ports refused connections. */
  public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

  /** Marker printed when a step after the failing one runs (must never appear). */
  public static final String RAN_AFTER_FAILURE = "probe step ran after the failure";

  @Bean
  StartupPhase probeConfig() {
    return new Probe(PhaseOrder.CONFIG);
  }

  @Bean
  StartupPhase probeKeys() {
    return new Probe(PhaseOrder.KEYS);
  }

  @Bean
  StartupPhase probePostgres() {
    return new Probe(PhaseOrder.POSTGRES_MIGRATION);
  }

  @Bean
  StartupPhase probeClickHouse() {
    return new Probe(PhaseOrder.CLICKHOUSE_MIGRATION);
  }

  @Bean
  StartupPhase probeWal() {
    return new Probe(PhaseOrder.WAL_RECOVERY);
  }

  @Bean
  StartupPhase probeListeners() {
    return new Probe(PhaseOrder.LISTENERS);
  }

  /** Entry point of the failed-start test (child JVM). */
  public static void main(String[] args) {
    SpringApplication.run(SequenceTestApplication.class, args);
  }

  /** Whether something accepts TCP connections on 127.0.0.1:{@code port}. */
  static boolean accepts(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static final class Probe implements StartupPhase {
    private final PhaseOrder order;

    Probe(PhaseOrder order) {
      this.order = order;
    }

    @Override
    public PhaseOrder order() {
      return order;
    }

    @Override
    public String name() {
      return "probe-" + order.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public void run() throws Exception {
      String failPhase = System.getProperty("argos.it.fail-phase");
      if (failPhase != null && order.compareTo(PhaseOrder.valueOf(failPhase)) > 0) {
        System.out.println(RAN_AFTER_FAILURE + ": " + order);
      }
      int http = Integer.getInteger("argos.it.http-port", -1);
      int management = Integer.getInteger("argos.it.management-port", -1);
      EVENTS.add(
          order
              + " http="
              + (http > 0 && accepts(http) ? "open" : "refused")
              + " management="
              + (management > 0 && accepts(management) ? "open" : "refused"));
      if (order.name().equals(failPhase)) {
        throw new StartupPhaseException(
            "probe failure in " + order,
            new IllegalStateException("cause " + System.getProperty("argos.it.sentinel")));
      }
    }
  }
}
