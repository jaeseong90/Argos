/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The application packages of {@code instrumentation.app-packages} (10 §15 "자동(main 클래스 패키지 상위
 * 2단계)", 10 §9.1 {@code topAppFrameId}, U-20): the configured value when set, otherwise inferred
 * once per JVM from the main class.
 *
 * <ol>
 *   <li>The main class: {@code sun.java.command} names a jar ({@code java -jar app.jar}): its
 *       manifest's {@code Start-Class} (Spring Boot), else its {@code Main-Class}; otherwise the
 *       first token is the main class ({@code java -cp ... com.example.Main}, {@code
 *       module/com.example.Main}).
 *   <li>Its package cut to the top two levels ({@code com.example.shop.App} gives {@code
 *       com.example}). When those two levels are a reserved namespace ({@code io.argos}, the JDK
 *       ones), one level deeper ({@code io.argos.sample.boot3.App} gives {@code io.argos.sample};
 *       WO-18 assumption C-1). The agent's own packages ({@code io.argos.agent}), JDK namespaces,
 *       the default package and well-known launchers (Tomcat's {@code Bootstrap}, the Spring Boot
 *       loader without {@code Start-Class}, build tools) give no packages: unknown stays empty.
 * </ol>
 *
 * <p>WO-18 exposes the result (effective configuration and self-diagnostics, one INFO line with the
 * package count and the source only, LOG-01); WO-19 uses it for {@code error_top_frame_id} (the
 * first frame of an application package, ADR-0010 C-237 (6), C-131). Inference reads a jar
 * manifest, so it runs off the premain thread (the remote wiring on {@code argos-sender}, and the
 * {@code argos-watch} thread at its start for exporters without a session). The main jar's manifest
 * is read at most once per JVM ({@link #MAIN_JAR_MANIFESTS}) and shared with {@link AppVersion}
 * ({@code app.version} from {@code Implementation-Version}, WO-19).
 */
public final class AppPackages {
  /** Where the packages came from. */
  public enum Source {
    /** {@code instrumentation.app-packages} is set. */
    CONFIGURED,
    /** {@code Start-Class} of the jar's manifest (Spring Boot). */
    START_CLASS,
    /** {@code Main-Class} of the jar's manifest. */
    MAIN_CLASS,
    /** The main class named on the command line. */
    COMMAND,
    /** No usable main class: no packages. */
    NONE;

    /** Lower-case name for self-diagnostics. */
    public String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Self-diagnostics extras keys. */
  public static final String EXTRA_PACKAGES = "app_packages";

  public static final String EXTRA_SOURCE = "app_packages_source";

  /** Namespaces whose top two levels are never application packages by themselves. */
  private static final String[] RESERVED = {
    "io.argos.", "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun."
  };

  /** Packages a main class may never yield (the JDK, the agent itself). */
  private static final String[] NEVER = {
    "io.argos.agent.", "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun."
  };

  /** Main classes of launchers: their packages say nothing about the application. */
  private static final String[] LAUNCHERS = {
    "org.springframework.boot.loader.",
    "org.apache.catalina.startup.",
    "org.eclipse.jetty.start.",
    "org.codehaus.plexus.classworlds.",
    "org.gradle.",
    "worker.org.gradle.",
    "com.intellij.rt.",
    "org.junit.platform.console."
  };

  private static final AtomicBoolean LOGGED = new AtomicBoolean();
  private static volatile AppPackages inferred;

  private final List<String> packages;
  private final Source source;

  AppPackages(List<String> packages, Source source) {
    this.packages = Collections.unmodifiableList(new ArrayList<String>(packages));
    this.source = source;
  }

  /** The configured packages of {@code config}, or the ones inferred from this JVM's main class. */
  public static AppPackages of(AgentConfig config) {
    List<String> configured = config.instrumentationAppPackages();
    if (configured != null && !configured.isEmpty()) {
      return new AppPackages(configured, Source.CONFIGURED);
    }
    AppPackages known = inferred;
    if (known == null) {
      known = infer(System.getProperty("sun.java.command"), MAIN_JAR_MANIFESTS);
      inferred = known;
    }
    return known;
  }

  /** Reads the manifest of a jar (a seam for the unit tests). */
  interface ManifestReader {
    /** The manifest's main attributes, or null when the jar or its manifest cannot be read. */
    Attributes read(String jarPath);
  }

  static final ManifestReader JAR_MANIFESTS =
      new ManifestReader() {
        @Override
        public Attributes read(String jarPath) {
          File file = new File(jarPath);
          if (!file.isFile()) {
            return null;
          }
          try (JarFile jar = new JarFile(file, false)) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : manifest.getMainAttributes();
          } catch (IOException | RuntimeException e) {
            return null;
          }
        }
      };

  /**
   * {@link #JAR_MANIFESTS} that keeps the last jar's attributes: the main jar named by {@code
   * sun.java.command} is read once per JVM for both {@link AppPackages} and {@link AppVersion}. One
   * entry only (the main jar), so the cache is bounded.
   */
  static final ManifestReader MAIN_JAR_MANIFESTS = new CachingManifestReader(JAR_MANIFESTS);

  /**
   * One-entry cache over a {@link ManifestReader} (the attributes are only read, never changed).
   */
  static final class CachingManifestReader implements ManifestReader {
    private final ManifestReader delegate;
    private volatile Cached cached;

    CachingManifestReader(ManifestReader delegate) {
      this.delegate = delegate;
    }

    @Override
    public Attributes read(String jarPath) {
      Cached known = cached;
      if (known != null && known.path.equals(jarPath)) {
        return known.attributes;
      }
      Attributes attributes = delegate.read(jarPath);
      cached = new Cached(jarPath, attributes);
      return attributes;
    }

    private static final class Cached {
      final String path;
      final Attributes attributes;

      Cached(String path, Attributes attributes) {
        this.path = path;
        this.attributes = attributes;
      }
    }
  }

  /** The packages inferred from a {@code sun.java.command} value (see the class comment). */
  static AppPackages infer(String command, ManifestReader manifests) {
    if (command == null || command.trim().isEmpty()) {
      return none();
    }
    String text = command.trim();
    String jar = jarPath(text);
    String mainClass;
    Source source;
    if (jar != null) {
      Attributes attributes = manifests.read(jar);
      if (attributes == null) {
        return none();
      }
      String start = trimmed(attributes.getValue("Start-Class"));
      if (start != null) {
        mainClass = start;
        source = Source.START_CLASS;
      } else {
        mainClass = trimmed(attributes.getValue("Main-Class"));
        source = Source.MAIN_CLASS;
      }
    } else {
      String first = text.split("\\s+", 2)[0];
      int slash = first.indexOf('/');
      mainClass = slash >= 0 ? first.substring(slash + 1) : first;
      source = Source.COMMAND;
    }
    String pkg = packageOf(mainClass);
    return pkg == null ? none() : new AppPackages(Collections.singletonList(pkg), source);
  }

  /**
   * The application package of {@code mainClass} (top two levels, one deeper under a reserved
   * namespace), or null when the class says nothing about the application.
   */
  static String packageOf(String mainClass) {
    if (mainClass == null || startsWithAny(mainClass, LAUNCHERS)) {
      return null;
    }
    int dot = mainClass.lastIndexOf('.');
    if (dot <= 0) {
      return null;
    }
    String pkg = mainClass.substring(0, dot);
    if (!isJavaName(pkg) || startsWithAny(pkg + ".", NEVER)) {
      return null;
    }
    String[] segments = pkg.split("\\.", -1);
    int levels = Math.min(2, segments.length);
    String candidate = join(segments, levels);
    if (reserved(candidate)) {
      if (segments.length <= levels) {
        return null;
      }
      candidate = join(segments, levels + 1);
    }
    return startsWithAny(candidate + ".", NEVER) ? null : candidate;
  }

  /** The packages (empty when none). */
  public List<String> packages() {
    return packages;
  }

  public Source source() {
    return source;
  }

  /** One INFO line per JVM: the package count and the source (no string argument, LOG-01). */
  public void logOnce() {
    if (LOGGED.compareAndSet(false, true)) {
      AgentLogger.info(
          "application packages: {} package(s), source {} (instrumentation.app-packages)",
          packages.size(),
          source);
    }
  }

  /** Self-diagnostics extras: {@code app_packages} (comma separated) and its source. */
  public Map<String, Object> diagnostics() {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put(EXTRA_PACKAGES, joined(","));
    out.put(EXTRA_SOURCE, source.label());
    return out;
  }

  /**
   * {@code base} with the inferred packages as the value of an unset {@code
   * instrumentation.app-packages} (source default: the documented default is "automatic").
   */
  public EffectiveConfig effective(EffectiveConfig base) {
    if (base == null
        || source == Source.CONFIGURED
        || packages.isEmpty()
        || base.value(ConfigKeys.INSTRUMENTATION_APP_PACKAGES) != null) {
      return base;
    }
    Map<String, EffectiveConfig.Entry> entries =
        new LinkedHashMap<String, EffectiveConfig.Entry>(base.entries());
    entries.put(
        ConfigKeys.INSTRUMENTATION_APP_PACKAGES,
        new EffectiveConfig.Entry("[" + joined(", ") + "]", EffectiveConfig.Source.DEFAULT));
    return new EffectiveConfig(entries);
  }

  private String joined(String separator) {
    StringBuilder out = new StringBuilder();
    for (String pkg : packages) {
      out.append(out.length() == 0 ? "" : separator).append(pkg);
    }
    return out.toString();
  }

  private static AppPackages none() {
    return new AppPackages(Collections.<String>emptyList(), Source.NONE);
  }

  /** The jar path at the start of {@code command} ({@code java -jar}), or null. */
  static String jarPath(String command) {
    String lower = command.toLowerCase(Locale.ROOT);
    for (String suffix : new String[] {".jar", ".war"}) {
      int at = lower.indexOf(suffix);
      while (at >= 0) {
        int end = at + suffix.length();
        if (end == lower.length() || Character.isWhitespace(lower.charAt(end))) {
          return command.substring(0, end);
        }
        at = lower.indexOf(suffix, end);
      }
    }
    return null;
  }

  private static boolean reserved(String candidate) {
    String withDot = candidate + ".";
    for (String prefix : RESERVED) {
      if (withDot.startsWith(prefix) || prefix.startsWith(withDot)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isJavaName(String pkg) {
    for (String segment : pkg.split("\\.", -1)) {
      if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
        return false;
      }
      for (int i = 1; i < segment.length(); i++) {
        if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
          return false;
        }
      }
    }
    return true;
  }

  private static String join(String[] segments, int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      out.append(i == 0 ? "" : ".").append(segments[i]);
    }
    return out.toString();
  }

  private static boolean startsWithAny(String name, String[] prefixes) {
    for (String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }
}
