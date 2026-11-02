/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * Creates the {@link AgentClassLoader} over {@code inst/**} of the agent jar and hands over to
 * {@code io.argos.agent.core.boot.AgentStarter} (10 §4.3 P2).
 *
 * <p>In agent mode this class is loaded by the bootstrap class loader (the jar was appended to its
 * search path by {@code AgentPremain}). In {@code --check} mode it is loaded by the system class
 * loader and the agent class loader delegates to that loader instead.
 */
public final class AgentBootstrap {
  static final String STARTER_CLASS = "io.argos.agent.core.boot.AgentStarter";
  static final String CHECK_CLASS = "io.argos.agent.core.check.CheckCommand";

  private static final AtomicBoolean STARTED = new AtomicBoolean();

  /** The start failure that happened before the core logger existed (diagnostics only). */
  static volatile String startFailure;

  private AgentBootstrap() {}

  /**
   * P2: builds the agent class loader (parent: platform class loader on JDK 9+, the bootstrap class
   * loader on JDK 8, DSN-10-01) and starts the core. Called once; later calls do nothing.
   */
  public static void start(
      Instrumentation inst, File agentJar, String agentArgs, long premainStartNanos) {
    if (!STARTED.compareAndSet(false, true)) {
      return;
    }
    try {
      AgentClassLoader loader =
          new AgentClassLoader(new JarFile(agentJar, false), agentJar, platformClassLoader());
      Class<?> starter = Class.forName(STARTER_CLASS, true, loader);
      Method start =
          starter.getMethod(
              "start",
              Instrumentation.class,
              File.class,
              String.class,
              AgentControl.class,
              long.class);
      start.invoke(null, inst, agentJar, agentArgs, new AgentControl(), premainStartNanos);
    } catch (Throwable t) {
      // The core logs its own failures; this only covers failures before it could.
      startFailure = String.valueOf(t instanceof InvocationTargetException ? t.getCause() : t);
    }
  }

  /**
   * {@code java -jar argos-agent.jar --check} (10 §13.3): runs the check command in an agent class
   * loader whose parent is the loader of this class (the system class loader in this mode).
   *
   * @return the process exit code (0 pass or warn, 1 fail, 2 usage error)
   */
  public static int check(String[] args, File agentJar) throws Exception {
    AgentClassLoader loader =
        new AgentClassLoader(
            new JarFile(agentJar, false), agentJar, AgentBootstrap.class.getClassLoader());
    Class<?> command = Class.forName(CHECK_CLASS, true, loader);
    Method run = command.getMethod("run", String[].class, File.class);
    try {
      return (Integer) run.invoke(null, args, agentJar);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  /**
   * {@code ClassLoader.getPlatformClassLoader()} on JDK 9+ (looked up reflectively, the agent
   * targets Java 8), {@code null} (the bootstrap class loader) on JDK 8.
   */
  static ClassLoader platformClassLoader() {
    try {
      Method method = ClassLoader.class.getMethod("getPlatformClassLoader");
      return (ClassLoader) method.invoke(null);
    } catch (NoSuchMethodException e) {
      return null;
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
