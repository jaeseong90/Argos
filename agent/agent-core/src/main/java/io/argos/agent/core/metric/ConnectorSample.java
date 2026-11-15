/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

/**
 * The values of one registered Tomcat connector endpoint at one moment ({@link
 * TomcatRegistry#snapshot()}): the sources of the 10 §11 series {@code tomcat_threads_busy}, {@code
 * tomcat_threads_current}, {@code tomcat_threads_max}, {@code tomcat_connections_current} and
 * {@code tomcat_connections_max} with the label {@code connector} = {@link #name()}. Immutable.
 *
 * <p>A value Tomcat does not report (an external or virtual-thread executor, an unlimited
 * connection count) or whose getter failed is {@value Getters#MISSING}, never 0 (C-4).
 */
public final class ConnectorSample {
  private final String name;
  private final int port;
  private final long threadsBusy;
  private final long threadsCurrent;
  private final long threadsMax;
  private final long connectionsCurrent;
  private final long connectionsMax;

  ConnectorSample(
      String name,
      int port,
      long threadsBusy,
      long threadsCurrent,
      long threadsMax,
      long connectionsCurrent,
      long connectionsMax) {
    this.name = name;
    this.port = port;
    this.threadsBusy = threadsBusy;
    this.threadsCurrent = threadsCurrent;
    this.threadsMax = threadsMax;
    this.connectionsCurrent = connectionsCurrent;
    this.connectionsMax = connectionsMax;
  }

  /** The endpoint's name ({@code http-nio-8080}), the {@code connector} label. */
  public String name() {
    return name;
  }

  /** The bound local port, else the configured port; -1 when unknown. */
  public int port() {
    return port;
  }

  /** {@code getCurrentThreadsBusy()}; -1 when missing. */
  public long threadsBusy() {
    return threadsBusy;
  }

  /** {@code getCurrentThreadCount()}; -1 when missing. */
  public long threadsCurrent() {
    return threadsCurrent;
  }

  /** {@code getMaxThreads()}; -1 when missing, always for a virtual-thread executor (10 §6.4). */
  public long threadsMax() {
    return threadsMax;
  }

  /** {@code getConnectionCount()}; -1 when missing. */
  public long connectionsCurrent() {
    return connectionsCurrent;
  }

  /** {@code getMaxConnections()}; -1 when missing or unlimited. */
  public long connectionsMax() {
    return connectionsMax;
  }

  @Override
  public String toString() {
    return "ConnectorSample{"
        + name
        + ", port="
        + port
        + ", busy="
        + threadsBusy
        + ", current="
        + threadsCurrent
        + ", max="
        + threadsMax
        + ", connections="
        + connectionsCurrent
        + ", maxConnections="
        + connectionsMax
        + "}";
  }
}
