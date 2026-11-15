/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static io.argos.server.config.ConfigTestSupport.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhaseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.Ordered;

/** {@code argos.testing.*}: development builds only (18 §15, fail-closed like ADR-0009 C-89). */
@Tag("req:DSN-18-15")
class TestingHooksTest {

  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  @TempDir Path dir;

  private ArgosProperties properties(Map<String, Object> values) {
    return bind(environment(false, Map.of(), file(values)));
  }

  @Test
  @DisplayName("T-BLD-010 server: release and unknown versions ignore argos.testing.*")
  void releaseGate() {
    ArgosProperties p = properties(Map.of("argos.testing.hold-file", dir.resolve("h").toString()));
    assertThat(new TestingHooks(p, new ServerBuild("0.1.0-SNAPSHOT", "abc", "1")).active())
        .isPresent();
    assertThat(new TestingHooks(p, new ServerBuild("1.0.0", "abc", "1")).active()).isEmpty();
    assertThat(new TestingHooks(p, new ServerBuild(null, null, null)).active()).isEmpty();
    assertThat(new ServerBuild(null, null, null).isRelease()).isTrue();
    assertThat(new ServerBuild(" ", "c", "p").version()).isEqualTo(ServerBuild.UNKNOWN);
    ArgosProperties none = properties(Map.of());
    assertThat(new TestingHooks(none, new ServerBuild("0.1.0-SNAPSHOT", "abc", "1")).active())
        .isEmpty();
  }

  @Test
  @DisplayName("the hold step runs first in its phase and waits while the hold file exists")
  void holds() throws Exception {
    Path hold = Files.createFile(dir.resolve("hold"));
    ArgosProperties p =
        properties(
            Map.of(
                "argos.testing.hold-before-phase",
                "clickhouse-migration",
                "argos.testing.hold-file",
                hold.toString()));
    TestingHoldStep step =
        new TestingHoldStep(p, new TestingHooks(p, new ServerBuild("0.1.0-SNAPSHOT", "c", "1")));
    assertThat(step.order()).isEqualTo(PhaseOrder.CLICKHOUSE_MIGRATION);
    assertThat(step.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);

    CompletableFuture<Void> run =
        CompletableFuture.runAsync(
            () -> {
              try {
                step.run();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Path reached = dir.resolve("hold.reached");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Files.exists(reached) && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(reached).exists();
    TimeUnit.MILLISECONDS.sleep(150);
    assertThat(run).isNotDone();
    Files.delete(hold);
    run.get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("T-BLD-010 server: a release build does not hold; a hold past hold-timeout fails")
  void timeoutAndRelease() throws Exception {
    Path hold = Files.createFile(dir.resolve("hold"));
    ArgosProperties p =
        properties(
            Map.of(
                "argos.testing.hold-before-phase", "keys",
                "argos.testing.hold-file", hold.toString(),
                "argos.testing.hold-timeout", "100ms"));
    TestingHoldStep dev =
        new TestingHoldStep(p, new TestingHooks(p, new ServerBuild("0.1.0-SNAPSHOT", "c", "1")));
    assertThatThrownBy(dev::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessage("argos.testing.hold-file still exists after 100ms");
    TestingHoldStep release =
        new TestingHoldStep(p, new TestingHooks(p, new ServerBuild("1.0.0", "c", "1")));
    release.run(); // returns at once although the hold file exists
    assertThat(hold).exists();
  }
}
