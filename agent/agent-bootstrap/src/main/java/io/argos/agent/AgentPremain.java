/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.function.Function;
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
 * <p>It never lets an exception reach the application: a failure here only means the application
 * runs without the agent. It writes nothing to stdout or stderr (F-A35) with one exception
 * (ADR-0009 C-78): when the start fails before the agent's logger exists (P0..P2: the jar cannot be
 * opened or appended, the agent class loader or the core cannot be loaded), there is no log file,
 * so exactly one line {@value #DISABLED_LINE_PREFIX}{@code <reason>} goes to stderr (one line, at
 * most {@value #MAX_REASON_CHARS} characters, tokens masked as {@code argos_****}, ADR-0010 C-94).
 * A failure inside the core is reported by the core's logger instead ({@code AgentStarter}, {@code
 * AgentLogger.startFailed}), which writes the same line only when no log file can be written.
 */
public final class AgentPremain {
  /** Kill switch, second stage (F-A15, 10 §4.3 P0): nothing is installed. */
  static final String DISABLED_PROPERTY = "argos.agent.disabled";

  /** Prefix of the single stderr line of a failed start without a log file (ADR-0009 C-78). */
  static final String DISABLED_LINE_PREFIX = "argos: agent disabled: ";

  /** Upper bound of the reason text of that line (the core's {@code AgentLogger} uses the same). */
  static final int MAX_REASON_CHARS = 200;

  /** Token format of 16 §2.1 ({@code TokenHolder} in the core), masked as {@code argos_****}. */
  private static final String TOKEN_PATTERN = "argos_[A-Za-z0-9_-]{43}";

  private static final String BOOTSTRAP_CLASS = "io.argos.agent.bootstrap.AgentBootstrap";

  /** {@code AgentBootstrap.Handoff}: {@code AgentBootstrap.start} behind a {@link Function}. */
  static final String BOOTSTRAP_HANDOFF_CLASS = BOOTSTRAP_CLASS + "$Handoff";

  /**
   * Why the last start attempt failed before the agent logger existed (also printed as the C-78
   * line). Null when the start succeeded.
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
    String failure;
    try {
      // P0: second-stage kill switch, checked before anything else.
      if (Boolean.getBoolean(DISABLED_PROPERTY)) {
        return;
      }
      File jar = agentJar();
      // P1: bootstrap classes become visible to every class loader (10 §4.3). The JVM opens the
      // jar itself for the bootstrap search path; this JarFile is handed to the agent class loader
      // so that the start path opens the jar only once (C-79).
      JarFile jarFile = new JarFile(jar, false);
      inst.appendToBootstrapClassLoaderSearch(jarFile);
      // P2: the bootstrap copy of AgentBootstrap, never a system class loader copy, entered
      // through its handoff (a no-argument constructor and an interface call instead of
      // Method.invoke, which spins method handles on JDK 18+, C-79). It returns the reason when
      // the core could not be started (null otherwise).
      Class<?> handoff = Class.forName(BOOTSTRAP_HANDOFF_CLASS, true, null);
      @SuppressWarnings("unchecked")
      Function<Object[], String> bootstrap =
          (Function<Object[], String>) handoff.getDeclaredConstructor().newInstance();
      failure = bootstrap.apply(new Object[] {inst, jarFile, jar, agentArgs, startNanos});
    } catch (Throwable t) {
      // Never propagate to the application (F-A15).
      Throwable cause =
          t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
      failure = String.valueOf(cause);
    }
    if (failure != null) {
      startFailure = failure;
      printDisabledLine(failure);
    }
  }

  /**
   * ADR-0009 C-78: the single stderr line of a start that failed before the agent's logger existed
   * (no log file can exist then). Never throws.
   */
  static void printDisabledLine(String reason) {
    try {
      System.err.println(disabledLine(reason));
      System.err.flush();
    } catch (Throwable ignored) {
      // Nothing else may be printed and nothing may reach the application.
    }
  }

  /**
   * {@value #DISABLED_LINE_PREFIX}{@code <reason>}: one line, at most {@value #MAX_REASON_CHARS}
   * characters of reason, tokens masked (SEC-06, ADR-0010 C-94).
   */
  static String disabledLine(String reason) {
    String text = reason == null || reason.trim().isEmpty() ? "unknown error" : reason.trim();
    text =
        text.replaceAll(TOKEN_PATTERN, "argos_****")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ');
    if (text.length() > MAX_REASON_CHARS) {
      text = text.substring(0, MAX_REASON_CHARS) + "...";
    }
    return DISABLED_LINE_PREFIX + text;
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
