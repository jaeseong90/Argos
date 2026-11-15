/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WAR mode of the launcher (staged Tomcat, command line) and the pinned container images. */
class WarLauncherTest {
  @TempDir Path dir;

  static final String LOGGING =
      "handlers = 1catalina.org.apache.juli.AsyncFileHandler,"
          + " 2localhost.org.apache.juli.AsyncFileHandler, java.util.logging.ConsoleHandler\n"
          + "\n"
          + ".handlers = 1catalina.org.apache.juli.AsyncFileHandler,"
          + " java.util.logging.ConsoleHandler\n"
          + "java.util.logging.ConsoleHandler.level = FINE\n";

  /** A WAR app with a descriptor and a Tomcat home that has the files staging reads. */
  SampleApp war() throws Exception {
    Path build = dir.resolve("shop-war-jakarta").resolve("build");
    Path artifact =
        Files.createDirectories(build.resolve("sample-app")).resolve("shop-war-jakarta.war");
    Files.writeString(artifact, "war", StandardCharsets.UTF_8);
    Files.writeString(
        artifact.resolveSibling("shop-war-jakarta.properties"),
        """
        app=shop-war-jakarta
        kind=war
        runJdk=17
        catalog=shop
        contexts=shop-a,shop-b
        tomcat=tomcat10:10.1.61,tomcat11:11.0.27
        """,
        StandardCharsets.ISO_8859_1);
    for (String key : List.of("tomcat10", "tomcat11")) {
      Path home = build.resolve("tomcat").resolve(key);
      Files.createDirectories(home.resolve("bin"));
      Files.createDirectories(home.resolve("conf"));
      Files.writeString(home.resolve("bin").resolve("bootstrap.jar"), "jar");
      Files.writeString(home.resolve("bin").resolve("tomcat-juli.jar"), "jar");
      Files.writeString(home.resolve("conf").resolve("server.xml"), "<Server port=\"8005\"/>");
      Files.writeString(home.resolve("conf").resolve("web.xml"), "<web-app/>");
      Files.writeString(
          home.resolve("conf").resolve("logging.properties"), LOGGING, StandardCharsets.UTF_8);
    }
    return SampleApp.fromArtifact(artifact);
  }

  @Test
  @DisplayName(
      "WAR descriptor: contexts, Tomcat distributions in order, 18 §4.1 default Tomcat per JDK,"
          + " CATALINA_HOME next to the build, context paths")
  void descriptor() throws Exception {
    SampleApp app = war();
    assertThat(app.isWar()).isTrue();
    assertThat(app.contexts()).containsExactly("shop-a", "shop-b");
    assertThat(app.contextPaths()).containsExactly("/shop-a", "/shop-b");
    assertThat(app.tomcats())
        .containsExactly(Map.entry("tomcat10", "10.1.61"), Map.entry("tomcat11", "11.0.27"));
    assertThat(app.defaultTomcat(17)).isEqualTo("tomcat10");
    assertThat(app.defaultTomcat(21)).isEqualTo("tomcat11");
    assertThat(app.defaultTomcat(25)).isEqualTo("tomcat11");
    assertThat(app.tomcatHome("tomcat11"))
        .isEqualTo(
            dir.resolve("shop-war-jakarta")
                .resolve("build")
                .resolve("tomcat")
                .resolve("tomcat11")
                .toAbsolutePath());
    assertThatThrownBy(() -> app.tomcatHome("tomcat9")).hasMessageContaining("no Tomcat");
    Files.delete(app.tomcatHome("tomcat10").resolve("bin").resolve("bootstrap.jar"));
    assertThatThrownBy(() -> app.tomcatHome("tomcat10"))
        .hasMessageContaining("is not staged")
        .hasMessageContaining("sampleStageTomcatTomcat10");

    Path jar = Files.createFile(dir.resolve("shop-boot3.jar"));
    SampleApp boot = SampleApp.fromArtifact(jar);
    assertThat(boot.contextPaths()).containsExactly("");
    assertThat(boot.tomcats()).isEmpty();
    assertThatThrownBy(() -> boot.defaultTomcat(21)).hasMessageContaining("no standalone Tomcat");
  }

