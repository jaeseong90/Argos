/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.core.config.YamlSubset.Entry;
import io.argos.agent.core.config.YamlSubset.Kind;
import io.argos.agent.core.config.YamlSubset.YamlException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The own bounded YAML subset of argos-agent.yml (10 §15, WO-10): what it reads and rejects. */
@Tag("req:F-A15")
class YamlSubsetTest {
  private static final String TOKEN = "argos_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

  private static String yaml(String... lines) {
    return String.join("\n", lines) + "\n";
  }

  private static Map<String, Entry> parse(String text) throws YamlException {
    Map<String, Entry> byKey = new LinkedHashMap<String, Entry>();
    for (Entry entry : YamlSubset.parse(text)) {
      byKey.put(entry.key(), entry);
    }
    return byKey;
  }

  @Test
  @DisplayName("the 17 §6.2 agent configuration example is read and flattened to dot keys")
  void deploymentExample() throws Exception {
    Map<String, Entry> entries =
        parse(
            yaml(
                "# /opt/argos-agent/argos-agent.yml  (0640)",
                "server:",
                "  address: argos.internal:9100",
                "  tls:",
                "    ca-file: /opt/argos-agent/argos-ca.crt      # own CA",
                "  token-file: /opt/argos-agent/token            # 0600",
                "app:",
                "  name: order-api",
                "  env: prod",
                "remote-config:",
                "  allow: [sampling.*, instrumentation.disabled, instrumentation.ignore-paths,"
                    + " log.level, argos.enabled]",
                "log:",
                "  dir: /opt/argos-agent/logs                    # 0700, 10MB x 5"));
    assertThat(entries.keySet())
        .containsExactly(
            "server.address",
            "server.tls.ca-file",
            "server.token-file",
            "app.name",
            "app.env",
            "remote-config.allow",
            "log.dir");
    assertThat(entries.get("server.address").scalar()).isEqualTo("argos.internal:9100");
    assertThat(entries.get("server.tls.ca-file").scalar())
        .isEqualTo("/opt/argos-agent/argos-ca.crt");
    assertThat(entries.get("server.address").line()).isEqualTo(3);
    Entry allow = entries.get("remote-config.allow");
    assertThat(allow.kind()).isEqualTo(Kind.LIST);
    assertThat(allow.items())
        .containsExactly(
            "sampling.*",
            "instrumentation.disabled",
            "instrumentation.ignore-paths",
            "log.level",
            "argos.enabled");
  }

  @Test
  @DisplayName("block sequences, indentless sequences, dotted and quoted keys, nulls and quoting")
  void subsetForms() throws Exception {
    Map<String, Entry> entries =
        parse(
            "\uFEFF---\r\n"
                + yaml(
                    "server.tls: false",
                    "instrumentation:",
                    "  ignore-paths:",
                    "  - /health",
                    "  - '/it''s'",
                    "  disabled:",
                    "    - jdbc",
                    "    - \"httpclient-jdk\"   # quoted item",
                    "  exclude-classes: []",
                    "  app-packages:",
                    "sampling:",
                    "  profile:",
                    "    slow-threshold-ms:",
                    "      default: 800",
                    "      services:",
                    "        \"GET /orders/{id}\": 2000",
                    "        'batch: nightly': 60000",
                    "capture:",
                    "  user-id.source: ~",
                    "  mask-patterns: [\"\\\\d{6}-\\\\d{7}\", 'a,b', plain]",
                    "app:",
                    "  name: \"tab\\there \\u00e9 \\\"q\\\" \\\\ \\/ \\x41\"",
                    "  group: # comment only, no value",
                    "  version: v1#not-a-comment"));
    assertThat(entries.get("server.tls").scalar()).isEqualTo("false");
    assertThat(entries.get("instrumentation.ignore-paths").items())
        .containsExactly("/health", "/it's");
    assertThat(entries.get("instrumentation.disabled").items())
        .containsExactly("jdbc", "httpclient-jdk");
    assertThat(entries.get("instrumentation.exclude-classes").kind()).isEqualTo(Kind.LIST);
    assertThat(entries.get("instrumentation.exclude-classes").items()).isEmpty();
    assertThat(entries.get("instrumentation.app-packages").kind()).isEqualTo(Kind.SCALAR);
    assertThat(entries.get("instrumentation.app-packages").scalar()).isNull();
    assertThat(entries.get("sampling.profile.slow-threshold-ms.default").scalar()).isEqualTo("800");
    assertThat(entries.get("sampling.profile.slow-threshold-ms.services.GET /orders/{id}").scalar())
        .isEqualTo("2000");
    assertThat(entries).containsKey("sampling.profile.slow-threshold-ms.services.batch: nightly");
    assertThat(entries.get("capture.user-id.source").scalar()).isNull();
    assertThat(entries.get("capture.mask-patterns").items())
        .containsExactly("\\d{6}-\\d{7}", "a,b", "plain");
    assertThat(entries.get("app.name").scalar()).isEqualTo("tab\there \u00e9 \"q\" \\ / A");
    assertThat(entries.get("app.group").scalar()).isNull();
    assertThat(entries.get("app.version").scalar()).isEqualTo("v1#not-a-comment");
  }

