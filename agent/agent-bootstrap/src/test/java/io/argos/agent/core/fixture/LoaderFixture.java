/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.fixture;

/**
 * A class in an agent-owned package ({@code io.argos.agent.core.*}) whose bytes the {@code
 * AgentClassLoader} tests store as {@code inst/**.classdata}.
 */
public final class LoaderFixture {
  private LoaderFixture() {}

  public static String hello() {
    return "hello from " + LoaderFixture.class.getClassLoader();
  }

  /** A second agent-owned class: defined after the premain recording stopped. */
  public static final class Later {
    private Later() {}
  }
}
