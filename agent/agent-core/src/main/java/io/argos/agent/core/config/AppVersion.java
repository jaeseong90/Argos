/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.Locale;
import java.util.jar.Attributes;

/**
 * The application version tag {@code app.version} (10 §15 "jar 매니페스트 {@code
 * Implementation-Version}", F-A21, T-AG-066): the configured value when set (not blank), otherwise
 * the {@code Implementation-Version} of the jar that {@code sun.java.command} names ({@code java
 * -jar app.jar}), otherwise empty: an application started from a class path ({@code java -cp ...
 * com.example.Main}), a WAR deployed to a servlet container (the container's launcher is the main
 * class) or a jar without the attribute has no version.
 *
 * <p>The jar manifest is the one {@link AppPackages} reads for the main class: both go through
 * {@link AppPackages#MAIN_JAR_MANIFESTS}, which reads the main jar's manifest at most once per JVM.
 * Reading a jar is file I/O, so this runs off the premain thread: on {@code argos-sender}, where
 * the hello ({@code AgentIdentity}) and the test exporters ({@code Exporters}) are created.
 */
public final class AppVersion {
  /** Where the version came from. */
  public enum Source {
    /** {@code app.version} is set. */
    CONFIGURED,
    /** {@code Implementation-Version} of the main jar's manifest. */
    MANIFEST,
    /** No version: empty. */
    NONE;

    /** Lower-case name for self-diagnostics. */
    public String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The manifest attribute of the version (JAR File Specification). */
  static final String IMPLEMENTATION_VERSION = "Implementation-Version";

  private static final AppVersion NONE = new AppVersion("", Source.NONE);

  private final String value;
  private final Source source;

  AppVersion(String value, Source source) {
    this.value = value;
    this.source = source;
  }

  /** The version of {@code config}: configured, else the main jar's manifest, else empty. */
  public static AppVersion of(AgentConfig config) {
    return resolve(
        config == null ? null : config.appVersion(),
        System.getProperty("sun.java.command"),
        AppPackages.MAIN_JAR_MANIFESTS);
  }

  /** The version for a configured value and a {@code sun.java.command} value (unit test seam). */
  static AppVersion resolve(
      String configured, String command, AppPackages.ManifestReader manifests) {
    String set = trimmed(configured);
    if (set != null) {
      return new AppVersion(set, Source.CONFIGURED);
    }
    if (command == null || command.trim().isEmpty()) {
      return NONE;
    }
    String jar = AppPackages.jarPath(command.trim());
    if (jar == null) {
      return NONE;
    }
    Attributes attributes = manifests.read(jar);
    String version =
        attributes == null ? null : trimmed(attributes.getValue(IMPLEMENTATION_VERSION));
    return version == null ? NONE : new AppVersion(version, Source.MANIFEST);
  }

  /** The version, empty when there is none (never null). */
  public String value() {
    return value;
  }

  /** The version, or null when there is none (an optional field left out). */
  public String valueOrNull() {
    return value.isEmpty() ? null : value;
  }

  public Source source() {
    return source;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }
}
