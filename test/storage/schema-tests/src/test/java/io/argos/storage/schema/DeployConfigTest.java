/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Static checks of deploy/clickhouse (no Docker): no credential literal, variables consistent. */
class DeployConfigTest {

  private static final Path DIR = RepositoryLayout.clickhouseDir();
  private static final Pattern FROM_ENV = Pattern.compile("from_env=\"([A-Z0-9_]+)\"");

  @Test
  @Tag("req:SEC-74")
  @DisplayName(
      "T-SEC-74 no password literal in deploy/clickhouse: every password element uses from_env")
  void noCredentialLiterals() throws IOException {
    Pattern passwordElement =
        Pattern.compile(
            "<(password[a-z0-9_]*)(\\s[^>]*)?>([^<]*)</\\1>|<(password[a-z0-9_]*)(\\s[^>]*)?/>");
    for (Path file : files()) {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      Matcher m = passwordElement.matcher(text);
      while (m.find()) {
        String attributes = m.group(2) != null ? m.group(2) : m.group(5);
        String body = m.group(3) == null ? "" : m.group(3);
        assertThat(attributes).as(file + ": " + m.group()).contains("from_env=");
        assertThat(body).as(file + ": " + m.group()).isEmpty();
      }
      assertThat(text).as(file.toString()).doesNotContainIgnoringCase("<password>");
    }
  }

  @Test
  @Tag("req:SEC-74")
  @DisplayName("T-SEC-74 users.d removes the default user and defines exactly the three accounts")
  void accounts() throws IOException {
    String users = Files.readString(DIR.resolve("users.d/argos.xml"), StandardCharsets.UTF_8);
    assertThat(users).contains("<default remove=\"remove\"/>");
    for (Account account : Account.values()) {
      assertThat(users)
          .contains("<" + account.user() + ">")
          .contains("from_env=\"" + account.passwordHashVariable() + "\"");
    }
    assertThat(users).doesNotContain("GRANT INSERT ON argos.*");
    assertThat(users).contains("<readonly>1</readonly>");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "C-60 the writer's grants are INSERT on the five raw tables; the migrator reads"
          + " system.mutations")
  void writerAndMigratorGrants() throws Exception {
    Element users = child(parse(DIR.resolve("users.d/argos.xml")), "users");
    List<String> writer =
        children(child(child(users, "argos_writer"), "grants")).stream()
            .map(Element::getTextContent)
            .toList();
    assertThat(writer)
        .containsExactly(
            "GRANT INSERT ON argos.xlog",
            "GRANT INSERT ON argos.profile_step",
            "GRANT INSERT ON argos.metric",
            "GRANT INSERT ON argos.sql_stat_raw",
            "GRANT INSERT ON argos.ext_stat_raw");
    assertThat(writer.stream().map(g -> g.substring("GRANT INSERT ON argos.".length())).toList())
        .containsExactlyElementsOf(SchemaParameters.RAW_TABLES);
    List<String> migrator =
        children(child(child(users, "argos_migrator"), "grants")).stream()
            .map(Element::getTextContent)
            .toList();
    assertThat(migrator).contains("GRANT SELECT ON system.mutations");
  }

  @Test
  @Tag("req:N-18")
  @DisplayName("N-18 every from_env variable is produced by the test launcher (no orphan variable)")
  void substitutionVariables() throws IOException {
    Set<String> used = new TreeSet<>();
    for (Path file : files()) {
      Matcher m = FROM_ENV.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (m.find()) {
        used.add(m.group(1));
      }
    }
    Set<String> provided = new TreeSet<>(MemoryBudget.sTopology().environment().keySet());
    for (Account account : Account.values()) {
      provided.add(account.passwordHashVariable());
    }
    assertThat(used).isEqualTo(provided);
  }

  @Test
  @DisplayName("config.d keeps the 13 §7.2 values that the schema tests rely on")
  void serverSettings() throws Exception {
    String config = Files.readString(DIR.resolve("config.d/argos.xml"), StandardCharsets.UTF_8);
    for (String expected :
        List.of(
            "<timezone>UTC</timezone>",
            "<listen_host>127.0.0.1</listen_host>",
            "<max_partition_size_to_drop>0</max_partition_size_to_drop>",
            "<parts_to_delay_insert>300</parts_to_delay_insert>",
            "<parts_to_throw_insert>1000</parts_to_throw_insert>",
            "<ttl_only_drop_parts>1</ttl_only_drop_parts>",
            "<background_pool_size>4</background_pool_size>",
            "<select_from_system_db_requires_grant>true</select_from_system_db_requires_grant>")) {
      assertThat(config).contains(expected);
    }
    // C-71 (B-6): the schedule pool keeps the LTS default.
    assertThat(child(parse(DIR.resolve("config.d/argos.xml")), "background_schedule_pool_size"))
        .isNull();
    assertThat(config).contains("C-71");
  }

  @Test
  @Tag("req:N-19")
  @DisplayName(
      "C-54 the server default compression is ZSTD(1) for every part (one case, no limits)")
  void defaultCompression() throws Exception {
    Element compression = child(parse(DIR.resolve("config.d/argos.xml")), "compression");
    assertThat(compression).isNotNull();
    List<Element> cases = children(compression);
    assertThat(cases).hasSize(1);
    Element only = cases.get(0);
    assertThat(only.getTagName()).isEqualTo("case");
    assertThat(children(only).stream().map(Element::getTagName).toList())
        .containsExactly("method", "level");
    assertThat(child(only, "method").getTextContent()).isEqualTo("zstd");
    assertThat(child(only, "level").getTextContent()).isEqualTo("1");
  }

