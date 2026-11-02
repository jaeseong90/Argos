/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A packaged WO-01 sample app (18 §4): the artifact that its {@code sampleAppElements} variant
 * publishes ({@code sample-apps/<app>/build/sample-app/<app>.jar}) and the launch descriptor next
 * to it ({@code <app>.properties}: kind, main class, build JDK, catalog, web contexts).
 *
 * @param name app name ({@code shop-boot3})
 * @param artifact the jar or WAR
 * @param kind {@code boot-jar}, {@code jar} or {@code war}
 * @param runJdk the JDK the app is built for and smoke-tested on (its descriptor)
 * @param catalog {@code shop}, {@code batch} or {@code none}
 * @param contexts web contexts of a WAR (empty for jar apps)
 */
public record SampleApp(
    String name, Path artifact, String kind, int runJdk, String catalog, List<String> contexts) {

  public SampleApp {
    contexts = List.copyOf(contexts);
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
    if (kind.equals("war")) {
      for (String context : properties.getProperty("contexts", name).split(",", -1)) {
        if (!context.isBlank()) {
          contexts.add(context.strip());
        }
      }
    }
    return new SampleApp(
        properties.getProperty("app", name).strip(),
        artifact,
        kind,
        Integer.parseInt(properties.getProperty("runJdk", "0").strip()),
        properties.getProperty("catalog", "shop").strip(),
        contexts);
  }

  /** True for apps started with {@code java -jar} (Spring Boot jars and plain executable jars). */
  public boolean isJar() {
    return kind.equals("boot-jar") || kind.equals("jar");
  }

  /** The readiness path: Spring Boot's health endpoint for catalog apps, else {@code /}. */
  public String readyPath() {
    return catalog.equals("none") ? "/" : "/actuator/health";
  }
}