  @Test
  @DisplayName("instrumentation.custom-methods: a block sequence of mappings, compact and expanded")
  void sequenceOfMappings() throws Exception {
    Map<String, Entry> entries =
        parse(
            yaml(
                "instrumentation:",
                "  custom-methods:",
                "    - pattern: \"com.example.service.*Service#*\"",
                "      step-type: METHOD",
                "      as-transaction: false",
                "    -",
                "      pattern: com.example.Job#run",
                "  custom-methods.max-classes-per-pattern: 100"));
    Entry custom = entries.get("instrumentation.custom-methods");
    assertThat(custom.kind()).isEqualTo(Kind.MAP_LIST);
    assertThat(custom.maps()).hasSize(2);
    assertThat(custom.maps().get(0))
        .containsEntry("pattern", "com.example.service.*Service#*")
        .containsEntry("step-type", "METHOD")
        .containsEntry("as-transaction", "false");
    assertThat(custom.maps().get(1)).containsOnlyKeys("pattern");
    assertThat(entries.get("instrumentation.custom-methods.max-classes-per-pattern").scalar())
        .isEqualTo("100");
  }

  @Test
  @DisplayName("an empty file or one with comments only has no entries")
  void empty() throws Exception {
    assertThat(YamlSubset.parse("")).isEmpty();
    assertThat(YamlSubset.parse(yaml("# nothing", "   ", "---", "# still nothing"))).isEmpty();
  }

