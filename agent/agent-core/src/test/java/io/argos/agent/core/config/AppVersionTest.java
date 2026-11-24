/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code app.version} (10 §15, F-A21, T-AG-066): the configured value first, else the {@code
 * Implementation-Version} of the jar that {@code sun.java.command} names, else empty (class path
 * runs, WAR deployments in a servlet container, jars without the attribute). The main jar's
 * manifest is read once per JVM and shared with {@link AppPackages}.
 */
@Tag("req:F-A21")
class AppVersionTest {
  /** A manifest reader over fixed jars: path to Implementation-Version (null: no attribute). */
  private static AppPackages.ManifestReader jars(
      final Map<String, String> versions, final AtomicInteger reads) {
    return new AppPackages.ManifestReader() {
      @Override
      public Attributes read(String jarPath) {
        reads.incrementAndGet();
        if (!versions.containsKey(jarPath)) {
          return null;
        }
        Attributes attributes = new Attributes();
        attributes.putValue("Start-Class", "com.example.shop.ShopApplication");
        String version = versions.get(jarPath);
        if (version != null) {
          attributes.putValue("Implementation-Version", version);
        }
        return attributes;
      }
    };
  }

  @Test
  @DisplayName(
      "T-AG-066 app.version from the main jar's manifest Implementation-Version (java -jar,"
          + " trimmed; executable WAR too)")
  void manifestVersion() {
    Map<String, String> versions = new HashMap<String, String>();
    versions.put("/apps/shop-boot3.jar", "0.0.0-SNAPSHOT");
    versions.put("/apps/boot war.war", " 2.4.1 ");
    AppPackages.ManifestReader reader = jars(versions, new AtomicInteger());

    AppVersion jar = AppVersion.resolve(null, "/apps/shop-boot3.jar --server.port=0", reader);
    assertThat(jar.value()).isEqualTo("0.0.0-SNAPSHOT");
    assertThat(jar.source()).isEqualTo(AppVersion.Source.MANIFEST);
    assertThat(jar.source().label()).isEqualTo("manifest");

    AppVersion war = AppVersion.resolve("  ", "/apps/boot war.war", reader);
    assertThat(war.value()).as("blank app.version counts as unset").isEqualTo("2.4.1");
  }

  @Test
  @DisplayName("T-AG-066 a configured app.version wins over the manifest (trimmed)")
  void configuredWins() {
    Map<String, String> versions = new HashMap<String, String>();
    versions.put("/apps/shop-boot3.jar", "0.0.0-SNAPSHOT");
    AtomicInteger reads = new AtomicInteger();
    AppVersion version =
        AppVersion.resolve(" 1.2.3 ", "/apps/shop-boot3.jar", jars(versions, reads));
    assertThat(version.value()).isEqualTo("1.2.3");
    assertThat(version.valueOrNull()).isEqualTo("1.2.3");
    assertThat(version.source()).isEqualTo(AppVersion.Source.CONFIGURED);
    assertThat(reads.get()).as("no manifest read when app.version is set").isZero();
  }

  @Test
  @DisplayName(
      "T-AG-066 empty version: class path run, Tomcat launcher (WAR deployment), unreadable jar,"
          + " jar without Implementation-Version, no sun.java.command")
  void emptyVersion() {
    Map<String, String> versions = new HashMap<String, String>();
    versions.put("/apps/plain.jar", null);
    AppPackages.ManifestReader reader = jars(versions, new AtomicInteger());
    String[] commands = {
      "com.example.Main --flag",
      "org.apache.catalina.startup.Bootstrap start",
      "/apps/missing.jar",
      "/apps/plain.jar",
      "",
      null
    };
    for (String command : commands) {
      AppVersion version = AppVersion.resolve(null, command, reader);
      assertThat(version.value()).as("command %s", command).isEmpty();
      assertThat(version.valueOrNull()).as("command %s", command).isNull();
      assertThat(version.source()).as("command %s", command).isEqualTo(AppVersion.Source.NONE);
    }
  }

  @Test
  @DisplayName(
      "T-AG-066 the main jar's manifest is read once and shared by app.version and the"
          + " application packages (AppPackages)")
  void manifestReadOnceAndShared() {
    final AtomicInteger reads = new AtomicInteger();
    AppPackages.ManifestReader counting =
        new AppPackages.ManifestReader() {
          @Override
          public Attributes read(String jarPath) {
            reads.incrementAndGet();
            Attributes attributes = new Attributes();
            attributes.putValue("Start-Class", "com.example.shop.ShopApplication");
            attributes.putValue("Implementation-Version", "3.1.0");
            return attributes;
          }
        };
    AppPackages.ManifestReader cached = new AppPackages.CachingManifestReader(counting);
    String command = "/apps/shop.jar --server.port=0";

    AppPackages packages = AppPackages.infer(command, cached);
    AppVersion version = AppVersion.resolve(null, command, cached);
    AppVersion again = AppVersion.resolve(null, command, cached);

    assertThat(packages.packages()).containsExactly("com.example");
    assertThat(version.value()).isEqualTo("3.1.0");
    assertThat(again.value()).isEqualTo("3.1.0");
    assertThat(reads.get()).as("one manifest read for both").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-AG-066 AppVersion.of(config): app.version set gives it; this test JVM (a class path"
          + " worker) without app.version gives empty")
  void ofConfig() {
    Map<String, String> set = new HashMap<String, String>();
    set.put(ConfigKeys.APP_VERSION, "9.9.9");
    assertThat(AppVersion.of(AgentConfig.of(set)).value()).isEqualTo("9.9.9");
    String command = System.getProperty("sun.java.command");
    if (command == null || AppPackages.jarPath(command.trim()) == null) {
      assertThat(AppVersion.of(AgentConfig.of(new HashMap<String, String>())).value()).isEmpty();
    }
  }
}
