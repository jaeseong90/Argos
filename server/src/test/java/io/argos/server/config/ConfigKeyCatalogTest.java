/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import io.argos.server.config.ConfigKeyCatalog.Kind;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/** The key catalog behind the snapshot (T-API-009), the effective configuration and messages. */
@Tag("req:O-R03")
class ConfigKeyCatalogTest {

  private static final Map<String, ConfigKey> KEYS =
      ConfigKeyCatalog.keys().stream()
          .collect(Collectors.toMap(ConfigKey::name, Function.identity()));

  @Test
  @DisplayName("key names follow the binder: kebab case, digit words, @Name")
  void names() {
    assertThat(ConfigKeyCatalog.kebab("metricsPer5s")).isEqualTo("metrics-per-5s");
    assertThat(ConfigKeyCatalog.kebab("txPerSecond")).isEqualTo("tx-per-second");
    assertThat(KEYS)
        .containsKeys(
            "argos.collector.quota.metrics-per-5s",
            "argos.retention.rollup-1m",
            "argos.retention.rollup-1h",
            "argos.master-key-file",
            "argos.agent.auto-register",
            "argos.testing.hold-before-phase");
    assertThat(ConfigKeyCatalog.keyOf("collector.limits.maxStepsPerBatch"))
        .isEqualTo("argos.collector.limits.max-steps-per-batch");
    assertThat(ConfigKeyCatalog.keyOf("metrics.allowCidrs[1].<list element>"))
        .isEqualTo("argos.metrics.allow-cidrs");
  }

  /** A record with a renamed component, like {@code Retention.rollup1m}. */
  record Renamed(@Name("other-name") int renamedValue, int plainValue) {}

