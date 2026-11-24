/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-80c (18 §12 "각 이벤트 발생 → 행 단언"): every event of the catalogue is asserted as an {@code
 * audit_logs} row by some integration or security test. A source file counts when it reads the
 * audit rows ({@code AuditLog.} or {@code audit_logs}) and names the event as a string literal
 * ({@code "LOGIN_SUCCESS"}) or a constant ({@code AuditEvent.LOGIN_SUCCESS}). The exceptions below
 * carry their reason; an exception whose event became covered must be removed ({@link
 * #exceptionsAreNeeded}).
 */
@Tag("req:SEC-80")
class AuditCatalogCoverageTest {

  private static final Path SERVER = Path.of(System.getProperty("argos.serverDir", "."));

  private static final List<Path> SOURCES =
      List.of(SERVER.resolve("src/integrationTest/java"), SERVER.resolve("src/securityTest/java"));

  private static final Pattern READS_ROWS = Pattern.compile("AuditLog\\.|audit_logs");

  /**
   * Events not yet asserted as rows, with the reason. Lane entries ({@code WO-28 lane}) name the
   * test of the same work order that asserts them; the integration step removes them once those
   * tests are in.
   */
  static final Map<String, String> EXCEPTIONS = exceptions();

  private static Map<String, String> exceptions() {
    // CUSTOM_METHOD_CHANGED: asserted by ConfigChangeIT (ADR-0010 C-238 (8), pair 14 wrap-up).
    Map<String, String> m = new LinkedHashMap<>();
    return m;
  }

  /** The events asserted as rows by the sources. */
  static Set<String> covered() {
    List<String> texts = new ArrayList<>();
    for (Path root : SOURCES) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        files
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(
                p -> {
                  try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (READS_ROWS.matcher(text).find()) {
                      texts.add(text);
                    }
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    Set<String> covered = new TreeSet<>();
    for (AuditEvent e : AuditEvent.values()) {
      Pattern named = Pattern.compile("\"" + e.name() + "\"|AuditEvent\\." + e.name() + "\\b");
      if (texts.stream().anyMatch(t -> named.matcher(t).find())) {
        covered.add(e.name());
      }
    }
    return covered;
  }

  @Test
  @DisplayName(
      "T-SEC-80c every catalogue event is asserted as an audit_logs row by an integration or"
          + " security test, or listed with its reason")
  void everyEventAsserted() {
    assertThat(SOURCES.get(0)).as("integration test sources").isDirectory();
    Set<String> covered = covered();
    Set<String> missing = new TreeSet<>();
    Arrays.stream(AuditEvent.values())
        .map(Enum::name)
        .filter(n -> !covered.contains(n) && !EXCEPTIONS.containsKey(n))
        .forEach(missing::add);
    assertThat(missing).as("events without a row assertion and without an exception").isEmpty();
    assertThat(EXCEPTIONS.keySet())
        .as("exceptions name catalogue events")
        .allMatch(n -> Arrays.stream(AuditEvent.values()).anyMatch(e -> e.name().equals(n)));
    assertThat(EXCEPTIONS.values()).allMatch(reason -> reason.length() > 20);
  }

  @Test
  @DisplayName(
      "T-SEC-80c an exception of an earlier work order is removed once its event is asserted")
  void exceptionsAreNeeded() {
    Set<String> covered = covered();
    Set<String> stale = new TreeSet<>();
    EXCEPTIONS.forEach(
        (event, reason) -> {
          if (!reason.startsWith("WO-28 lane") && covered.contains(event)) {
            stale.add(event);
          }
        });
    assertThat(stale).as("exceptions whose events are asserted now: remove them").isEmpty();
  }
}
