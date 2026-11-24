/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.log.LogCapture;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code instrumentation.app-packages} inference (10 §15 "자동(main 클래스 패키지 상위 2단계)", U-20, WO-18
 * assumption C-1): the main class from {@code sun.java.command} (a jar's Start-Class or Main-Class,
 * or the class itself), its top two package levels, one deeper under a reserved namespace, never
 * the agent's own or a JDK namespace, nothing for launchers.
 */
@Tag("req:F-A08")
class AppPackagesTest {
  /** A manifest reader over fixed jars: path to (Start-Class, Main-Class). */
  private static AppPackages.ManifestReader jars(final Map<String, String[]> jars) {
    return new AppPackages.ManifestReader() {
      @Override
      public Attributes read(String jarPath) {
        String[] classes = jars.get(jarPath);
        if (classes == null) {
          return null;
        }
        Attributes attributes = new Attributes();
        if (classes[0] != null) {
          attributes.putValue("Start-Class", classes[0]);
        }
        if (classes[1] != null) {
          attributes.putValue("Main-Class", classes[1]);
        }
        return attributes;
      }
    };
  }

  private static final String BOOT_LOADER = "org.springframework.boot.loader.launch.JarLauncher";

  @Test
  @DisplayName(
      "U-20 [wo18-u20] the sample apps and the trivial app: Spring Boot jars by Start-Class"
          + " (io.argos.sample, one level below the reserved io.argos), the trivial app by its"
          + " class (example.trivial)")
  void sampleApps() {
    // Main classes as the sample-apps build files declare them (bootJar(...) / executableJar(...))
    // and the agent-testing trivial app (launched with -cp, the class on the command line).
    Map<String, String[]> jars = new HashMap<String, String[]>();
    jars.put(
        "/apps/shop-boot2.jar",
        new String[] {
          "io.argos.sample.boot2.ShopBoot2Application",
          "org.springframework.boot.loader.JarLauncher"
        });
    jars.put(
        "/apps/shop-boot3.jar",
        new String[] {"io.argos.sample.boot3.ShopBoot3Application", BOOT_LOADER});
    jars.put(
        "/apps/shop-boot4.jar",
        new String[] {"io.argos.sample.boot4.ShopBoot4Application", BOOT_LOADER});
    jars.put(
        "/apps/shop-batch.jar",
        new String[] {"io.argos.sample.batch.ShopBatchApplication", BOOT_LOADER});
    AppPackages.ManifestReader reader = jars(jars);
    String[][] cases = {
      {"shop-boot2", "/apps/shop-boot2.jar --server.port=0", "io.argos.sample", "START_CLASS"},
      {"shop-boot3", "/apps/shop-boot3.jar", "io.argos.sample", "START_CLASS"},
      {
        "shop-boot4",
        "/apps/shop-boot4.jar --spring.main.banner-mode=off",
        "io.argos.sample",
        "START_CLASS"
      },
      {"shop-batch", "/apps/shop-batch.jar", "io.argos.sample", "START_CLASS"},
      {"trivial", "example.trivial.TrivialApp 30000", "example.trivial", "COMMAND"},
    };
    for (String[] c : cases) {
      AppPackages inferred = AppPackages.infer(c[1], reader);
      assertThat(inferred.packages()).as(c[0]).containsExactly(c[2]);
      assertThat(inferred.source().name()).as(c[0]).isEqualTo(c[3]);
      System.out.println(
          "[wo18-u20] app="
              + c[0]
              + " packages="
              + inferred.packages()
              + " source="
              + inferred.source().label());
    }
  }

  @Test
  @DisplayName(
      "U-20 top two levels; Main-Class without Start-Class; module/class; a jar path with spaces;"
          + " a missing or unreadable jar gives none")
  void inference() {
    Map<String, String[]> jars = new HashMap<String, String[]>();
    jars.put("C:\\Program Files\\shop\\app.jar", new String[] {null, "com.example.shop.Main"});
    jars.put("/opt/plain.jar", new String[] {null, "com.acme.orders.cli.Tool"});
    AppPackages.ManifestReader reader = jars(jars);
    assertThat(AppPackages.infer("C:\\Program Files\\shop\\app.jar --x", reader).packages())
        .containsExactly("com.example");
    AppPackages plain = AppPackages.infer("/opt/plain.jar", reader);
    assertThat(plain.packages()).containsExactly("com.acme");
    assertThat(plain.source()).isEqualTo(AppPackages.Source.MAIN_CLASS);
    assertThat(AppPackages.infer("orders/com.acme.orders.Main arg", reader).packages())
        .containsExactly("com.acme");
    assertThat(AppPackages.infer("/missing.jar", reader).source())
        .isEqualTo(AppPackages.Source.NONE);
    assertThat(AppPackages.infer(null, reader).packages()).isEmpty();
    assertThat(AppPackages.infer("  ", reader).packages()).isEmpty();
  }

