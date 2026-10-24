/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.Check;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Smoke runner logic without starting an app: JSON, checks, options, Tomcat config, counts. */
class SmokeRunnerUnitTest {

  @Test
  @DisplayName("MiniJson reads app answers and writes reports")
  void json() {
    Object parsed =
        MiniJson.parse("{\"id\":1,\"ok\":true,\"x\":null,\"a\":[1.5,\"s\\u003c\"],\"o\":{}}");
    assertThat(parsed).isInstanceOf(Map.class);
    Map<?, ?> map = (Map<?, ?>) parsed;
    assertThat(map.get("id")).isEqualTo(1L);
    assertThat(MiniJson.scalar(map.get("ok"))).isEqualTo("true");
    assertThat(map.get("a")).isEqualTo(List.of(1.5, "s<"));
    assertThat(MiniJson.parseObjectOrEmpty("not json")).isEmpty();
    assertThat(MiniJson.write(Map.of("k", List.of("v\"")))).contains("\"v\\\"\"");
    assertThatThrownBy(() -> MiniJson.parse("{\"a\":1} x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Checks: status, JSON fields, elapsed time, headers, variant body expectations")
  void checks() {
    CatalogEntry entry =
        CatalogEntry.get("orders.page", "/api/orders?page=0")
            .check(Check.jsonEquals("rows", 100))
            .check(Check.jsonAtLeast("waitedMs", 50))
            .check(Check.minElapsedMs(10))
            .check(Check.headerPresent("X-Test"))
            .build();
    EntryResult ok = result(entry, 200, "{\"rows\":100,\"waitedMs\":60}", 12);
    ok.headers = Map.of("x-test", List.of("1"));
    SmokeClient.check(entry, ok, Map.of());
    assertThat(ok.failures).isEmpty();
    // The values the JSON checks read are reported (smoke.json "observed").
    assertThat(ok.observed).containsEntry("rows", 100L).containsEntry("waitedMs", 60L);
    assertThat(ok.toMap()).containsKey("observed");

    EntryResult bad = result(entry, 500, "{\"rows\":99,\"waitedMs\":10}", 5);
    SmokeClient.check(entry, bad, Map.of("orders.page", "needle"));
    assertThat(bad.failures)
        .hasSize(6)
        .anyMatch(f -> f.startsWith("status 500"))
        .anyMatch(f -> f.contains("rows = 100 (actual 99)"))
        .anyMatch(f -> f.contains("needle"));
  }

  private static EntryResult result(CatalogEntry entry, int status, String body, long ms) {
    EntryResult result = new EntryResult(entry, "");
    result.status = status;
    result.body = body;
    result.elapsedMs = ms;
    return result;
  }

  @Test
  @DisplayName("Options: repeatable key=value options and required ones")
  void options() {
    SmokeOptions o =
        SmokeOptions.parse(
            new String[] {
              "--app",
              "shop-war-jakarta",
              "--kind",
              "war",
              "--artifact",
              "x.war",
              "--tomcat-home",
              "t",
              "--env",
              "SAMPLE_VT=true",
              "--env",
              "A=b=c",
              "--expect-runtime",
              "virtualThreads=true",
              "--seed-env",
              "--context",
              "shop-a",
              "--context",
              "shop-b"
            });
    assertThat(o.env).containsEntry("SAMPLE_VT", "true").containsEntry("A", "b=c");
    assertThat(o.expectRuntime).containsEntry("virtualThreads", "true");
    assertThat(o.seedEnvironment).isTrue();
    assertThat(o.contexts).containsExactly("shop-a", "shop-b");
    assertThatThrownBy(() -> SmokeOptions.parse(new String[] {"--app", "x", "--kind", "war"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Runtime: routingDataSource=true with routingLookups=0 fails the run")
  void routingBypassFails() {
    SmokeOptions options =
        SmokeOptions.parse(
            new String[] {
              "--app",
              "shop-boot3",
              "--kind",
              "url",
              "--url",
              "http://127.0.0.1:1",
              "--catalog",
              "shop"
            });
    CatalogEntry entry = CatalogEntry.get("orders.one", "/api/orders/1").build();
    List<EntryResult> results = List.of(result(entry, 200, "{}", 1));
    Map<String, String> expected = Map.of("routingDataSource", "true");

    Map<String, Object> bypassed =
        MiniJson.parseObjectOrEmpty("{\"routingDataSource\":true,\"routingLookups\":0}");
    List<String> problems = SmokeRun.runtimeProblems(bypassed, expected, "shop-a");
    assertThat(problems)
        .singleElement()
        .asString()
        .startsWith("shop-a: ")
        .contains("routingLookups = 0")
        .contains("requests bypassed the routing DataSource");
    assertThat(report(options, results, problems).passed()).isFalse();

    Map<String, Object> missing = MiniJson.parseObjectOrEmpty("{\"routingDataSource\":true}");
    assertThat(SmokeRun.runtimeProblems(missing, Map.of(), ""))
        .singleElement()
        .asString()
        .contains("requests bypassed the routing DataSource");

    Map<String, Object> routed =
        MiniJson.parseObjectOrEmpty("{\"routingDataSource\":true,\"routingLookups\":42}");
    List<String> none = SmokeRun.runtimeProblems(routed, expected, "");
    assertThat(none).isEmpty();
    assertThat(report(options, results, none).passed()).isTrue();

    Map<String, Object> plain =
        MiniJson.parseObjectOrEmpty("{\"routingDataSource\":false,\"routingLookups\":0}");
    assertThat(SmokeRun.runtimeProblems(plain, Map.of(), "")).isEmpty();
    assertThat(SmokeRun.runtimeProblems(plain, expected, ""))
        .singleElement()
        .asString()
        .isEqualTo("GET /api/runtime routingDataSource = false, expected true");
  }

  @Test
  @DisplayName(
      "Declared clients: a different set in /api/runtime fails the run, the same set passes")
  void declaredClients() {
    SmokeOptions options =
        SmokeOptions.parse(
            new String[] {
              "--app",
              "shop-boot3",
              "--kind",
              "url",
              "--url",
              "http://127.0.0.1:1",
              "--catalog",
              "shop",
              "--expect-clients",
              "hurl,resttemplate,hc5,okhttp,jdk11,webclient"
            });
    assertThat(options.expectClients)
        .containsExactlyInAnyOrder("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient");
    CatalogEntry entry = CatalogEntry.get("orders.one", "/api/orders/1").build();
    List<EntryResult> results = List.of(result(entry, 200, "{}", 1));

    // OkHttp 4 and WebClient gone from the app: their pay entries would only be SKIPPED.
    Map<String, Object> twoMissing =
        MiniJson.parseObjectOrEmpty(
            "{\"clients\":[\"hurl\",\"hc5\",\"jdk11\",\"resttemplate\"],"
                + "\"capabilities\":[\"client:hurl\",\"client:hc5\",\"client:jdk11\","
                + "\"client:resttemplate\"]}");
    List<String> problems = SmokeRun.clientProblems(twoMissing, options.expectClients, "");
    assertThat(problems)
        .singleElement()
        .asString()
        .startsWith("GET /api/runtime clients = ")
        .contains("missing [okhttp, webclient]")
        .contains("not declared []");
    assertThat(report(options, results, problems).passed()).isFalse();

    // A client the app has but the build does not declare fails as well.
    Map<String, Object> extra =
        MiniJson.parseObjectOrEmpty(
            "{\"clients\":[\"hurl\",\"hc4\",\"hc5\",\"okhttp\",\"jdk11\",\"webclient\","
                + "\"resttemplate\"]}");
    assertThat(SmokeRun.clientProblems(extra, options.expectClients, "shop-a"))
        .singleElement()
        .asString()
        .startsWith("shop-a: ")
        .contains("not declared [hc4]");

    // No answer from /api/runtime (empty map): nothing reported, so it fails.
    assertThat(SmokeRun.clientProblems(Map.of(), options.expectClients, "")).hasSize(1);

    // Same set in another order: no problem, the run passes.
    Map<String, Object> same =
        MiniJson.parseObjectOrEmpty(
            "{\"clients\":[\"webclient\",\"jdk11\",\"okhttp\",\"hc5\",\"resttemplate\",\"hurl\"]}");
    List<String> none = SmokeRun.clientProblems(same, options.expectClients, "");
    assertThat(none).isEmpty();
    assertThat(report(options, results, none).passed()).isTrue();

    // Without --expect-clients (seed traffic, --kind url) nothing is declared or checked.
    assertThat(
            SmokeOptions.parse(
                    new String[] {"--app", "x", "--kind", "url", "--url", "http://127.0.0.1:1"})
                .expectClients)
        .isNull();
    assertThatThrownBy(
            () ->
                SmokeOptions.parse(
                    new String[] {
                      "--app", "x", "--kind", "url", "--url", "u", "--expect-clients", " , "
                    }))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Negative body check: a 404 from the static resource handler fails not-found")
  void bodyNotContains() {
    CatalogEntry entry =
        CatalogEntry.get("not-found", "/api/not-found-x")
            .status(404)
            .check(Check.bodyNotContains("No static resource"))
            .build();
    EntryResult staticHandler =
        result(entry, 404, "{\"message\":\"No static resource api/not-found-x.\"}", 3);
    SmokeClient.check(entry, staticHandler, Map.of());
    assertThat(staticHandler.failures)
        .singleElement()
        .asString()
        .isEqualTo("body does not contain 'No static resource'");

    EntryResult noHandler =
        result(entry, 404, "{\"message\":\"No endpoint GET /api/not-found-x.\"}", 3);
    SmokeClient.check(entry, noHandler, Map.of());
    assertThat(noHandler.failures).isEmpty();
  }

  private static SmokeReport report(
      SmokeOptions options, List<EntryResult> results, List<String> problems) {
    return new SmokeReport(options, Map.of(), List.of(), Map.of(), results, problems, List.of());
  }

  @Test
  @DisplayName("Tomcat base: connector on 127.0.0.1 with the chosen port, no shutdown port")
  void serverXml() {
    String xml = TomcatBase.serverXml(18123);
    assertThat(xml)
        .contains("port=\"18123\" address=\"127.0.0.1\"")
        .contains("<Server port=\"-1\"");
  }

  @Test
  @DisplayName("Class count of a nested jar ignores module-info and package-info")
  void countClasses() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String name :
          List.of(
              "a/A.class", "a/B.class", "module-info.class", "a/package-info.class", "a/x.txt")) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(new byte[] {1});
        zip.closeEntry();
      }
    }
    assertThat(ArtifactStats.countClasses(new ByteArrayInputStream(bytes.toByteArray())))
        .isEqualTo(2);
  }
}
