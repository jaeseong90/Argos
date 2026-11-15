/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Allow-list of the LOG-01 rule ({@link AgentCodeRules#logArgumentsAreSafe}, 10 §14, SEC-26):
 * {@code src/archTest/resources/log-argument-allowlist.txt}. One entry per line, a reason after the
 * name is required:
 *
 * <pre>
 * producer io.argos.agent.core.export.AgentInfo#version    the jar manifest, not collected data
 * site io.argos.agent.core.boot.AgentStarter#reportConfiguration    configuration warnings ...
 * </pre>
 *
 * <ul>
 *   <li>{@code producer <class>#<method or field>}: strings from this source are identifiers or
 *       operator configuration, never collected data;
 *   <li>{@code site <class>#<method>}: every log call in that method is reviewed by hand (the
 *       reason says why its strings cannot be collected data).
 * </ul>
 *
 * Adding a line is reviewed like the advice allow-list: the reason must say where the value comes
 * from.
 */
final class LogAllowlist {
  static final String RESOURCE = "/log-argument-allowlist.txt";

  private final Map<String, String> producers;
  private final Map<String, String> sites;

  private LogAllowlist(Map<String, String> producers, Map<String, String> sites) {
    this.producers = Map.copyOf(producers);
    this.sites = Map.copyOf(sites);
  }

  static LogAllowlist empty() {
    return new LogAllowlist(Map.of(), Map.of());
  }

  /** The production allow-list (archTest resource). */
  static LogAllowlist production() {
    try (InputStream in =
            Objects.requireNonNull(
                LogAllowlist.class.getResourceAsStream(RESOURCE), "missing resource " + RESOURCE);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return parse(reader.lines().toList(), RESOURCE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses allow-list lines; a malformed line or a missing reason is an error. */
  static LogAllowlist parse(List<String> lines, String source) {
    Map<String, String> producers = new LinkedHashMap<>();
    Map<String, String> sites = new LinkedHashMap<>();
    int number = 0;
    for (String raw : lines) {
      number++;
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\s+", 3);
      if (parts.length < 3 || !parts[1].contains("#")) {
        throw new IllegalArgumentException(
            source + ":" + number + ": expected '<producer|site> <class>#<member> <reason>'");
      }
      switch (parts[0]) {
        case "producer" -> producers.put(parts[1], parts[2]);
        case "site" -> sites.put(parts[1], parts[2]);
        default ->
            throw new IllegalArgumentException(
                source + ":" + number + ": unknown kind '" + parts[0] + "'");
      }
    }
    return new LogAllowlist(producers, sites);
  }

  boolean allowsProducer(String classAndMember) {
    return producers.containsKey(classAndMember);
  }

  boolean allowsSite(String className, String method) {
    return sites.containsKey(className + "#" + method);
  }

  Map<String, String> producers() {
    return producers;
  }

  Map<String, String> sites() {
    return sites;
  }
}
