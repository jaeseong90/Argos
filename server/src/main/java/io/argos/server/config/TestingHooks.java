/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.config.ArgosProperties.Testing;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gate of the {@code argos.testing.*} hooks (18 §15): they apply only to development builds. A
 * release build ({@link ServerBuild#isRelease()}, fail-closed for an unknown version) ignores them
 * and says so once with a WARN. Every hook asks {@link #active()} before doing anything.
 */
@Component
public class TestingHooks {

  private static final Logger LOG = LoggerFactory.getLogger(TestingHooks.class);

  private final Testing testing;
  private final ServerBuild build;
  private final AtomicBoolean warned = new AtomicBoolean();

  public TestingHooks(ArgosProperties properties, ServerBuild build) {
    this.testing = properties.testing();
    this.build = build;
  }

  /** The hooks when this is a development build and any is set; otherwise empty. */
  public Optional<Testing> active() {
    if (!testing.anySet()) {
      return Optional.empty();
    }
    if (build.isRelease()) {
      if (warned.compareAndSet(false, true)) {
        LOG.warn(
            "argos.testing.* settings are ignored: version {} is a release build (18 §15)",
            build.version());
      }
      return Optional.empty();
    }
    return Optional.of(testing);
  }
}
