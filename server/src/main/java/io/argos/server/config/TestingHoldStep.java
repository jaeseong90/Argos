/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties.Testing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Test hook {@code argos.testing.hold-before-phase} (development builds only, {@link
 * TestingHooks}): holds the start sequence before the first step of the configured phase while the
 * file {@code argos.testing.hold-file} exists, so that a test can observe the server in the middle
 * of its start (for example before the ClickHouse migration: no listener, not ready). The step
 * creates {@code <hold-file>.reached} when the hold begins and fails the start after {@code
 * argos.testing.hold-timeout}. The bean exists only when the key is set; a release build logs that
 * the hook is ignored and does not hold.
 */
@Component
@ConditionalOnProperty(prefix = "argos.testing", name = "hold-before-phase")
public class TestingHoldStep implements StartupPhase, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger(TestingHoldStep.class);

  private final ArgosProperties properties;
  private final TestingHooks hooks;

  public TestingHoldStep(ArgosProperties properties, TestingHooks hooks) {
    this.properties = properties;
    this.hooks = hooks;
  }

  @Override
  public PhaseOrder order() {
    PhaseOrder phase = properties.testing().holdBeforePhase();
    return phase == null ? PhaseOrder.CONFIG : phase;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public String name() {
    return "testing-hold";
  }

  @Override
  public void run() throws Exception {
    Optional<Testing> active = hooks.active();
    if (active.isEmpty() || active.get().holdFile() == null) {
      return;
    }
    Testing testing = active.get();
    Path holdFile = Paths.get(testing.holdFile());
    Path reached = Paths.get(testing.holdFile() + ".reached");
    try {
      Files.writeString(reached, order().name());
    } catch (IOException e) {
      throw new StartupPhaseException("argos.testing.hold-file: cannot create " + reached, e);
    }
    LOG.warn("Test hook: start held before phase {} while {} exists", order(), holdFile);
    long deadline = System.nanoTime() + testing.holdTimeout().toNanos();
    while (Files.exists(holdFile)) {
      if (System.nanoTime() - deadline > 0) {
        throw new StartupPhaseException(
            "argos.testing.hold-file still exists after " + format(testing.holdTimeout()));
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    LOG.warn("Test hook: start released before phase {}", order());
  }

  private static String format(Duration d) {
    return ConfigKeyCatalog.render(d);
  }
}