  @Test
  @DisplayName(
      "C-1 reserved and unknown main classes: io.argos.agent and JDK namespaces never, launchers"
          + " (Tomcat Bootstrap, the Spring Boot loader, build tools) and the default package give"
          + " no packages; a one-level package stays itself")
  void reservedAndUnknown() {
    assertThat(AppPackages.packageOf("io.argos.sample.boot3.App")).isEqualTo("io.argos.sample");
    assertThat(AppPackages.packageOf("io.argos.agent.AgentPremain")).isNull();
    assertThat(AppPackages.packageOf("io.argos.Main")).isNull();
    assertThat(AppPackages.packageOf("java.lang.Thread")).isNull();
    assertThat(AppPackages.packageOf("com.sun.tools.Main")).isNull();
    assertThat(AppPackages.packageOf("jdk.jshell.Main")).isNull();
    assertThat(AppPackages.packageOf("org.apache.catalina.startup.Bootstrap")).isNull();
    assertThat(AppPackages.packageOf(BOOT_LOADER)).isNull();
    assertThat(AppPackages.packageOf("worker.org.gradle.process.internal.worker.GradleWorkerMain"))
        .isNull();
    assertThat(AppPackages.packageOf("Main")).isNull();
    assertThat(AppPackages.packageOf("app.Main")).isEqualTo("app");
    assertThat(AppPackages.packageOf("com.example.Main")).isEqualTo("com.example");
    assertThat(AppPackages.packageOf("-jar")).isNull();
  }

  @Test
  @DisplayName(
      "U-20 the configured value wins; the inferred value fills the unset effective"
          + " instrumentation.app-packages and self-diagnostics; one INFO line without strings")
  void configuredEffectiveAndDiagnostics() {
    Map<String, String> properties = new HashMap<String, String>();
    properties.put("argos.server.address", "collector.example:9100");
    properties.put("argos.app.name", "orders");
    properties.put("argos.instrumentation.app-packages", "[com.acme, org.acme]");
    LoadedConfig configured =
        ConfigLoader.load(
            new ConfigInputs(null, properties, Collections.<String, String>emptyMap(), false));
    AppPackages fixed = configured.config().appPackages();
    assertThat(fixed.packages()).containsExactly("com.acme", "org.acme");
    assertThat(fixed.source()).isEqualTo(AppPackages.Source.CONFIGURED);
    assertThat(fixed.effective(configured.effective())).isSameAs(configured.effective());

    properties.remove("argos.instrumentation.app-packages");
    LoadedConfig unset =
        ConfigLoader.load(
            new ConfigInputs(null, properties, Collections.<String, String>emptyMap(), false));
    assertThat(unset.effective().value("instrumentation.app-packages")).isNull();
    AppPackages inferred =
        new AppPackages(Collections.singletonList("com.example"), AppPackages.Source.START_CLASS);
    EffectiveConfig shown = inferred.effective(unset.effective());
    assertThat(shown.value("instrumentation.app-packages")).isEqualTo("[com.example]");
    assertThat(shown.source("instrumentation.app-packages"))
        .isEqualTo(EffectiveConfig.Source.DEFAULT);
    assertThat(inferred.diagnostics())
        .containsEntry(AppPackages.EXTRA_PACKAGES, "com.example")
        .containsEntry(AppPackages.EXTRA_SOURCE, "start_class");
    try (LogCapture log = LogCapture.start("INFO")) {
      inferred.logOnce();
      inferred.logOnce();
      assertThat(log.linesAt("INFO"))
          .filteredOn(line -> line.contains("application packages"))
          .hasSizeLessThanOrEqualTo(1)
          .allSatisfy(line -> assertThat(line).doesNotContain("com.example"));
    }
  }
}
