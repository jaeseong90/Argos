/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.about;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.ProtocolVersion;
import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.config.ServerBuild;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentTokens;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-API-013a on a real server with PostgreSQL and ClickHouse (WO-39; 14 §4.14, O-R12, O-L04,
 * ADR-0010 C-153): {@code GET /about} gives the build's version and commit (the same as the {@code
 * X-Argos-Server-Version} header), the protocol version, the agent versions of the instances that
 * are not archived and the uptime; {@code GET /about/licenses} gives the three parts from the lists
 * the build generates (WO-44): server and agent equal to their argos-deps.txt, ui only in a build
 * with the UI bundle ({@code not_generated} otherwise). Every role may read both, nobody without a
 * session.
 */
@Tag("integration")
@Tag("req:O-R12")
@Tag("req:O-L04")
class AboutApiIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of());
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static void insertInstance(int appId, String name, String version, boolean archived)
      throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.instances (app_id, name, agent_version, last_seen_at,"
                    + " archived_at) VALUES (?, ?, ?, now(), CASE WHEN ? THEN now() END)")) {
      st.setInt(1, appId);
      st.setString(2, name);
      st.setString(3, version);
      st.setBoolean(4, archived);
      assertThat(st.executeUpdate()).isEqualTo(1);
    }
  }

  private static long countOf(JsonNode versions, String version) {
    for (JsonNode v : versions) {
      if (v.path("version").isString() && version.equals(v.path("version").asString())) {
        return v.path("count").asLong();
      }
    }
    return 0;
  }

  @Test
  @DisplayName(
      "T-API-013a GET /about: version and commit of the build (= X-Argos-Server-Version),"
          + " protocol_version, agent_versions of the instances that are not archived with counts,"
          + " uptime_s, a relative support_policy_url; viewer, operator and admin 200, no session"
          + " 401")
  void about() throws Exception {
    int appId;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, "about-it", "prod");
    }
    insertInstance(appId, "about-a:8080", "9.8.7-it", false);
    insertInstance(appId, "about-b:8080", "9.8.7-it", false);
    insertInstance(appId, "about-c:8080", "9.8.6-it", false);
    insertInstance(appId, "about-old:8080", "9.8.7-it", true);
    insertInstance(appId, "about-gone:8080", "9.8.5-it", true);

    ServerBuild build = server.bean(ServerBuild.class);
    for (Role role : new Role[] {Role.VIEWER, Role.OPERATOR, Role.ADMIN}) {
      ApiResponse r = login(role).get("/api/v1/about");
      assertThat(r.status()).as(role + " " + r).isEqualTo(200);
      JsonNode data = r.json().path("data");
      assertThat(data.path("version").asString()).isEqualTo(build.version());
      assertThat(r.header("X-Argos-Server-Version")).contains(build.version());
      assertThat(data.path("commit").asString()).isEqualTo(build.commit());
      assertThat(data.path("protocol_version").asInt()).isEqualTo(ProtocolVersion.CURRENT);
      JsonNode versions = data.path("agent_versions");
      assertThat(countOf(versions, "9.8.7-it")).as("archived instance excluded").isEqualTo(2);
      assertThat(countOf(versions, "9.8.6-it")).isEqualTo(1);
      assertThat(countOf(versions, "9.8.5-it")).as("archived only: not listed").isZero();
      assertThat(versions.get(0).path("count").asLong())
          .as("most common first")
          .isGreaterThanOrEqualTo(versions.get(versions.size() - 1).path("count").asLong());
      assertThat(data.path("uptime_s").asLong()).isGreaterThanOrEqualTo(0);
      assertThat(data.path("support_policy_url").asString()).isNotBlank().doesNotContain("://");
    }
    ApiResponse anonymous = server.client().get("/api/v1/about");
    assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(401);
  }

  /** {@code group:artifact} to {@code version:license} of an argos-deps.txt file (SEC-77). */
  private static Map<String, String> depsOf(String property) throws Exception {
    String path = System.getProperty(property);
    assertThat(path).as("system property " + property + " (server/build.gradle.kts)").isNotBlank();
    Map<String, String> out = new TreeMap<>();
    for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
      String l = line.strip();
      if (l.isEmpty() || l.startsWith("#")) {
        continue;
      }
      String[] parts = l.split(":", 4);
      assertThat(parts).as(l).hasSize(4);
      out.put(parts[0] + ":" + parts[1], parts[2] + ":" + parts[3]);
    }
    assertThat(out).as(path).isNotEmpty();
    return out;
  }

  private static Map<String, String> componentsOf(JsonNode section) {
    Map<String, String> out = new TreeMap<>();
    for (JsonNode c : section.path("components")) {
      out.put(
          c.path("name").asString(),
          c.path("version").asString() + ":" + c.path("license").asString());
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-API-013a GET /about/licenses (WO-44, C-153): the server and agent sections list exactly the"
          + " modules of their argos-deps.txt with the same versions and licenses (e.g."
          + " io.grpc:grpc-netty, org.springframework:spring-core, net.bytebuddy:byte-buddy); the ui"
          + " section is available exactly in a build with the UI bundle, otherwise not_generated;"
          + " viewer 200, no session 401")
  void licenses() throws Exception {
    ApiResponse r = login(Role.VIEWER).get("/api/v1/about/licenses");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().path("data");
    ClassLoader loader = AboutApiIT.class.getClassLoader();
    for (String part : new String[] {"server", "ui", "agent"}) {
      assertThat(data.path(part).isObject()).as(part).isTrue();
    }

    Map<String, String> serverDeps = depsOf("argos.test.serverDepsFile");
    JsonNode serverSection = data.path("server");
    assertThat(serverSection.path("available").asBoolean()).as(serverSection.toString()).isTrue();
    assertThat(serverSection.path("truncated").asBoolean()).isFalse();
    Map<String, String> serverListed = componentsOf(serverSection);
    assertThat(serverListed).as("server list = server argos-deps.txt").isEqualTo(serverDeps);
    assertThat(serverListed).containsKeys("io.grpc:grpc-netty", "org.springframework:spring-core");
    assertThat(serverListed.get("org.postgresql:postgresql")).endsWith(":BSD-2-Clause");

    Map<String, String> agentDeps = depsOf("argos.test.agentDepsFile");
    JsonNode agentSection = data.path("agent");
    assertThat(agentSection.path("available").asBoolean()).as(agentSection.toString()).isTrue();
    Map<String, String> agentListed = componentsOf(agentSection);
    assertThat(agentListed).as("agent list = agent jar argos-deps.txt").isEqualTo(agentDeps);
    assertThat(agentListed).containsKeys("io.grpc:grpc-netty", "net.bytebuddy:byte-buddy");

    boolean uiBundled = Boolean.parseBoolean(System.getProperty("argos.test.uiBundled", "false"));
    JsonNode ui = data.path("ui");
    assertThat(loader.getResource(LicenseCatalog.DIRECTORY + "ui.json") != null)
        .as("ui.json on the class path exactly when the build bundles the UI")
        .isEqualTo(uiBundled);
    assertThat(ui.path("available").asBoolean()).as(ui.toString()).isEqualTo(uiBundled);
    if (uiBundled) {
      assertThat(ui.path("components").size()).isPositive();
      assertThat(componentsOf(ui)).containsKey("react");
    } else {
      assertThat(ui.path("reason").asString()).isEqualTo("not_generated");
      assertThat(ui.path("components").isArray()).isTrue();
      assertThat(ui.path("components").size()).isZero();
    }
    ApiResponse anonymous = server.client().get("/api/v1/about/licenses");
    assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(401);
  }
}