  @Test
  @DisplayName("T-API-009 @Name on a record component is the key name, as the binder sees it")
  void nameAnnotation() {
    RecordComponent[] components = Renamed.class.getRecordComponents();
    // The trap: @Name targets PARAMETER and FIELD, so the record component itself never has it.
    assertThat(components[0].getAnnotation(Name.class)).isNull();
    assertThat(ConfigKeyCatalog.keyName(components[0])).isEqualTo("other-name");
    assertThat(ConfigKeyCatalog.keyName(components[1])).isEqualTo("plain-value");
    Renamed bound =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of("x.other-name", "7", "x.renamed-value", "8", "x.plain-value", "9")))
            .bindOrCreate("x", Renamed.class);
    assertThat(bound).isEqualTo(new Renamed(7, 9));
  }

  /**
   * T-API-009 round trip: every leaf key, set by its catalog name to a value other than its
   * default, changes the bound value. A catalog name the binder does not use (a missed
   * {@code @Name}, a different word split) leaves the default and fails here.
   */
  @Test
  @DisplayName("T-API-009 every catalog key binds: a non-default value by its name changes it")
  void everyKeyBindsByItsName() {
    ArgosProperties defaults =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate(ConfigKeyCatalog.PREFIX, ArgosProperties.class);
    // One binding with every key set: each catalog name is unique and names one component, so a
    // name the binder does not use leaves exactly its own component at the default.
    Map<String, String> source = new TreeMap<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      source.putAll(otherValue(key, key.valueIn(defaults)));
    }
    ArgosProperties bound =
        new Binder(new MapConfigurationPropertySource(source))
            .bindOrCreate(ConfigKeyCatalog.PREFIX, ArgosProperties.class);
    List<String> notBound = new ArrayList<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      Object before = key.valueIn(defaults);
      Object after = key.valueIn(bound);
      if (Objects.equals(before, after)) {
        notBound.add(key.name() + " (set " + otherValue(key, before) + ", still " + after + ")");
      }
    }
    assertThat(source).hasSizeGreaterThanOrEqualTo(ConfigKeyCatalog.keys().size());
    assertThat(notBound).as("keys whose catalog name does not bind").isEmpty();
  }

  /** A setting of {@code key} to a value of its type other than {@code current}. */
  private static Map<String, String> otherValue(ConfigKey key, Object current) {
    String type = key.type();
    String name = key.name();
    if (type.startsWith("enum(")) {
      List<String> constants =
          List.of(type.substring("enum(".length(), type.length() - 1).split("\\|"));
      String now = current == null ? null : ConfigKeyCatalog.render(current);
      return Map.of(name, constants.stream().filter(c -> !c.equals(now)).findFirst().orElseThrow());
    }
    if (type.startsWith("list<")) {
      return Map.of(name + "[0]", "round-trip-value");
    }
    if (type.equals("map<string,boolean>")) {
      @SuppressWarnings("unchecked")
      Map<String, Boolean> map = current == null ? Map.of() : (Map<String, Boolean>) current;
      return Map.of(name + ".default", Boolean.toString(!map.getOrDefault("default", false)));
    }
    String value =
        switch (type) {
          case "int" -> Integer.toString((Integer) current + 1);
          case "long" -> Long.toString((Long) current + 1);
          case "double" -> Double.toString((Double) current / 2);
          case "boolean" -> Boolean.toString(!(Boolean) current);
          case "duration" -> plusOneDay((Duration) current).toMillis() + "ms";
          case "data-size" -> plusOneMegabyte((DataSize) current).toBytes() + "B";
          case "time-zone" -> "UTC".equals(String.valueOf(current)) ? "Europe/Berlin" : "UTC";
          case "string" -> "round-trip-value";
          default ->
              throw new AssertionError(
                  "No non-default value for type "
                      + type
                      + " of "
                      + name
                      + ": add one to ConfigKeyCatalogTest.otherValue");
        };
    return Map.of(name, value);
  }

  private static Duration plusOneDay(Duration current) {
    return current == null ? Duration.ofDays(1) : current.plusDays(1);
  }

  private static DataSize plusOneMegabyte(DataSize current) {
    long bytes = current == null ? 0 : current.toBytes();
    return DataSize.ofBytes(bytes + DataSize.ofMegabytes(1).toBytes());
  }

  @Test
  @DisplayName("types and defaults in the snapshot notation")
  void typesAndDefaults() {
    assertThat(KEYS.get("argos.retention.xlog").snapshotLine())
        .isEqualTo("argos.retention.xlog\tduration\t30d");
    assertThat(KEYS.get("argos.wal.max-bytes").snapshotLine())
        .isEqualTo("argos.wal.max-bytes\tdata-size\t10GB");
    assertThat(KEYS.get("argos.privacy.default-ip-mode").snapshotLine())
        .isEqualTo("argos.privacy.default-ip-mode\tenum(full|masked|off)\tmasked");
    assertThat(KEYS.get("argos.metrics.allow-cidrs").snapshotLine())
        .isEqualTo("argos.metrics.allow-cidrs\tlist<string>\t[]");
    assertThat(KEYS.get("argos.agent.auto-register").snapshotLine())
        .isEqualTo("argos.agent.auto-register\tmap<string,boolean>\t{default=true}");
    assertThat(KEYS.get("argos.web.public-url").defaultValue()).isEqualTo("(none)");
    assertThat(ConfigKeyCatalog.render(Duration.ofMinutes(3))).isEqualTo("3m");
    assertThat(ConfigKeyCatalog.render(Duration.ofMillis(1500))).isEqualTo("1500ms");
    assertThat(ConfigKeyCatalog.render(DataSize.ofMegabytes(256))).isEqualTo("256MB");
    assertThat(ConfigKeyCatalog.render(List.of("a", "b"))).isEqualTo("[a, b]");
  }

  @Test
  @DisplayName("secret keys and secret file keys are classified")
  void kinds() {
    List<String> secrets =
        KEYS.values().stream()
            .filter(k -> k.kind() == Kind.SECRET)
            .map(ConfigKey::name)
            .sorted()
            .toList();
    assertThat(secrets)
        .containsExactly(
            "argos.alert.smtp.password",
            "argos.clickhouse.migrator.password",
            "argos.clickhouse.reader.password",
            "argos.clickhouse.writer.password",
            "argos.master-key",
            "argos.metrics.token",
            "argos.postgres.owner.password",
            "argos.postgres.password");
    assertThat(
            KEYS.values().stream().filter(k -> k.kind() == Kind.SECRET_FILE).map(ConfigKey::name))
        .hasSize(secrets.size())
        .allMatch(n -> secrets.contains(n.substring(0, n.length() - "-file".length())));
  }
}
