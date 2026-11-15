/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-API-002 (O-R03, 14 §2.1, §2.9): the compatibility check of CI ({@code
 * .github/scripts/check-openapi-diff.sh}, oasdiff pinned by digest) on copies of the committed
 * document in a throwaway git repository: a removed required response property and a changed
 * property type fail, an added optional property passes. The script answers exit 1 also for an
 * invalid spec or a failed oasdiff run, so a failure must name the oasdiff rule id of the change
 * and a pass must say "no breaking changes" without an error.
 *
 * <p>Runs the pinned oasdiff container, or {@code OASDIFF_BIN} when set; skipped with the reason
 * when neither Docker nor the binary is available (CI has Docker). bash: {@code ARGOS_BASH}, Git's
 * bash on Windows, else {@code bash}.
 *
 * <p>oasdiff treats the removal of an optional response property as compatible (no error, no
 * warning): the response properties that are always present are therefore marked {@code required}
 * in the document, so that their removal is caught.
 */
@Tag("req:O-R03")
class OpenApiDiffContractTest {

  private static final Path SCRIPT =
      OpenApiDocument.repositoryRoot().resolve(".github/scripts/check-openapi-diff.sh");

  private static String bash;
  private static String spec;

  @TempDir Path work;

  @BeforeAll
  static void prerequisites() throws Exception {
    bash = bash();
    Assumptions.assumeTrue(bash != null, "no bash to run check-openapi-diff.sh");
    String oasdiff = System.getenv("OASDIFF_BIN");
    boolean docker = run(List.of("docker", "info"), null, Map.of()).code() == 0;
    Assumptions.assumeTrue(
        (oasdiff != null && !oasdiff.isBlank()) || docker,
        "neither Docker (for the pinned oasdiff image) nor OASDIFF_BIN is available");
    spec = OpenApiDocument.readCommitted();
    if (spec == null) {
      spec = OpenApiDocument.generate();
    }
  }

  @Test
  @DisplayName("T-API-002 a removed required response property fails check-openapi-diff.sh")
  void removedResponseField() throws Exception {
    JsonNode changed = OpenApiDocument.readYaml(spec);
    ObjectNode detail = detailSchema(changed);
    assertThat(detail.path("properties").has("elapsed_ms")).isTrue();
    ((ObjectNode) detail.path("properties")).remove("elapsed_ms");
    ArrayNode required = (ArrayNode) detail.path("required");
    for (int i = required.size() - 1; i >= 0; i--) {
      if (required.get(i).asString().equals("elapsed_ms")) {
        required.remove(i);
      }
    }
    assertBreaking(diff(OpenApiDocument.render(changed)), "response-required-property-removed");
  }

  @Test
  @DisplayName("T-API-002 a changed property type fails check-openapi-diff.sh")
  void changedType() throws Exception {
    JsonNode changed = OpenApiDocument.readYaml(spec);
    ObjectNode property = (ObjectNode) detailSchema(changed).path("properties").path("elapsed_ms");
    property.removeAll();
    property.put("type", "string");
    assertBreaking(diff(OpenApiDocument.render(changed)), "response-property-type-changed");
  }

  @Test
  @DisplayName("T-API-002 an added optional response property passes check-openapi-diff.sh")
  void addedOptionalField() throws Exception {
    JsonNode changed = OpenApiDocument.readYaml(spec);
    ((ObjectNode) detailSchema(changed).path("properties"))
        .putObject("added_for_t_api_002")
        .put("type", "string");
    assertCompatible(diff(OpenApiDocument.render(changed)));
  }

  @Test
  @DisplayName("T-API-002 the unchanged document passes check-openapi-diff.sh")
  void unchanged() throws Exception {
    assertCompatible(diff(spec));
  }

  @Test
  @DisplayName(
      "T-API-002 removing QueryMeta.request_id (the envelope meta of every operation) fails"
          + " check-openapi-diff.sh")
  void removedEnvelopeMetaField() throws Exception {
    JsonNode changed = OpenApiDocument.readYaml(spec);
    removeProperty(changed, "QueryMeta", "request_id");
    assertBreaking(diff(OpenApiDocument.render(changed)), "response-required-property-removed");
  }

  @Test
  @DisplayName(
      "T-API-002 removing ServiceStats.p95_ms (a nullable statistics value) fails"
          + " check-openapi-diff.sh")
  void removedStatisticsField() throws Exception {
    JsonNode changed = OpenApiDocument.readYaml(spec);
    removeProperty(changed, "ServiceStats", "p95_ms");
    assertBreaking(diff(OpenApiDocument.render(changed)), "response-required-property-removed");
  }

