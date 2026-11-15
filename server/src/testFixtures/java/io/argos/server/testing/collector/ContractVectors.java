/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.collector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The argos.v1 contract vectors of WO-11 ({@code
 * agent/agent-testing/src/main/resources/contract/argos-v1-vectors.json}, 11 §16): the same file
 * the agent's FakeCollector is driven with, read here so that the real collector is checked against
 * the identical inputs and expected outcomes (WO-21). Entries with a {@code fault} field are
 * FakeCollector fault injections and are not replayed as such.
 */
public final class ContractVectors {

  /** Path of the vector file below the repository root. */
  public static final String RELATIVE_PATH =
      "agent/agent-testing/src/main/resources/contract/argos-v1-vectors.json";

  private ContractVectors() {}

  /** The vector file ({@code argos.repositoryRoot}, set by the Gradle test tasks). */
  public static Path file() {
    String root = System.getProperty("argos.repositoryRoot", "..");
    return Paths.get(root).resolve(RELATIVE_PATH).toAbsolutePath().normalize();
  }

  /** The whole document. */
  public static JsonNode load() {
    try {
      return JsonMapper.builder().build().readTree(Files.readString(file()));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the contract vectors " + file(), e);
    }
  }

  /** One section ({@code clock}, {@code dedup}, {@code session}, {@code status_codes}). */
  public static JsonNode section(String name) {
    JsonNode node = load().get(name);
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("No section " + name + " in " + file());
    }
    return node;
  }
}
