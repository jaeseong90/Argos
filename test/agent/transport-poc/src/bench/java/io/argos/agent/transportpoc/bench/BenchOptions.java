/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Command line of {@link TransportBench} (written by the Gradle tasks transportPocSmoke and
 * transportPocBench).
 *
 * @param mode {@code smoke} (functional checks, exit code) or {@code bench} (measurement matrix)
 * @param out output directory (bench: a new timestamped directory is created below it)
 * @param repetitions repetitions of each resource measurement
 * @param only variant ids to run (empty: all)
 * @param baseAgent release agent jar without transport (baseline)
 * @param hostClasses class path of the host application
 * @param collectorClasspath class path of the fake collector
 * @param variants comparison jars by id, in build order
 * @param jdks JDK homes by name ("8", "11", "17", "21", "25", "8u242", "8u252")
 * @param knownAfUnixTemp smoke: variants whose Windows JDK 16+ AF_UNIX selector file is the known
 *     finding ADR-0005 P3 instead of a failure (empty: every temp file fails)
 */
record BenchOptions(
    String mode,
    Path out,
    int repetitions,
    Set<String> only,
    Path baseAgent,
    String hostClasses,
    String collectorClasspath,
    Map<String, Path> variants,
    Map<String, Path> jdks,
    Set<String> knownAfUnixTemp) {

  static BenchOptions parse(String[] args) {
    String mode = "smoke";
    Path out = null;
    int repetitions = 1;
    Set<String> only = new LinkedHashSet<>();
    Path base = null;
    String host = null;
    String collector = null;
    Map<String, Path> variants = new LinkedHashMap<>();
    Map<String, Path> jdks = new LinkedHashMap<>();
    Set<String> knownAfUnixTemp = new LinkedHashSet<>();
    for (String arg : args) {
      int eq = arg.indexOf('=');
      if (!arg.startsWith("--") || eq < 0) {
        throw new IllegalArgumentException("bad argument " + arg);
      }
      String name = arg.substring(2, eq);
      String value = arg.substring(eq + 1);
      switch (name) {
        case "mode" -> mode = value;
        case "out" -> out = Path.of(value);
        case "repetitions" -> repetitions = Integer.parseInt(value);
        case "only" -> {
          for (String id : value.split(",", -1)) {
            if (!id.isBlank()) {
              only.add(id.trim());
            }
          }
        }
        case "known-af-unix-temp" -> {
          for (String id : value.split(",", -1)) {
            if (!id.isBlank()) {
              knownAfUnixTemp.add(id.trim());
            }
          }
        }
        case "base-agent" -> base = Path.of(value);
        case "host-classes" -> host = value;
        case "collector-classpath" -> collector = value;
        case "variant" ->
            variants.put(
                value.substring(0, value.indexOf('=')),
                Path.of(value.substring(value.indexOf('=') + 1)));
        case "jdk" ->
            jdks.put(
                value.substring(0, value.indexOf('=')),
                Path.of(value.substring(value.indexOf('=') + 1)));
        default -> throw new IllegalArgumentException("unknown option --" + name);
      }
    }
    if (out == null
        || base == null
        || host == null
        || collector == null
        || !jdks.containsKey("21")) {
      throw new IllegalArgumentException(
          "--out, --base-agent, --host-classes, --collector-classpath and --jdk=21=... are required");
    }
    if (!only.isEmpty()) {
      variants.keySet().retainAll(only);
    }
    return new BenchOptions(
        mode, out, repetitions, only, base, host, collector, variants, jdks, knownAfUnixTemp);
  }

  boolean bench() {
    return mode.equals("bench");
  }
}
