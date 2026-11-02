/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The ignore list of the agent builder (10 §4.5, N-27): types the agent never transforms, checked
 * before any module matcher with cheap name tests.
 *
 * <ol>
 *   <li>Argos itself ({@code io.argos.*}, shaded libraries included), synthetic types and every
 *       class of the agent class loader.
 *   <li>Generated classes: lambdas, JDK/CGLIB/ByteBuddy/Javassist proxies.
 *   <li>JDK and Jakarta EE packages ({@code java.*}, {@code javax.*} except servlet and sql, {@code
 *       jdk.*}, {@code sun.*}, {@code com.sun.*}, {@code jakarta.*} except servlet), except the JDK
 *       classes in {@link #JDK_ALLOWLIST} / {@link #JDK_ALLOWLIST_PREFIXES}.
 *   <li>Other agents and dynamic-language runtimes.
 *   <li>Logging framework internals, except the classes the logging module instruments.
 *   <li>{@code instrumentation.exclude-classes} globs (field workaround, T-AG-042).
 * </ol>
 *
 * <p>Modules that instrument a JDK class must add it to {@link #JDK_ALLOWLIST} (P-6 classes are
 * listed); a JDK class outside the list is never transformed whatever a module matches.
 */
public final class IgnoreMatchers {
  /** JDK classes that modules may transform (10 §4.5 item 3). */
  public static final Set<String> JDK_ALLOWLIST =
      Collections.unmodifiableSet(
          new HashSet<String>(
              Arrays.asList(
                  "java.net.HttpURLConnection",
                  "sun.net.www.protocol.http.HttpURLConnection",
                  "java.util.concurrent.AbstractExecutorService",
                  "java.util.concurrent.ThreadPoolExecutor",
                  "java.util.concurrent.ScheduledThreadPoolExecutor",
                  "java.util.concurrent.ForkJoinPool",
                  "java.util.concurrent.ForkJoinTask",
                  "java.util.concurrent.FutureTask",
                  "java.util.concurrent.CompletableFuture",
                  "java.util.concurrent.ThreadPerTaskExecutor",
                  "jdk.internal.net.http.HttpClientImpl")));

  /** Prefixes of allowed JDK classes (nested classes, implementation packages). */
  public static final List<String> JDK_ALLOWLIST_PREFIXES =
      Collections.unmodifiableList(
          Arrays.asList(
              "sun.net.www.protocol.https.",
              "java.util.concurrent.CompletableFuture$AsyncSupply",
              "java.util.concurrent.CompletableFuture$AsyncRun",
              "java.util.concurrent.CompletableFuture$Uni"));

  /** Packages that are ignored unless allow-listed (item 3). */
  private static final String[] JDK_PREFIXES = {
    "java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta."
  };

  /** Parts of {@link #JDK_PREFIXES} that applications and containers implement (item 3). */
  private static final String[] JDK_EXCEPTIONS = {
    "javax.servlet.", "javax.sql.", "jakarta.servlet."
  };

  /**
   * The application's (unshaded) ByteBuddy package. Built at run time on purpose: Shadow relocates
   * every string constant that starts with a relocated package, so a literal {@code
   * "net.bytebuddy."} would read {@code "io.argos.agent.shaded.net.bytebuddy."} in the agent jar
   * and the application's ByteBuddy classes would leave the ignore list (ADR-0005 transport, C-23).
   * {@code checkOwnRelocatedLiterals} in agent-dist rejects such constants.
   */
  static final String APP_BYTEBUDDY_PREFIX = String.join(".", "net", "bytebuddy", "");

  /** Other agents and runtimes (item 4), logging internals (item 5). */
  private static final String[] OTHER_PREFIXES = {
    "io.argos.",
    "org.jacoco.",
    "io.opentelemetry.javaagent.",
    APP_BYTEBUDDY_PREFIX,
    "org.groovy.",
    "org.codehaus.groovy.",
    "kotlin.reflect.jvm.internal.",
    "ch.qos.logback.",
    "org.apache.logging.log4j.",
    "org.slf4j."
  };

  /** Logging classes that the logging module instruments (10 §6.9): not ignored. */
  private static final String[] LOGGING_EXCEPTIONS = {
    "ch.qos.logback.classic.spi.LoggingEvent",
    "org.apache.logging.log4j.core.impl.ThreadContextDataInjector"
  };

  /** Name fragments of generated classes (item 2). */
  private static final String[] GENERATED_MARKERS = {
    "$$Lambda",
    "$Proxy",
    "$$SpringCGLIB$$",
    "$$EnhancerBy",
    "$HibernateProxy$",
    "ByteBuddy$",
    "$$FastClassBy",
    "_$$_jvst"
  };

  /** Loaders of generated reflection accessors: their classes are never instrumented. */
  private static final Set<String> IGNORED_LOADER_TYPES =
      Collections.unmodifiableSet(
          new HashSet<String>(
              Arrays.asList(
                  "sun.reflect.DelegatingClassLoader",
                  "jdk.internal.reflect.DelegatingClassLoader")));

  private IgnoreMatchers() {}

  /**
   * Item 1-6 as a type matcher; {@code excludeGlobs} is {@code instrumentation.exclude-classes}.
   */
  public static ElementMatcher.Junction<TypeDescription> types(List<String> excludeGlobs) {
    final List<Pattern> excludes = new ArrayList<Pattern>();
    for (String glob : excludeGlobs) {
      excludes.add(Glob.toPattern(glob));
    }
    return new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
      @Override
      public boolean matches(TypeDescription type) {
        return ignored(type.getName(), excludes) || type.isSynthetic();
      }

      @Override
      public String toString() {
        return "argosIgnoredTypes";
      }
    };
  }

  /** The agent class loader and reflection accessor loaders (item 1). */
  public static ElementMatcher.Junction<ClassLoader> loaders(final ClassLoader agentLoader) {
    return new ElementMatcher.Junction.AbstractBase<ClassLoader>() {
      @Override
      @SuppressWarnings("ReferenceEquality") // class loader identity
      public boolean matches(ClassLoader loader) {
        return loader != null
            && (loader == agentLoader
                || IGNORED_LOADER_TYPES.contains(loader.getClass().getName()));
      }

      @Override
      public String toString() {
        return "argosIgnoredLoaders";
      }
    };
  }

  /** True for a JDK class that modules may transform ({@link #JDK_ALLOWLIST} and its prefixes). */
  public static boolean isJdkAllowlisted(String name) {
    if (JDK_ALLOWLIST.contains(name)) {
      return true;
    }
    for (String prefix : JDK_ALLOWLIST_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** True when the class named {@code name} must not be transformed (items 1-6, by name only). */
  static boolean ignored(String name, List<Pattern> excludes) {
    for (String marker : GENERATED_MARKERS) {
      if (name.contains(marker)) {
        return true;
      }
    }
    if (startsWithAny(name, JDK_PREFIXES)) {
      return !isJdkAllowlisted(name) && !startsWithAny(name, JDK_EXCEPTIONS);
    }
    if (startsWithAny(name, OTHER_PREFIXES) && !startsWithAny(name, LOGGING_EXCEPTIONS)) {
      return true;
    }
    for (Pattern exclude : excludes) {
      if (exclude.matcher(name).matches()) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWithAny(String name, String[] prefixes) {
    for (String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
