/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * An application the harness starts (18 §4, §5.2): a packaged WO-01 sample app (the artifact of its
 * {@code sampleAppElements} variant, {@code sample-apps/<app>/build/sample-app/<app>.jar} or {@code
 * .war}, with the launch descriptor {@code <app>.properties} next to it) or a plain main class on a
 * class path (the attach tests' {@code example.trivial.TrivialApp}).
 *
 * @param name app name ({@code shop-boot3})
 * @param artifact the jar or WAR; for {@code main} apps the first class path entry
 * @param kind {@code boot-jar}, {@code jar}, {@code war} or {@code main}
 * @param runJdk the JDK the app is built for and smoke-tested on (its descriptor)
 * @param catalog {@code shop}, {@code batch} or {@code none}
 * @param contexts web contexts of a WAR (empty otherwise)
 * @param mainClass main class of a {@code main} app (null otherwise)
 * @param classpath class path of a {@code main} app (empty otherwise)
 * @param tomcats standalone Tomcat distributions of a WAR, key to version in descriptor order
 *     ({@code tomcat10 -> 10.1.61}; empty otherwise)
 */
public record SampleApp(
    String name,
    Path artifact,
    String kind,
    int runJdk,
    String catalog,
    List<String> contexts,
    String mainClass,
    List<Path> classpath,
    Map<String, String> tomcats) {

  public SampleApp {
    contexts = List.copyOf(contexts);
    classpath = List.copyOf(classpath);
    tomcats = Collections.unmodifiableMap(new LinkedHashMap<>(tomcats));
  }

  /** A packaged app without a main class (jar, Boot jar, WAR without Tomcat distributions). */
  public SampleApp(
      String name, Path artifact, String kind, int runJdk, String catalog, List<String> contexts) {
    this(name, artifact, kind, runJdk, catalog, contexts, null, List.of(), Map.of());
  }

  /**
   * The app of {@code artifact}, described by the {@code <name>.properties} next to it; without a
   * descriptor the kind follows the file extension and the catalog is {@code shop}.
   */
  public static SampleApp fromArtifact(Path artifact) {
    String file = artifact.getFileName().toString();
    String name = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
    Path descriptor = artifact.resolveSibling(name + ".properties");
    Properties properties = new Properties();
    if (Files.exists(descriptor)) {
      try (Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.ISO_8859_1)) {
        properties.load(reader);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    String kind =
        properties.getProperty("kind", file.endsWith(".war") ? "war" : "boot-jar").strip();
    List<String> contexts = new ArrayList<>();
    Map<String, String> tomcats = new LinkedHashMap<>();
    if (kind.equals("war")) {
      for (String context : properties.getProperty("contexts", name).split(",", -1)) {
        if (!context.isBlank()) {
          contexts.add(context.strip());
        }
      }
      // tomcat=tomcat10:10.1.61,tomcat11:11.0.27 (argos.sample-app launch descriptor)
      for (String entry : properties.getProperty("tomcat", "").split(",", -1)) {
        if (!entry.isBlank()) {
          int colon = entry.indexOf(':');
          tomcats.put(
              (colon < 0 ? entry : entry.substring(0, colon)).strip(),
              colon < 0 ? "" : entry.substring(colon + 1).strip());
        }
      }
    }
    return new SampleApp(
        properties.getProperty("app", name).strip(),
        artifact,
        kind,
        Integer.parseInt(properties.getProperty("runJdk", "0").strip()),
        properties.getProperty("catalog", "shop").strip(),
        contexts,
        null,
        List.of(),
        tomcats);
  }

  /**
   * A WO-01 sample app by name: the artifact path comes from the system property {@code
   * argos.test.sampleApp.<name>} that agent-testing's Gradle test tasks set for the apps they build
   * ({@code -Pagent.e2e}).
   */
  public static SampleApp named(String name) {
    String path = System.getProperty("argos.test.sampleApp." + name);
    if (path == null || path.isBlank()) {
      throw new IllegalStateException(
          "sample app "
              + name
              + " not built for this run: system property argos.test.sampleApp."
              + name
              + " is not set (add it to -Pagent.e2e=<app>@<jdk>)");
    }
    return fromArtifact(Path.of(path));
  }

  /**
   * A plain Java application: {@code java -cp <classpath> <mainClass> <args>}. It runs to
   * completion ({@link AppLauncher#runToExit}) and is not an HTTP app.
   */
  public static SampleApp mainClass(String name, String mainClass, List<Path> classpath) {
    if (classpath.isEmpty()) {
      throw new IllegalArgumentException("empty class path for " + mainClass);
    }
    return new SampleApp(
        name, classpath.get(0), "main", 0, "none", List.of(), mainClass, classpath, Map.of());
  }

  /** {@link #mainClass(String, String, List)} with a {@code File.pathSeparator} class path. */
  public static SampleApp mainClass(String name, String mainClass, String classpath) {
    List<Path> entries = new ArrayList<>();
    for (String entry : classpath.split(File.pathSeparator, -1)) {
      if (!entry.isBlank()) {
        entries.add(Path.of(entry));
      }
    }
    return mainClass(name, mainClass, entries);
  }

  /** True for apps started with {@code java -jar} (Spring Boot jars and plain executable jars). */
  public boolean isJar() {
    return kind.equals("boot-jar") || kind.equals("jar");
  }

  /** True for WAR apps (deployed to a staged standalone Tomcat, {@link TomcatStaging}). */
  public boolean isWar() {
    return kind.equals("war");
  }

  /** True for {@link #mainClass} apps. */
  public boolean isMainClass() {
    return kind.equals("main");
  }

  /** The readiness path: Spring Boot's health endpoint for catalog apps, else {@code /}. */
  public String readyPath() {
    return catalog.equals("none") ? "/" : "/actuator/health";
  }

  /**
   * The URL path prefixes of the app: {@code /<context>} per web context of a WAR ({@code ""} for
   * the root context), a single {@code ""} for every other app.
   */
  public List<String> contextPaths() {
    if (!isWar()) {
      return List.of("");
    }
    List<String> paths = new ArrayList<>();
    for (String context : contexts) {
      String trimmed = context.replaceAll("^/+|/+$", "");
      paths.add(trimmed.isEmpty() || trimmed.equals("ROOT") ? "" : "/" + trimmed);
    }
    return paths.isEmpty() ? List.of("") : paths;
  }

  /**
   * The Tomcat a WAR runs in on {@code jdk} when the test picks none ({@link AppLauncher#tomcat}):
   * the first distribution of the descriptor on the app's build JDK and below, the last (newest)
   * one above it. This gives the 18 §4.1 defaults: shop-war-jakarta runs Tomcat 10.1 on JDK 17 and
   * Tomcat 11 on JDK 21 and 25; shop-war-javax runs its only Tomcat 9.
   */
  public String defaultTomcat(int jdk) {
    if (tomcats.isEmpty()) {
      throw new IllegalStateException(
          name + " has no standalone Tomcat in its launch descriptor (tomcat=<key>:<version>)");
    }
    List<String> keys = new ArrayList<>(tomcats.keySet());
    return jdk <= runJdk ? keys.get(0) : keys.get(keys.size() - 1);
  }

  /**
   * CATALINA_HOME of the Tomcat {@code key}: the system property {@code
   * argos.test.tomcatHome.<app>.<key>} (agent-testing's Gradle test tasks set it for the WAR apps
   * of {@code -Pagent.e2e} and stage the distribution), else {@code build/tomcat/<key>} of the
   * sample app ({@code <artifact>/../../tomcat/<key>}, where the {@code sampleStageTomcat<Key>}
   * task of the argos.sample-app plugin unpacks it).
   */
  public Path tomcatHome(String key) {
    if (!tomcats.containsKey(key)) {
      throw new IllegalArgumentException(
          name + " has no Tomcat '" + key + "' (descriptor: " + tomcats.keySet() + ")");
    }
    String property = System.getProperty("argos.test.tomcatHome." + name + "." + key);
    Path home =
        property != null && !property.isBlank()
            ? Path.of(property)
            : artifact.toAbsolutePath().getParent().resolveSibling("tomcat").resolve(key);
    if (!Files.isRegularFile(home.resolve("bin").resolve("bootstrap.jar"))) {
      throw new IllegalStateException(
          "Tomcat '"
              + key
              + "' of "
              + name
              + " is not staged at "
              + home
              + " (run :sample-apps:"
              + name
              + ":sampleStageTomcat"
              + Character.toUpperCase(key.charAt(0))
              + key.substring(1)
              + " or add the app to -Pagent.e2e)");
    }
    return home;
  }
}
