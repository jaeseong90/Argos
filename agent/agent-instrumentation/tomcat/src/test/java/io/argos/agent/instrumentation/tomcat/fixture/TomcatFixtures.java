/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.tomcat.fixture;

import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioEndpoint;

/**
 * The container side of the Tomcat advice tests: real Tomcat 9 {@code NioEndpoint} objects whose
 * {@code bind()} and {@code startInternal()} open no socket (unit tests use no network, 18 §2.2),
 * started through the real {@code AbstractEndpoint.start()}. Loaded by the test's {@code
 * AdvisingLoader} together with {@code org.apache.tomcat.util.net} (child first), so {@code
 * start()} carries the module's advice; the test calls it reflectively.
 */
public final class TomcatFixtures {
  private TomcatFixtures() {}

  /** An endpoint without a socket; {@code failBind} makes {@code start()} throw. */
  public static Object endpoint(String name, int port, int maxThreads, boolean failBind) {
    QuietEndpoint endpoint = new QuietEndpoint(failBind);
    endpoint.setName(name);
    endpoint.setPort(port);
    endpoint.setMaxThreads(maxThreads);
    endpoint.setMaxConnections(321);
    return endpoint;
  }

  /** {@code AbstractEndpoint.start()} (the advised method); creates the executor first. */
  public static void start(Object endpoint) throws Exception {
    AbstractEndpoint<?, ?> e = (AbstractEndpoint<?, ?>) endpoint;
    e.createExecutor();
    e.start();
  }

  /** Stops the endpoint (and its executor). */
  public static void stop(Object endpoint) throws Exception {
    AbstractEndpoint<?, ?> e = (AbstractEndpoint<?, ?>) endpoint;
    e.stop();
    e.shutdownExecutor();
  }

  /** A NIO endpoint that binds nothing and starts no acceptor or poller. */
  static final class QuietEndpoint extends NioEndpoint {
    private final boolean failBind;

    QuietEndpoint(boolean failBind) {
      this.failBind = failBind;
    }

    @Override
    public void bind() throws Exception {
      if (failBind) {
        throw new java.net.BindException("injected: address in use");
      }
    }

    @Override
    public void unbind() {}

    @Override
    public void startInternal() {
      // running = true without acceptor and poller threads
    }

    @Override
    public void stopInternal() {}
  }
}
