/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher options of the compatibility matrix (WO-50, 18 §6): the row's application
 * environment and Tomcat ({@value AppLauncher#MATRIX_APP_ENV}, {@value AppLauncher#MATRIX_TOMCAT}),
 * extra Java agents before or after Argos (18 §6.4 coexistence) and the staged manager app with its
 * user (18 §5.9 WAR redeploy).
 */
class MatrixLauncherTest {
  @TempDir Path dir;

  /** A WAR app with Tomcat 10.1 and 11 homes, as in {@link WarLauncherTest}. */
  SampleApp war(boolean withManager) throws Exception {
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
      Files.writeString(home.resolve("conf").resolve("tomcat-users.xml"), "<tomcat-users/>");
      Files.writeString(
          home.resolve("conf").resolve("logging.properties"),
          "handlers = java.util.logging.ConsoleHandler\n",
          StandardCharsets.UTF_8);
      if (withManager) {
        Path manager = home.resolve("webapps").resolve("manager");
        Files.createDirectories(manager.resolve("WEB-INF"));
        Files.createDirectories(manager.resolve("META-INF"));
        Files.writeString(manager.resolve("WEB-INF").resolve("web.xml"), "<web-app/>");
        Files.writeString(
            manager.resolve("META-INF").resolve("context.xml"),
            "<Context><Valve className=\"org.apache.catalina.valves.RemoteCIDRValve\""
                + " allow=\"0.0.0.0/0\"/></Context>");
      }
    }
    return SampleApp.fromArtifact(artifact);
  }

  SampleApp jar() throws Exception {
    return SampleApp.fromArtifact(Files.writeString(dir.resolve("shop-boot3.jar"), "jar"));
  }

  @Test
  @DisplayName(
      "matrix app environment K=V;K=V: parsed in order, blanks skipped, '=' allowed in values,"
          + " invalid items fail; the test's own withEnv wins over the row's")
  void matrixEnvironment() throws Exception {
    assertThat(AppLauncher.parseEnvironment(null)).isEmpty();
    assertThat(AppLauncher.parseEnvironment("  ")).isEmpty();
    assertThat(AppLauncher.parseEnvironment("SAMPLE_CONTAINER=jetty; ;SAMPLE_DB=postgres"))
        .containsExactly(
            Map.entry("SAMPLE_CONTAINER", "jetty"), Map.entry("SAMPLE_DB", "postgres"));
    assertThat(AppLauncher.parseEnvironment("JAVA_OPTS=-Da=b")).containsEntry("JAVA_OPTS", "-Da=b");
    assertThatThrownBy(() -> AppLauncher.parseEnvironment("SAMPLE_CONTAINER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(AppLauncher.MATRIX_APP_ENV);
    assertThatThrownBy(() -> AppLauncher.parseEnvironment("1BAD=x"))
        .isInstanceOf(IllegalArgumentException.class);

    AppLauncher launcher =
        AppLauncher.of(jar(), new Jdk(21, Path.of("java")))
            .matrixSettings("SAMPLE_CONTAINER=jetty;SAMPLE_POOL=dbcp2", null)
            .withEnv("SAMPLE_POOL", "hikari")
            .withEnv("SAMPLE_EXT_URL", "http://127.0.0.1:1");
    assertThat(launcher.environment())
        .containsExactly(
            Map.entry("SAMPLE_CONTAINER", "jetty"),
            Map.entry("SAMPLE_POOL", "hikari"),
            Map.entry("SAMPLE_EXT_URL", "http://127.0.0.1:1"));
    // Without matrix properties nothing is added (the integration suites are unchanged).
    assertThat(
            AppLauncher.of(jar(), new Jdk(21, Path.of("java")))
                .matrixSettings(null, null)
                .withEnv("A", "1")
                .environment())
        .containsExactly(Map.entry("A", "1"));
  }

  @Test
  @DisplayName(
      "matrix Tomcat key: replaces tomcat(key) and the default Tomcat for a WAR app that has it,"
          + " ignored for one that has not")
  void matrixTomcat() throws Exception {
    SampleApp app = war(false);
    Jdk jdk21 = new Jdk(21, Path.of("java"));
    assertThat(AppLauncher.of(app, jdk21).matrixSettings(null, null).tomcatKey())
        .isEqualTo("tomcat11");
    assertThat(AppLauncher.of(app, jdk21).matrixSettings(null, "tomcat10").tomcatKey())
        .isEqualTo("tomcat10");
    assertThat(
            AppLauncher.of(app, new Jdk(17, Path.of("java")))
                .matrixSettings(null, "tomcat11")
                .tomcat("tomcat10")
                .tomcatKey())
        .as("the matrix row decides the container of every suite it runs")
        .isEqualTo("tomcat11");
    assertThat(AppLauncher.of(app, jdk21).matrixSettings(null, "tomcat9").tomcatKey())
        .as("a key the app does not have is ignored")
        .isEqualTo("tomcat11");
    assertThat(
            AppLauncher.of(app, jdk21).matrixSettings(null, "tomcat10").command(dir.resolve("run")))
        .contains("-Dcatalina.home=" + app.tomcatHome("tomcat10"));
  }

  @Test
  @DisplayName(
      "18 §6.4 extra agents: BEFORE ones precede -javaagent:argos-agent.jar, AFTER ones follow the"
          + " Argos settings, in call order, with their options; without Argos only the extra"
          + " agent")
  void extraAgents() throws Exception {
    Path work = dir.resolve("run");
    Path jacoco = dir.resolve("jacocoagent.jar");
    Path otel = dir.resolve("opentelemetry-javaagent.jar");
    String argos = "-javaagent:" + work.resolve("agent").resolve("argos-agent.jar");
    List<String> before =
        AppLauncher.of(jar(), new Jdk(21, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"))
            .withExtraAgent(otel, AppLauncher.Order.BEFORE, null)
            .withExtraAgent(jacoco, AppLauncher.Order.BEFORE, "destfile=" + work.resolve("j.exec"))
            .command(work);
    String otelArgument = "-javaagent:" + otel.toAbsolutePath();
    String jacocoArgument =
        "-javaagent:" + jacoco.toAbsolutePath() + "=destfile=" + work.resolve("j.exec");
    assertThat(before).containsSubsequence(otelArgument, jacocoArgument, argos, "-jar");
    assertThat(before.indexOf("-Djava.io.tmpdir=" + work.resolve("tmp")))
        .isLessThan(before.indexOf(otelArgument));

    List<String> after =
        AppLauncher.of(jar(), new Jdk(21, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"))
            .withExtraAgent(otel, AppLauncher.Order.AFTER, " ")
            .command(work);
    assertThat(after).containsSubsequence(argos, "-Dargos.app.name=shop-boot3", otelArgument);
    int lastArgosSetting = 0;
    for (int i = 0; i < after.size(); i++) {
      if (after.get(i).startsWith("-Dargos.")) {
        lastArgosSetting = i;
      }
    }
    assertThat(after.indexOf(otelArgument)).isGreaterThan(lastArgosSetting);

    List<String> alone =
        AppLauncher.of(jar(), new Jdk(21, Path.of("java")))
            .withoutAgent()
            .withExtraAgent(otel, AppLauncher.Order.AFTER, null)
            .command(work);
    assertThat(alone).contains(otelArgument).noneMatch(a -> a.contains("argos-agent.jar"));
    assertThatThrownBy(() -> new AppLauncher.ExtraAgent(null, AppLauncher.Order.BEFORE, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "18 §5.9 manager app: copied from the distribution with a 127.0.0.1-only context, a"
          + " manager-script user with a random password (owner-only file on POSIX, masked in"
          + " toString), the UserDatabase realm in server.xml")
  void managerStaging() throws Exception {
    SampleApp app = war(true);
    Path base = TomcatStaging.base(dir.resolve("run"));
    TomcatStaging.ManagerAccess access = TomcatStaging.ManagerAccess.random();
    TomcatStaging.stage(
        app,
        app.tomcatHome("tomcat11"),
        base,
        18080,
        TomcatStaging.Shutdown.random(18005),
        Map.of(),
        access);
    assertThat(access.user()).isEqualTo(TomcatStaging.ManagerAccess.USER);
    assertThat(access.password()).matches("[0-9a-f]{32}");
    assertThat(TomcatStaging.ManagerAccess.random().password()).isNotEqualTo(access.password());
    assertThat(access.toString()).doesNotContain(access.password()).contains("****");

    Path users = base.resolve("conf").resolve("tomcat-users.xml");
    assertThat(Files.readString(users))
        .contains("<role rolename=\"manager-script\" />")
        .contains(
            "<user username=\"argos-matrix\" password=\""
                + access.password()
                + "\" roles=\"manager-script\" />")
        .doesNotContain("manager-gui");
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(users)))
          .isEqualTo("rw-------");
    }
    Path manager = base.resolve("webapps").resolve("manager");
    assertThat(manager.resolve("WEB-INF").resolve("web.xml")).hasContent("<web-app/>");
    assertThat(Files.readString(manager.resolve("META-INF").resolve("context.xml")))
        .contains("privileged=\"true\"")
        .contains(
            "<Valve className=\"org.apache.catalina.valves.RemoteAddrValve\""
                + " allow=\"127\\.0\\.0\\.1\" />")
        .doesNotContain("0.0.0.0/0");
    String server = Files.readString(base.resolve("conf").resolve("server.xml"));
    assertThat(server)
        .contains("factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"")
        .contains("pathname=\"conf/tomcat-users.xml\"")
        .contains("<Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"")
        .contains("<Connector port=\"18080\" address=\"127.0.0.1\"")
        .doesNotContain(access.password());
    assertThat(base.resolve("webapps").resolve("shop-a.war")).hasContent("war");

    // Without a manager: no realm, the distribution's users file as it is, no manager app.
    Path plain = TomcatStaging.base(dir.resolve("plain"));
    TomcatStaging.stage(app, app.tomcatHome("tomcat11"), plain, 18081);
    assertThat(Files.readString(plain.resolve("conf").resolve("server.xml")))
        .doesNotContain("Realm")
        .doesNotContain("GlobalNamingResources")
        .contains("      <Host name=\"localhost\"")
        .contains("  <Service name=\"Catalina\">");
    assertThat(plain.resolve("conf").resolve("tomcat-users.xml")).hasContent("<tomcat-users/>");
    assertThat(plain.resolve("webapps").resolve("manager")).doesNotExist();
  }

  @Test
  @DisplayName(
      "manager staging refuses a distribution without the manager app, a context named manager"
          + " and a jar app")
  void managerStagingRefusals() throws Exception {
    SampleApp app = war(false);
    TomcatStaging.ManagerAccess access = TomcatStaging.ManagerAccess.random();
    assertThatThrownBy(
            () ->
                TomcatStaging.stage(
                    app,
                    app.tomcatHome("tomcat11"),
                    TomcatStaging.base(dir.resolve("r1")),
                    18080,
                    null,
                    Map.of(),
                    access))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no webapps/manager");
    SampleApp withManager = war(true);
    Path fixture = Files.writeString(dir.resolve("fixture.war"), "fixture");
    assertThatThrownBy(
            () ->
                TomcatStaging.stage(
                    withManager,
                    withManager.tomcatHome("tomcat11"),
                    TomcatStaging.base(dir.resolve("r2")),
                    18080,
                    null,
                    Map.of("/manager", fixture),
                    access))
        .hasMessageContaining("already in use");
    assertThatThrownBy(
            () -> AppLauncher.of(jar(), new Jdk(21, Path.of("java"))).tomcatManager(true))
        .hasMessageContaining("not a WAR app");
    assertThatThrownBy(() -> new TomcatStaging.ManagerAccess("x", "short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TomcatStaging.ManagerAccess("a\"b", "0123456789abcdef0"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
