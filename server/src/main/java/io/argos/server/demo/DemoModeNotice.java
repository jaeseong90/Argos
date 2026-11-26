/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.demo;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.config.ArgosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The warning of demo mode (17 §3.2; WO-41): one WARN line at every start of a server with {@code
 * argos.demo.enabled=true}, naming demo mode and the plaintext gRPC settings in force ({@link
 * DemoModeDefaults} supplies them unless an operator set them). Nothing is logged without demo
 * mode. A {@code CONFIG} step, so the line comes before the migrations and the listeners.
 */
@Component
public class DemoModeNotice implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(DemoModeNotice.class);

  private final ArgosProperties properties;

  public DemoModeNotice(ArgosProperties properties) {
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "demo-mode";
  }

  @Override
  public void run() {
    if (properties.demo().enabled()) {
      LOG.warn(
          "{}",
          message(properties.collector().tls().enabled(), properties.collector().allowPlaintext()));
    }
  }

  /** The warning for the collector settings in force. */
  static String message(boolean tlsEnabled, boolean allowPlaintext) {
    String settings =
        "argos.collector.tls.enabled="
            + tlsEnabled
            + ", argos.collector.allow-plaintext="
            + allowPlaintext;
    String plaintext =
        !tlsEnabled && allowPlaintext
            ? "plaintext gRPC is enabled (" + settings + ")"
            : "plaintext gRPC defaults are overridden (" + settings + ")";
    return "DEMO MODE (argos.demo.enabled=true): "
        + plaintext
        + ", the UI shows the demo banner, and the installation serves sample traffic. Never use"
        + " demo mode for production data";
  }
}