  static Stream<Arguments> outsideTheSubset() {
    return Stream.of(
        Arguments.of("tab indentation", yaml("server:", "\taddress: x"), 2),
        Arguments.of("flow mapping", yaml("server: {address: x}"), 1),
        Arguments.of("block scalar", yaml("app:", "  name: |", "    multi"), 2),
        Arguments.of("folded scalar", yaml("app:", "  name: >", "    multi"), 2),
        Arguments.of("anchor", yaml("base: &b x", "other: *b"), 1),
        Arguments.of("alias", yaml("other: *b"), 1),
        Arguments.of("tag", yaml("app:", "  name: !!str x"), 2),
        Arguments.of("directive", yaml("%YAML 1.2", "---", "a: b"), 1),
        Arguments.of("second document", yaml("a: b", "---", "c: d"), 2),
        Arguments.of("document end", yaml("a: b", "..."), 2),
        Arguments.of("nested sequence", yaml("a:", "  - - x"), 2),
        Arguments.of("nested flow", yaml("a: [x, [y]]"), 1),
        Arguments.of("duplicate key", yaml("a: 1", "b: 2", "a: 3"), 3),
        Arguments.of(
            "duplicate dotted key", yaml("server.tls: false", "server:", "  tls: true"), 3),
        Arguments.of("unexpected indentation", yaml("a: 1", "  b: 2"), 2),
        Arguments.of("multi-line plain scalar", yaml("a: one", "  two"), 2),
        Arguments.of("unclosed double quote", yaml("a: \"open"), 1),
        Arguments.of("unclosed single quote", yaml("a: 'open"), 1),
        Arguments.of("unclosed flow sequence", yaml("a: [x, y"), 1),
        Arguments.of("empty flow item", yaml("a: [x, , y]"), 1),
        Arguments.of("colon space in a plain value", yaml("a: b: c"), 1),
        Arguments.of("top-level sequence", yaml("- a", "- b"), 1),
        Arguments.of("key without colon", yaml("server", "  address: x"), 1),
        Arguments.of("no space after colon", yaml("a:b: c", "\"q\":x"), 2),
        Arguments.of("complex key", yaml("? a", ": b"), 1),
        Arguments.of("control character", yaml("a: b\u0007"), 1),
        Arguments.of("unsupported escape", yaml("a: \"\\q\""), 1),
        Arguments.of("text after a quoted value", yaml("a: \"x\" y"), 1),
        Arguments.of("mixed sequence", yaml("a:", "  - x", "  - k: v"), 3),
        Arguments.of("mapping in a sequence item holds a list", yaml("a:", "  - k: [x]"), 2),
        Arguments.of("empty sequence item", yaml("a:", "  -", "b: c"), 2),
        Arguments.of("sequence after key on one line", yaml("a: - x"), 1),
        Arguments.of("indentation below the top level", yaml("  a: 1", "b: 2"), 2));
  }

  @ParameterizedTest(name = "outside the subset: {0} (line {2})")
  @MethodSource("outsideTheSubset")
  void rejectsOutsideTheSubset(String name, String text, int line) {
    assertThatThrownBy(() -> YamlSubset.parse(text))
        .isInstanceOf(YamlException.class)
        .satisfies(e -> assertThat(((YamlException) e).line()).isEqualTo(line));
  }

