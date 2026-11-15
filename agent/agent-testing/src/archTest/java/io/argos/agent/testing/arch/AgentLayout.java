/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Package layout of an agent (10 §2): {@code <root>.bootstrap}, {@code <root>.core}, {@code
 * <root>.instrumentation.<module>}, {@code <root>.shaded}, plus entry point classes such as {@code
 * <root>.AgentPremain}. The production layout uses root {@code io.argos.agent}; the fixtures use
 * the same layout below their own roots, so every rule runs unchanged against both.
 *
 * @param root root package
 * @param entryPoints classes of the bootstrap module outside the bootstrap package (10 §4.1)
 * @param relocatedPackages packages the agent shades into {@code <root>.shaded} (AgentShading)
 * @param instrumentationModules Gradle project names of the instrumentation modules
 *     (settings.gradle.kts), e.g. {@code httpclient-jdk11}; the allow-list uses these names
 * @param allowlist readOnly=false / AssignReturned allow-list (10 §5.3 A-5)
 */
public record AgentLayout(
    String root,
    List<String> entryPoints,
    List<String> relocatedPackages,
    List<String> instrumentationModules,
    AdviceAllowlist allowlist) {

  /** Agent root package in production. */
  public static final String PRODUCTION_ROOT = "io.argos.agent";

  /** ByteBuddy's Advice class; its nested annotations configure advice methods. */
  static final String ADVICE = "net.bytebuddy.asm.Advice";

  private static final String HTTPCLIENT_PREFIX = "httpclient-";

  public AgentLayout {
    Objects.requireNonNull(root, "root");
    entryPoints = List.copyOf(entryPoints);
    relocatedPackages = List.copyOf(relocatedPackages);
    instrumentationModules = List.copyOf(instrumentationModules);
    Objects.requireNonNull(allowlist, "allowlist");
  }

  /**
   * The production layout. Relocated packages, instrumentation modules and the allow-list come from
   * the archTest task (build-logic AgentShading, settings.gradle.kts,
   * agent-instrumentation/src/main/resources/argos-advice-allowlist.txt).
   */
  public static AgentLayout production() {
    Path allowlist = Path.of(requiredProperty("argos.archunit.adviceAllowlist"));
    return new AgentLayout(
        PRODUCTION_ROOT,
        List.of(PRODUCTION_ROOT + ".AgentPremain"),
        listProperty("argos.archunit.relocatedPackages"),
        listProperty("argos.archunit.instrumentationModules"),
        AdviceAllowlist.read(allowlist));
  }

  static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "System property " + name + " is not set; run the rules with ./gradlew archTest");
    }
    return value;
  }

  private static List<String> listProperty(String name) {
    return Arrays.stream(requiredProperty(name).split(",", -1))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  public String bootstrap() {
    return root + ".bootstrap";
  }

  public String core() {
    return root + ".core";
  }

  public String instrumentation() {
    return root + ".instrumentation";
  }

  public String instrumentationApi() {
    return instrumentation() + ".api";
  }

  /** The instrumentation module that intercepts logging frameworks (MDC injection, 10 §6.9). */
  public String loggingModule() {
    return instrumentation() + ".logging";
  }

  public String shaded() {
    return root + ".shaded";
  }

  /** Binary name of the advice guard holder (10 §3 {@code Argos.skip(int)}). */
  public String guardHolder() {
    return bootstrap() + ".Argos";
  }

  /** Packages where writing to stdout/stderr is allowed: stderr fallback sink, --check output. */
  public List<String> consolePackages() {
    return List.of(core() + ".log", core() + ".check");
  }

  /**
   * Classes that may write to stderr only (not stdout, no stack traces): the entry point, which
   * prints the single {@code argos: agent disabled: <reason>} line of a start that failed before
   * the agent's logger existed (ADR-0009 C-78).
   */
  public List<String> stderrOnlyClasses() {
    return List.of(root + ".AgentPremain");
  }

  public static boolean inPackage(String className, String packageName) {
    return className.startsWith(packageName + ".");
  }

  /** True for classes of this agent (any package below the root). */
  public boolean isAgentClass(String className) {
    return inPackage(className, root);
  }

  public boolean isBootstrapModule(String className) {
    return inPackage(className, bootstrap()) || entryPoints.contains(topLevel(className));
  }

  /** True for a class of a package that the agent relocates, or for the relocated form itself. */
  public boolean isShadedLibrary(String className) {
    return inPackage(className, shaded())
        || relocatedPackages.stream().anyMatch(p -> inPackage(className, p));
  }

  /**
   * The name of {@code binaryName} below ByteBuddy's {@code Advice} ({@code OnMethodEnter}, {@code
   * AssignReturned.ToReturned}, ...), for the original and the relocated ({@code
   * <root>.shaded.net.bytebuddy.asm.Advice$...}) form; empty for every other type.
   */
  public Optional<String> adviceMemberName(String binaryName) {
    for (String advice : List.of(ADVICE, shaded() + "." + ADVICE)) {
      if (binaryName.startsWith(advice + "$")) {
        return Optional.of(binaryName.substring(advice.length() + 1).replace('$', '.'));
      }
    }
    return Optional.empty();
  }

  /**
   * The instrumentation module key of a class: the first package segment below {@code
   * <root>.instrumentation}, or the first two for {@code httpclient.*} modules (10 §2). Package
   * keys identify modules within the rules; {@link #gradleModuleOf} maps them to project names.
   */
  public Optional<String> moduleOf(String className) {
    if (!inPackage(className, instrumentation())) {
      return Optional.empty();
    }
    String rest = className.substring(instrumentation().length() + 1);
    String[] segments = rest.split("\\.", -1);
    if (segments.length < 2) {
      return Optional.empty();
    }
    if (segments[0].equals("httpclient") && segments.length > 2) {
      return Optional.of("httpclient." + segments[1]);
    }
    return Optional.of(segments[0]);
  }

  public Optional<String> moduleOf(JavaClass javaClass) {
    return moduleOf(javaClass.getName());
  }

  /**
   * The package key of a Gradle instrumentation module (02 §4.1, 10 §2): {@code httpclient-jdk11}
   * -> {@code httpclient.jdk11}, {@code servlet-async} -> {@code servletasync}, {@code jdbc} ->
   * {@code jdbc}.
   */
  public static String packageKeyOf(String gradleModule) {
    if (gradleModule.startsWith(HTTPCLIENT_PREFIX)) {
      return "httpclient." + gradleModule.substring(HTTPCLIENT_PREFIX.length()).replace("-", "");
    }
    return gradleModule.replace("-", "");
  }

  /**
   * The Gradle project name of a class's instrumentation module (settings.gradle.kts, e.g. {@code
   * httpclient-jdk11}), the key of the allow-list's module column. A package without a module in
   * {@link #instrumentationModules} keeps its package key.
   */
  public Optional<String> gradleModuleOf(String className) {
    return moduleOf(className)
        .map(
            key ->
                instrumentationModules.stream()
                    .filter(module -> packageKeyOf(module).equals(key))
                    .findFirst()
                    .orElse(key));
  }

  /** Name of the outermost class ({@code a.B$C} -> {@code a.B}). */
  public static String topLevel(String className) {
    int dollar = className.indexOf('$', className.lastIndexOf('.') + 1);
    return dollar < 0 ? className : className.substring(0, dollar);
  }
}
