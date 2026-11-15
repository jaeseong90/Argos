/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric.redeploy;

import org.apache.tomcat.util.net.NioEndpoint;

/**
 * An application class that keeps a Tomcat connector endpoint in a static field (an embedded
 * server). {@code RegistryClassLoaderLeakTest} loads it in a child class loader together with
 * Tomcat (never through the test's own class loader), so it must not refer to anything of the agent
 * or of the test. The endpoint binds no socket.
 */
public final class RedeployTomcatApp {
  private static NioEndpoint endpoint;

  private RedeployTomcatApp() {}

  /** Creates the application's endpoint (not started, no socket) and returns it. */
  public static Object start() {
    NioEndpoint created = new NioEndpoint();
    created.setName("http-nio-redeploy");
    created.setPort(18089);
    created.setMaxThreads(23);
    endpoint = created;
    return endpoint;
  }
}
