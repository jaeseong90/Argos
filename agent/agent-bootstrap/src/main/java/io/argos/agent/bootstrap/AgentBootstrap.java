/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * Creates the {@link AgentClassLoader} over {@code inst/**} of the agent jar and hands over to
 * {@code io.argos.agent.core.boot.AgentStarter} (10 §4.3 P2).
 *
 * <p>In agent mode this class is loaded by the bootstrap class loader (the jar was appended to its
 * search path by {@code AgentPremain}). In {@code --check} mode it is loaded by the system class
 * loader and the agent class loader delegates to that loader instead.
 *
 * <p>Start path (ADR-0009 C-79): the jar that P1 opened is the jar of the agent class loader (no
 * second open), the platform class loader is found without reflection where possible (a reflective
 * call of the caller-sensitive {@code ClassLoader.getPlatformClassLoader()} costs about 10 ms on
 * JDK 18+), and the loader records the classes defined on the premain thread until the core ends
 * the start sequence.
 */
public final class AgentBootstrap {
  static final String STARTER_CLASS = "io.argos.agent.core.boot.AgentStarter";

  /**
   * {@code AgentStarter.Handoff}: {@code AgentStarter.start(Instrumentation, File, String,
   * AgentControl, long)} behind a {@link Consumer} of {@code {Instrumentation, File, String,
   * AgentControl, Long}}.
   */
  static final String STARTER_HANDOFF_CLASS = STARTER_CLASS + "$Handoff";

  static final String CHECK_CLASS = "io.argos.agent.core.check.CheckCommand";

  /** Class name of the JDK 9+ platform class loader. */
  static final String PLATFORM_LOADER_CLASS =
      "jdk.internal.loader.ClassLoaders$PlatformClassLoader";

  private static boolean started;

  /**
   * The start failure that happened before the core logger existed (also returned to {@code
   * AgentPremain}, which prints it as the C-78 line).
   */
  static volatile String startFailure;

  private AgentBootstrap() {}

  /**
   * {@link #start} for {@code AgentPremain}, which may not refer to bootstrap types (10 §4.2): a
   * public no-argument constructor and a {@link Function} call instead of {@code Method.invoke},
   * which spins method handles for this signature on JDK 18+ (about 15 ms of P2 on JDK 21, ADR-0009
   * C-79). The argument array is {@code {Instrumentation, JarFile, File, String, Long}}.
   */
  public static final class Handoff implements Function<Object[], String> {
    @Override
    public String apply(Object[] arguments) {
      return start(
          (Instrumentation) arguments[0],
          (JarFile) arguments[1],
          (File) arguments[2],
          (String) arguments[3],
          ((Long) arguments[4]).longValue());
    }
  }

  private static synchronized boolean firstStart() {
    if (started) {
      return false;
    }
    started = true;
    return true;
  }

  /**
   * P2: builds the agent class loader over {@code jar} (the jar P1 appended to the bootstrap search
   * path; parent: platform class loader on JDK 9+, the bootstrap class loader on JDK 8, DSN-10-01)
   * and starts the core. Called once; later calls do nothing.
   *
   * <p>The core reports its own failures ({@code AgentStarter.start} never throws). A failure
   * before the core could start (the agent class loader, a missing or unloadable core class) is
   * returned instead, so that {@code AgentPremain} prints the single stderr line of ADR-0009 C-78:
   * there is no log file at that point.
   *
   * @return null when the core was started (or this is a repeated call), else the reason the core
   *     could not be started (one {@code Throwable.toString()})
   */
  public static String start(
      Instrumentation inst, JarFile jar, File agentJar, String agentArgs, long premainStartNanos) {
    if (!firstStart()) {
      return null;
    }
    AgentClassLoader loader = null;
    try {
      loader = new AgentClassLoader(jar, agentJar, platformClassLoader());
      loader.startRecording(Thread.currentThread());
      AgentControl control = new AgentControl(loader);
      // AgentStarter.start through its handoff (no Method.invoke on the start path, C-79).
      Class<?> starter = Class.forName(STARTER_HANDOFF_CLASS, true, loader);
      @SuppressWarnings("unchecked")
      Consumer<Object[]> start =
          (Consumer<Object[]>) starter.getDeclaredConstructor().newInstance();
      start.accept(new Object[] {inst, agentJar, agentArgs, control, premainStartNanos});
      return null;
    } catch (Throwable t) {
      Throwable cause =
          t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
      startFailure = String.valueOf(cause);
      if (loader != null) {
        loader.stopRecording();
      }
      return startFailure;
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
   * The platform class loader on JDK 9+ and {@code null} (the bootstrap class loader) on JDK 8
   * (DSN-10-01). On JDK 9+ it is the topmost ancestor of the system class loader when that is the
   * JDK's platform loader; otherwise (a custom system class loader without the platform loader as
   * an ancestor) {@code ClassLoader.getPlatformClassLoader()} is called reflectively, as the agent
   * targets Java 8.
   */
  static ClassLoader platformClassLoader() {
    if (isJava8(System.getProperty("java.specification.version"))) {
      return null;
    }
    ClassLoader candidate = topmostAncestor(ClassLoader.getSystemClassLoader());
    if (candidate != null && PLATFORM_LOADER_CLASS.equals(candidate.getClass().getName())) {
      return candidate;
    }
    return platformClassLoaderReflectively();
  }

  /** True for {@code java.specification.version} {@code 1.x} (JDK 8 and older). */
  static boolean isJava8(String specificationVersion) {
    return specificationVersion != null && specificationVersion.startsWith("1.");
  }

  /** The last non-null loader of {@code loader}'s parent chain ({@code loader} itself included). */
  static ClassLoader topmostAncestor(ClassLoader loader) {
    ClassLoader current = loader;
    // Bounded walk: parent chains are short; a cycle cannot exist, the bound is only a guard.
    for (int i = 0; current != null && current.getParent() != null && i < 64; i++) {
      current = current.getParent();
    }
    return current;
  }

  static ClassLoader platformClassLoaderReflectively() {
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