  @Test
  @DisplayName("caps: file 256 KiB, depth 8, 2,000 entries, scalar 8 KiB")
  void caps() throws Exception {
    // file size
    StringBuilder big = new StringBuilder("a: \"");
    while (big.length() <= YamlSubset.MAX_FILE_BYTES) {
      big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n");
    }
    assertThatThrownBy(() -> YamlSubset.parse(big.toString().getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("larger than 262144 bytes");

    // depth: 8 levels pass, 9 fail
    assertThat(YamlSubset.parse(nested(8))).hasSize(1);
    assertThatThrownBy(() -> YamlSubset.parse(nested(9)))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("deeper than 8");

    // entries: 2,000 pass, 2,001 fail (keys and sequence items count)
    assertThat(YamlSubset.parse(keys(YamlSubset.MAX_ENTRIES))).hasSize(YamlSubset.MAX_ENTRIES);
    assertThatThrownBy(() -> YamlSubset.parse(keys(YamlSubset.MAX_ENTRIES + 1)))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries");
    StringBuilder items = new StringBuilder("list:\n");
    for (int i = 0; i < YamlSubset.MAX_ENTRIES; i++) {
      items.append("  - item").append(i).append('\n');
    }
    assertThatThrownBy(() -> YamlSubset.parse(items.toString()))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries");
    StringBuilder flowItems = new StringBuilder("list: [");
    for (int i = 0; i <= YamlSubset.MAX_ENTRIES; i++) {
      flowItems.append(i == 0 ? "" : ",").append(i);
    }
    assertThatThrownBy(() -> YamlSubset.parse(flowItems.append("]\n").toString()))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries");

    // flow sequence items count against the cap of the whole document, not per sequence
    assertThat(YamlSubset.parse(flowLists(999, 999))).hasSize(2); // 2 keys + 1,998 items
    assertThatThrownBy(() -> YamlSubset.parse(flowLists(999, 1000))) // 2 keys + 1,999 items
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries")
        .satisfies(e -> assertThat(((YamlException) e).line()).isEqualTo(2));
    assertThat(YamlSubset.parse(flowLists(500, 500, 500, 496))).hasSize(4); // 4 + 1,996
    assertThatThrownBy(() -> YamlSubset.parse(flowLists(500, 500, 500, 497)))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries");
    // block and flow items share the cap
    StringBuilder mixed = new StringBuilder("block:\n");
    for (int i = 0; i < 1000; i++) {
      mixed.append("  - b").append(i).append('\n');
    }
    assertThat(YamlSubset.parse(mixed + flowLists(998))).hasSize(2); // 2 + 1,000 + 998
    assertThatThrownBy(() -> YamlSubset.parse(mixed + flowLists(999)))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("more than 2000 entries");

    // scalar length: plain, quoted and key
    String longValue = repeat('v', YamlSubset.MAX_SCALAR_CHARS + 1);
    assertThat(YamlSubset.parse("a: " + repeat('v', YamlSubset.MAX_SCALAR_CHARS))).hasSize(1);
    for (String text :
        Arrays.asList(
            "a: " + longValue,
            "a: \"" + longValue + "\"",
            "a: '" + longValue + "'",
            longValue + ": x")) {
      assertThatThrownBy(() -> YamlSubset.parse(text))
          .isInstanceOf(YamlException.class)
          .hasMessageContaining("longer than 8192 characters");
    }
  }

  @Test
  @DisplayName("hostile input: invalid UTF-8, anchors bomb, deep nesting and the token stay out")
  void hostile() {
    byte[] invalid = {'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n'};
    assertThatThrownBy(() -> YamlSubset.parse(invalid))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("not valid UTF-8");
    assertThatThrownBy(
            () ->
                YamlSubset.parse(
                    yaml(
                        "a: &a [lol, lol, lol]",
                        "b: &b [*a, *a, *a]",
                        "c: &c [*b, *b, *b]",
                        "d: [*c, *c, *c]")))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("anchors are not supported");
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      deep.append(repeat(' ', i)).append("k").append(i).append(":\n");
    }
    assertThatThrownBy(() -> YamlSubset.parse(deep.toString()))
        .isInstanceOf(YamlException.class)
        .hasMessageContaining("deeper than 8");
    // An error on a line that holds the token never quotes that line (SEC-06).
    assertThatThrownBy(() -> YamlSubset.parse(yaml("server:", "  token: " + TOKEN + ": x")))
        .isInstanceOf(YamlException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain("argos_"));
    assertThatThrownBy(() -> YamlSubset.parse(yaml("server:", "  token: '" + TOKEN)))
        .isInstanceOf(YamlException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
  }

  @Test
  @DisplayName("line numbers count blank lines, comments and CRLF line ends")
  void lineNumbers() throws Exception {
    List<Entry> entries = YamlSubset.parse("# c\r\n\r\na: 1\r\n\r\n# c\r\nb: 2\r\n");
    assertThat(entries.get(0).line()).isEqualTo(3);
    assertThat(entries.get(1).line()).isEqualTo(6);
    assertThatThrownBy(() -> YamlSubset.parse("a: 1\r\n\r\nb: {x}\r\n"))
        .isInstanceOf(YamlException.class)
        .satisfies(e -> assertThat(((YamlException) e).line()).isEqualTo(3));
  }

  private static String nested(int depth) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      out.append(repeat(' ', 2 * i)).append("k").append(i).append(i == depth - 1 ? ": v" : ":");
      out.append('\n');
    }
    return out.toString();
  }

  /** One key per size, each with a single-line flow sequence of that many items. */
  private static String flowLists(int... sizes) {
    StringBuilder out = new StringBuilder();
    for (int k = 0; k < sizes.length; k++) {
      out.append("flow").append(k).append(": [");
      for (int i = 0; i < sizes[k]; i++) {
        out.append(i == 0 ? "" : ", ").append('v').append(i);
      }
      out.append("]\n");
    }
    return out.toString();
  }

  private static String keys(int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      out.append("key").append(i).append(": ").append(i).append('\n');
    }
    return out.toString();
  }

  static String repeat(char c, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(c);
    }
    return out.toString();
  }
}