  @Test
  @Tag("req:N-19")
  @DisplayName("13 §7.2 every system log of the bundled image (26.8.34.13) has a TTL or is removed")
  void systemLogsBounded() throws Exception {
    Element root = parse(DIR.resolve("config.d/argos.xml"));
    Map<String, Element> sections = new LinkedHashMap<>();
    for (Element e : children(root)) {
      sections.put(e.getTagName(), e);
    }
    for (String log : IMAGE_SYSTEM_LOGS) {
      Element section = sections.get(log);
      assertThat(section).as(log + " must be configured (TTL or remove)").isNotNull();
      boolean removed = "1".equals(section.getAttribute("remove"));
      Element ttl = child(section, "ttl");
      assertThat(removed || (ttl != null && ttl.getTextContent().contains("INTERVAL")))
          .as(log)
          .isTrue();
    }
    for (Map.Entry<String, Element> e : sections.entrySet()) {
      if (e.getKey().endsWith("_log") && !"1".equals(e.getValue().getAttribute("remove"))) {
        assertThat(child(e.getValue(), "ttl")).as(e.getKey()).isNotNull();
      }
    }
    for (String kept : List.of("query_log", "metric_log", "trace_log", "error_log")) {
      assertThat(child(sections.get(kept), "ttl").getTextContent())
          .as(kept)
          .isEqualTo("event_date + INTERVAL 7 DAY");
    }
  }

  @Test
  @Tag("req:SEC-75")
  @Tag("req:SEC-76")
  @DisplayName(
      "13 §7.2 unused listeners removed (MySQL, PostgreSQL, interserver) and no remote URL allowed")
  void networkSurface() throws Exception {
    Element root = parse(DIR.resolve("config.d/argos.xml"));
    for (String port : List.of("mysql_port", "postgresql_port", "interserver_http_port")) {
      Element e = child(root, port);
      assertThat(e).as(port).isNotNull();
      assertThat(e.getAttribute("remove")).as(port).isEqualTo("1");
    }
    assertThat(child(root, "http_port")).isNull();
    assertThat(child(root, "tcp_port")).isNull();
    Element allow = child(root, "remote_url_allow_hosts");
    assertThat(allow).as("remote_url_allow_hosts").isNotNull();
    assertThat(children(allow)).as("remote_url_allow_hosts entries").isEmpty();
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "SEC-76 writer profile constraints and the migrator's sub-privileges (no dictionary DDL)")
  void accountPrivileges() throws Exception {
    Element root = parse(DIR.resolve("users.d/argos.xml"));
    Element writer = child(child(root, "profiles"), "argos_writer_profile");
    Element constraints = child(writer, "constraints");
    assertThat(constraints).isNotNull();
    for (String constant :
        List.of(
            "async_insert",
            "insert_deduplicate",
            "deduplicate_blocks_in_dependent_materialized_views",
            "log_queries",
            "readonly",
            "allow_ddl")) {
      assertThat(child(child(constraints, constant), "const")).as(constant).isNotNull();
    }
    assertThat(child(child(constraints, "max_insert_threads"), "max").getTextContent())
        .isEqualTo("1");
    assertThat(child(child(constraints, "max_memory_usage"), "max").getAttribute("from_env"))
        .isEqualTo("ARGOS_CH_WRITER_MAX_MEMORY_USAGE");

    Element migrator = child(child(root, "users"), "argos_migrator");
    List<String> grants =
        children(child(migrator, "grants")).stream().map(Element::getTextContent).toList();
    assertThat(grants)
        .containsExactly(
            "GRANT CREATE DATABASE, CREATE TABLE, CREATE VIEW, DROP DATABASE, DROP TABLE,"
                + " DROP VIEW, ALTER, INSERT, SELECT, BACKUP ON argos.*",
            "GRANT SELECT ON system.mutations");
    String users = Files.readString(DIR.resolve("users.d/argos.xml"), StandardCharsets.UTF_8);
    assertThat(users).doesNotContain("DICTIONARY ON", "FUNCTION ON", "NAMED COLLECTION ON");
  }

  /** The *_log sections of /etc/clickhouse-server/config.xml in the bundled image (26.8.34.13). */
  private static final List<String> IMAGE_SYSTEM_LOGS =
      List.of(
          "query_log",
          "trace_log",
          "query_thread_log",
          "query_views_log",
          "part_log",
          "background_schedule_pool_log",
          "text_log",
          "metric_log",
          "error_log",
          "instrumentation_trace_log",
          "query_metric_log",
          "asynchronous_metric_log",
          "iceberg_metadata_log",
          "delta_lake_metadata_log",
          "opentelemetry_span_log",
          "crash_log",
          "processors_profile_log",
          "asynchronous_insert_log",
          "backup_log",
          "s3queue_log",
          "blob_storage_log",
          "aggregated_zookeeper_log",
          "zookeeper_connection_log");

  private static Element parse(Path file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
  }

  private static List<Element> children(Element parent) {
    List<Element> children = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        children.add(e);
      }
    }
    return children;
  }

  private static Element child(Element parent, String name) {
    for (Element e : children(parent)) {
      if (e.getTagName().equals(name)) {
        return e;
      }
    }
    return null;
  }

  private static List<Path> files() throws IOException {
    try (Stream<Path> walk = Files.walk(DIR)) {
      return walk.filter(Files::isRegularFile)
          .filter(f -> f.toString().endsWith(".xml") || f.toString().endsWith(".sql"))
          .toList();
    }
  }
}
