/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Agent entry point named by the manifest ({@code Premain-Class}, {@code Agent-Class}, {@code
 * Main-Class}, 10 §4.1, ADR-0003 C-21).
 *
 * <p>This class is loaded by the system class loader (the agent jar is on the system class path).
 * It must never refer to a bootstrap type directly: that would make the system class loader define
 * its own copies of {@code io.argos.agent.bootstrap.*}. It appends the jar to the bootstrap class
 * loader search and calls {@code io.argos.agent.bootstrap.AgentBootstrap} reflectively (10 §4.2 "중복
 * 방지").
 *
 * <p>It writes nothing to stdout or stderr (F-A35) and never lets an exception reach the
 * application: a failure here only means the application runs without the agent.
 */
public final class AgentPremain {
  /** Kill switch, second stage (F-A15, 10 §4.3 P0): nothing is installed. */
  static final String DISABLED_PROPERTY = "argos.agent.disabled";

  private static final String BOOTSTRAP_CLASS = "io.argos.agent.bootstrap.AgentBootstrap";

  /**
   * Why the last start attempt failed before the agent logger existed, for diagnostics with a
   * debugger or a heap dump only (nothing may be printed). Null when the start succeeded.
   */
  static volatile String startFailure;

  private AgentPremain() {}

  /** {@code -javaagent:argos-agent.jar} (N-30). */
  public static void premain(String agentArgs, Instrumentation inst) {
    start(agentArgs, inst);
  }

  /** Dynamic attach (1.x, F-A31); the same sequence as premain. */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    start(agentArgs, inst);
  }

  /**
   * {@code java -jar argos-agent.jar --check|--version} (10 §13.3, F-A27). Runs the check command
   * in the agent class loader without attaching to an application.
   */
  public static void main(String[] args) throws Exception {
    File jar = agentJar();
    Class<?> bootstrap = Class.forName(BOOTSTRAP_CLASS, true, AgentPremain.class.getClassLoader());
    Method check = bootstrap.getMethod("check", String[].class, File.class);
    Object code;
    try {
      code = check.invoke(null, args, jar);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
    System.exit((Integer) code);
  }

  private static void start(String agentArgs, Instrumentation inst) {
    long startNanos = System.nanoTime();
    try {
      // P0: second-stage kill switch, checked before anything else.
      if (Boolean.getBoolean(DISABLED_PROPERTY)) {
        return;
      }
      File jar = agentJar();
      // P1: bootstrap classes become visible to every class loader (10 §4.3).
      inst.appendToBootstrapClassLoaderSearch(new JarFile(jar, false));
      // P2: the bootstrap copy of AgentBootstrap, never a system class loader copy.
      Class<?> bootstrap = Class.forName(BOOTSTRAP_CLASS, true, null);
      Method method =
          bootstrap.getMethod("start", Instrumentation.class, File.class, String.class, long.class);
      method.invoke(null, inst, jar, agentArgs, startNanos);
    } catch (Throwable t) {
      // Never propagate to the application (F-A15); nothing may be printed (F-A35).
      startFailure = String.valueOf(t);
    }
  }

  /** The agent jar file this class was loaded from. */
  static File agentJar() throws URISyntaxException {
    CodeSource source = AgentPremain.class.getProtectionDomain().getCodeSource();
    if (source == null || source.getLocation() == null) {
      throw new IllegalStateException("cannot locate the Argos agent jar");
    }
    return new File(source.getLocation().toURI());
  }
}