  /**
   * Removes {@code property} from the component schema {@code schema} and from its {@code required}
   * list, asserting it was there and required (the generated document marks every always-written
   * property required: ArgosRecordSchemaConverter).
   */
  private static void removeProperty(JsonNode document, String schema, String property) {
    JsonNode node = document.path("components").path("schemas").path(schema);
    assertThat(node.isObject()).as(schema + " schema").isTrue();
    assertThat(node.path("properties").has(property)).as(schema + "." + property).isTrue();
    ((ObjectNode) node.path("properties")).remove(property);
    ArrayNode required = (ArrayNode) node.path("required");
    boolean found = false;
    for (int i = required.size() - 1; i >= 0; i--) {
      if (required.get(i).asString().equals(property)) {
        required.remove(i);
        found = true;
      }
    }
    assertThat(found).as(schema + "." + property + " is required").isTrue();
  }

  /**
   * The exit code and the combined output of a command.
   *
   * @param code exit code; -1 when it could not start, -2 on a time-out
   * @param output stdout and stderr
   */
  record Result(int code, String output) {}

  /**
   * Exit 1 because oasdiff found the breaking change {@code rule} (its rule id in the report and
   * the script's breaking-change message), not because a spec was invalid or oasdiff failed (which
   * the script also reports as exit 1).
   */
  private static void assertBreaking(Result result, String rule) {
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output())
        .as("oasdiff rule id in the report")
        .contains("[" + rule + "]")
        .contains("check-openapi-diff: breaking API changes found")
        .doesNotContain("oasdiff failed")
        .doesNotContain("invalid spec");
  }

  /** Exit 0 with the script's "no breaking changes" message and no error in the output. */
  private static void assertCompatible(Result result) {
    assertThat(result.code()).as(result.output()).isZero();
    assertThat(result.output())
        .contains("check-openapi-diff: no breaking changes")
        .doesNotContain("breaking API changes found")
        .doesNotContain("oasdiff failed")
        .doesNotContainIgnoringCase("error");
  }

  private static ObjectNode detailSchema(JsonNode document) {
    JsonNode detail = document.path("components").path("schemas").path("TransactionDetail");
    assertThat(detail.isObject()).as("TransactionDetail schema").isTrue();
    return (ObjectNode) detail;
  }

  /** Commits {@link #spec} as the base, puts {@code head} in the tree, runs the script. */
  private Result diff(String head) throws Exception {
    Path repo = work.resolve("repo");
    Files.createDirectories(repo.resolve("docs/api"));
    Path gitConfig = work.resolve("gitconfig");
    Files.writeString(gitConfig, "");
    Map<String, String> env =
        Map.of(
            "GIT_CONFIG_GLOBAL", gitConfig.toString(),
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_AUTHOR_NAME", "Contract Test",
            "GIT_AUTHOR_EMAIL", "contract@example.org",
            "GIT_COMMITTER_NAME", "Contract Test",
            "GIT_COMMITTER_EMAIL", "contract@example.org");
    Path file = repo.resolve(OpenApiDocument.COMMITTED);
    Files.writeString(file, spec, StandardCharsets.UTF_8);
    assertThat(run(List.of("git", "init", "-q", "-b", "main"), repo, env).code()).isZero();
    assertThat(run(List.of("git", "add", "."), repo, env).code()).isZero();
    assertThat(run(List.of("git", "commit", "-q", "-m", "base"), repo, env).code()).isZero();
    Files.writeString(file, head, StandardCharsets.UTF_8);
    List<String> command = new ArrayList<>();
    command.add(bash);
    command.add(SCRIPT.toAbsolutePath().toString().replace('\\', '/'));
    command.add("HEAD");
    return run(command, repo, env);
  }

  private static Result run(List<String> command, Path directory, Map<String, String> env)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    if (directory != null) {
      pb.directory(directory.toFile());
    }
    pb.environment().putAll(env);
    pb.environment().remove("BREAKING_CHANGE");
    pb.environment().remove("OPENAPI_SPEC");
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      return new Result(-1, String.valueOf(e.getMessage()));
    }
    byte[] output = process.getInputStream().readAllBytes();
    String text = new String(output, StandardCharsets.UTF_8);
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      return new Result(-2, text);
    }
    if (command.get(0).equals(bash)) {
      System.out.println("check-openapi-diff.sh: " + text.strip());
    }
    return new Result(process.exitValue(), text);
  }

  /** {@code ARGOS_BASH}, Git's bash on Windows, else {@code bash} on the path; null when none. */
  private static String bash() {
    String configured = System.getenv("ARGOS_BASH");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      for (String candidate :
          List.of(
              "C:\\Program Files\\Git\\bin\\bash.exe",
              "C:\\Program Files (x86)\\Git\\bin\\bash.exe")) {
        if (new File(candidate).isFile()) {
          return candidate;
        }
      }
      // Not the bash.exe of WSL in System32: it cannot run this repository's scripts as is.
      return null;
    }
    return "bash";
  }
}