  @Test
  @DisplayName(
      "WAR command: presets, temp dir and agent as for jar apps, then catalina.home/base, JULI,"
          + " --add-opens on 9+, Bootstrap start; Tomcat chosen by tomcat(key)")
  void command() throws Exception {
    SampleApp app = war();
    Path work = dir.resolve("run");
    Path home = app.tomcatHome("tomcat11");
    List<String> command =
        AppLauncher.of(app, new Jdk(21, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"))
            .command(work);
    assertThat(command)
        .startsWith("java", "-XX:-UsePerfData")
        .containsSubsequence(
            "-XX:+BytecodeVerificationLocal",
            "-Djava.io.tmpdir=" + work.resolve("tmp"),
            "-javaagent:" + work.resolve("agent").resolve("argos-agent.jar"),
            "-Dargos.test.exporter=file:" + work.resolve("out"),
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-Dcatalina.home=" + home,
            "-Dcatalina.base=" + work.resolve("catalina-base").toAbsolutePath(),
            "-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager",
            "-classpath",
            TomcatStaging.BOOTSTRAP_CLASS,
            "start")
        .endsWith(TomcatStaging.BOOTSTRAP_CLASS, "start")
        .noneMatch(argument -> argument.equals("-jar"));

    AppLauncher onTomcat10 = AppLauncher.of(app, new Jdk(21, Path.of("java"))).tomcat("tomcat10");
    assertThat(onTomcat10.tomcatKey()).isEqualTo("tomcat10");
    assertThat(onTomcat10.command(work)).contains("-Dcatalina.home=" + app.tomcatHome("tomcat10"));
    assertThat(TomcatStaging.arguments(home, work, 8))
        .noneMatch(argument -> argument.startsWith("--add-opens"));
  }

  @Test
  @DisplayName(
      "CATALINA_BASE: conf copied, server.xml on 127.0.0.1:<port> without shutdown port, no JULI"
          + " console handler, one WAR per context")
  void staging() throws Exception {
    SampleApp app = war();
    Path base = TomcatStaging.base(dir.resolve("run"));
    TomcatStaging.stage(app, app.tomcatHome("tomcat11"), base, 18080);
    String server = Files.readString(base.resolve("conf").resolve("server.xml"));
    assertThat(server)
        .contains("<Server port=\"-1\"")
        .contains("<Connector port=\"18080\" address=\"127.0.0.1\"")
        .doesNotContain("8005");
    assertThat(base.resolve("conf").resolve("web.xml")).hasContent("<web-app/>");
    assertThat(Files.readString(base.resolve("conf").resolve("logging.properties")))
        .isEqualTo(
            "handlers = 1catalina.org.apache.juli.AsyncFileHandler,"
                + " 2localhost.org.apache.juli.AsyncFileHandler\n"
                + "\n"
                + ".handlers = 1catalina.org.apache.juli.AsyncFileHandler\n"
                + "java.util.logging.ConsoleHandler.level = FINE\n");
    assertThat(base.resolve("webapps").resolve("shop-a.war")).hasContent("war");
    assertThat(base.resolve("webapps").resolve("shop-b.war")).hasContent("war");
    for (String directory : List.of("logs", "temp", "work")) {
      assertThat(base.resolve(directory)).isDirectory();
    }
  }

  @Test
  @DisplayName(
      "CATALINA_BASE of a launcher run: loopback shutdown port with a random command, extra web"
          + " applications next to the contexts, English locale on the command line")
  void stagingWithShutdownAndExtraWebapps() throws Exception {
    SampleApp app = war();
    Path base = TomcatStaging.base(dir.resolve("run2"));
    Path fixture = Files.writeString(dir.resolve("fixture.war"), "fixture");
    TomcatStaging.Shutdown shutdown = TomcatStaging.Shutdown.random(18005);
    TomcatStaging.stage(
        app,
        app.tomcatHome("tomcat11"),
        base,
        18080,
        shutdown,
        Map.of("/servlet-fixture", fixture));
    String server = Files.readString(base.resolve("conf").resolve("server.xml"));
    assertThat(server)
        .contains(
            "<Server port=\"18005\" address=\"127.0.0.1\" shutdown=\"" + shutdown.command() + "\">")
        .contains("<Connector port=\"18080\" address=\"127.0.0.1\"");
    assertThat(shutdown.command()).matches("SHUTDOWN-[0-9a-f]{32}");
    assertThat(TomcatStaging.Shutdown.random(18005).command()).isNotEqualTo(shutdown.command());
    assertThat(shutdown.toString()).doesNotContain(shutdown.command());
    assertThat(base.resolve("webapps").resolve("servlet-fixture.war")).hasContent("fixture");
    assertThat(base.resolve("webapps").resolve("shop-a.war")).hasContent("war");
    assertThatThrownBy(
            () ->
                TomcatStaging.stage(
                    app,
                    app.tomcatHome("tomcat11"),
                    TomcatStaging.base(dir.resolve("run3")),
                    18080,
                    null,
                    Map.of("shop-a", fixture)))
        .hasMessageContaining("already a context");
    List<String> arguments = TomcatStaging.arguments(app.tomcatHome("tomcat11"), base, 21);
    assertThat(arguments).containsSubsequence("-Duser.language=en", "-Duser.country=US");
    assertThat(arguments.indexOf("-Duser.country=US"))
        .isLessThan(arguments.indexOf("-Dcatalina.home=" + app.tomcatHome("tomcat11")));
    assertThatThrownBy(
            () ->
                AppLauncher.of(
                        SampleApp.fromArtifact(Files.createFile(dir.resolve("boot.jar"))),
                        new Jdk(21, Path.of("java")))
                    .webapp("x", fixture))
        .hasMessageContaining("not a WAR app");
  }

  @Test
  @DisplayName(
      "container images: JDK 8, 11, 17, 21, 25 pinned by index digest; other JDKs fail; an"
          + " override must carry a digest")
  void containerImages() {
    for (int jdk : new int[] {8, 11, 17, 21, 25}) {
      assertThat(ContainerApp.image(jdk))
          .startsWith("eclipse-temurin:" + jdk + "-jdk@sha256:")
          .matches(".*@sha256:[0-9a-f]{64}");
    }
    assertThatThrownBy(() -> ContainerApp.image(23))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no pinned container image for JDK 23");
    String property = ContainerApp.IMAGE_PROPERTY + "23";
    try {
      System.setProperty(property, "eclipse-temurin:23-jdk@sha256:" + "a".repeat(64));
      assertThat(ContainerApp.image(23)).endsWith("a".repeat(64));
      System.setProperty(property, "eclipse-temurin:23-jdk");
      assertThatThrownBy(() -> ContainerApp.image(23)).hasMessageContaining("not pinned");
    } finally {
      System.clearProperty(property);
    }
  }
}
