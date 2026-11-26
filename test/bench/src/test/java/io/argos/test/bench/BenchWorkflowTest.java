/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The weekly bench workflow {@code .github/workflows/bench.yml} (18 §7, 19 §6.1, §6.4 and §6.6,
 * WO-51). The file is read as text with a small indentation reader (no YAML library in the
 * harness); {@code check-workflows.sh} and actionlint check the rest of the policy and the syntax.
 */
@Tag("req:DSN-18-07")
class BenchWorkflowTest {

  private static final Pattern PINNED_USES =
      Pattern.compile("^(?:- )?uses: [A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40} # v[0-9].*$");

  private static List<String> workflow;

  @BeforeAll
  static void read() throws IOException {
    Path repo =
        Path.of(System.getProperty("argos.repositoryRoot", "../..")).toAbsolutePath().normalize();
    workflow =
        Files.readString(repo.resolve(".github/workflows/bench.yml"), StandardCharsets.UTF_8)
            .lines()
            .toList();
  }

  @Test
  @DisplayName("T-BLD-015i triggers are exactly schedule and workflow_dispatch")
  void triggers() {
    List<String> on = under(workflow, "on");
    assertThat(keys(on)).containsExactly("schedule", "workflow_dispatch");
    assertThat(under(on, "schedule")).anySatisfy(l -> assertThat(l.trim()).startsWith("- cron:"));
    // No pull_request (any form), workflow_run or workflow_call anywhere: fork code must never
    // reach the self-hosted runner (O-S05, 19 §6.6).
    Pattern forbidden =
        Pattern.compile("^\\s*-?\\s*\"?(pull_request\\w*|workflow_run|workflow_call)\"?\\s*:");
    assertThat(workflow).noneMatch(l -> forbidden.matcher(l).find());
    assertThat(workflow).noneMatch(l -> !isComment(l) && l.contains("pull_request"));
  }

  @Test
  @DisplayName("T-BLD-015i manual inputs default to the weekly sets main,startup and exporter noop")
  void inputs() {
    List<String> in = under(under(under(workflow, "on"), "workflow_dispatch"), "inputs");
    assertThat(keys(in)).containsExactly("sets", "exporter", "collector");
    assertThat(value(under(in, "sets"), "default")).isEqualTo("main,startup");
    assertThat(value(under(in, "exporter"), "default")).isEqualTo("noop");
    assertThat(value(under(in, "collector"), "default")).isEqualTo("\"\"");
    // A scheduled run has no inputs: the same defaults are spelled in the env of the protocol step.
    assertThat(String.join("\n", benchSteps()))
        .contains("SETS: ${{ inputs.sets || 'main,startup' }}")
        .contains("EXPORTER: ${{ inputs.exporter || 'noop' }}");
  }

  @Test
  @DisplayName("T-BLD-015i read-only token by default and one bench run at a time")
  void permissionsAndConcurrency() {
    assertThat(trimmed(under(workflow, "permissions"))).containsExactly("contents: read");
    List<String> concurrency = under(workflow, "concurrency");
    assertThat(value(concurrency, "group")).isEqualTo("bench");
    assertThat(value(concurrency, "cancel-in-progress")).isEqualTo("false");
    assertThat(workflow).noneMatch(l -> l.contains("write-all"));
  }

  @Test
  @DisplayName("T-BLD-015i the bench job runs on the self-hosted argos-bench-app runner, read-only")
  void benchJob() {
    List<String> bench = under(under(workflow, "jobs"), "bench");
    assertThat(value(bench, "runs-on")).isEqualTo("[self-hosted, linux, argos-bench-app]");
    assertThat(value(bench, "timeout-minutes")).isEqualTo("420");
    assertThat(keys(bench)).doesNotContain("permissions");
    assertThat(value(bench, "if"))
        .isEqualTo(
            "github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/')");
  }

  @Test
  @DisplayName("T-BLD-015i build, then --stop, then the protocol with --no-build, auto baseline")
  void benchStepOrder() {
    List<String> steps = benchSteps();
    int build = indexOf(steps, ":test:bench:benchJar");
    int stop = indexOf(steps, "./gradlew --stop");
    int protocol = indexOf(steps, "test/bench/bench-protocol.sh \"${args[@]}\"");
    assertThat(build).isLessThan(stop);
    assertThat(stop).isLessThan(protocol);
    String buildStep = String.join("\n", steps.subList(build - 1, build + 2));
    assertThat(buildStep)
        .contains("-Pargos.ui.skip=true")
        .contains(":test:bench:benchJar")
        .contains(":agent:agent-dist:agentJar")
        .contains(":sample-apps:ext-stub:sampleAppJar")
        .contains(":sample-apps:shop-boot3:sampleAppJar")
        .contains(":sample-apps:shop-heavy:sampleAppJar");
    String args = String.join("\n", steps.subList(indexOf(steps, "args=(--no-build"), protocol));
    assertThat(args)
        .contains("--no-build")
        .contains("--machine-profile argos-bench-app")
        .contains("--sets \"$SETS\"")
        .contains("--loader \"$LOADER\"")
        .contains("--app-address \"$APP_ADDR\"")
        .contains("--baseline auto")
        .contains("--latest");
    // No Gradle invocation after the daemon stopped (no daemon during the measurement).
    assertThat(steps.subList(stop + 1, steps.size())).noneMatch(l -> l.contains("./gradlew"));
    // Exit codes 3 (FAIL) and 4 (INVALID) are results; 1 and 2 fail the job.
    assertThat(String.join("\n", steps)).contains("0 | 3 | 4)");
    // The report is uploaded whatever the verdict.
    int upload = indexOf(steps, "name: bench-report");
    assertThat(steps.subList(upload - 4, upload)).anyMatch(l -> l.trim().equals("if: always()"));
  }

  @Test
  @DisplayName("T-BLD-015i every action is pinned to a commit SHA with a version comment")
  void pinnedActions() {
    List<String> uses = workflow.stream().filter(l -> l.trim().matches("^(- )?uses:.*")).toList();
    assertThat(uses).hasSizeGreaterThanOrEqualTo(5);
    assertThat(uses).allSatisfy(l -> assertThat(l.trim()).matches(PINNED_USES));
  }

  @Test
  @DisplayName("T-BLD-015i only the GitHub-hosted publish job has write scopes")
  void writeScopes() {
    List<String> jobs = under(workflow, "jobs");
    assertThat(keys(jobs)).containsExactly("bench", "publish");
    for (String job : keys(jobs)) {
      List<String> lines = under(jobs, job);
      boolean writes = lines.stream().anyMatch(l -> l.trim().matches("^[a-z-]+: write$"));
      assertThat(writes).as(job).isEqualTo(job.equals("publish"));
    }
    List<String> publish = under(jobs, "publish");
    assertThat(value(publish, "runs-on")).isEqualTo("ubuntu-latest");
    assertThat(value(publish, "needs")).isEqualTo("bench");
    assertThat(trimmed(under(publish, "permissions")))
        .containsExactly("contents: write", "pull-requests: write");
    // The publish job executes no repository code: git, gh and jq only.
    Pattern code = Pattern.compile("\\./gradlew|\\.sh\\b|\\bbash\\s|\\bnode\\s|\\bjava\\s|pnpm");
    assertThat(runScripts(publish)).noneMatch(l -> code.matcher(l).find());
    assertThat(String.join("\n", publish))
        .contains("git commit -s")
        .contains("bench/weekly-$REPORT")
        .contains("gh pr create")
        .contains("files outside docs/bench");
  }

  @Test
  @DisplayName("T-BLD-015i inputs and variables reach run scripts through env only")
  void noExpressionsInRunScripts() {
    List<String> jobs = under(workflow, "jobs");
    List<String> scripts = new ArrayList<>();
    for (String job : keys(jobs)) {
      scripts.addAll(runScripts(under(jobs, job)));
    }
    assertThat(scripts).isNotEmpty();
    assertThat(scripts).noneMatch(l -> l.contains("${{"));
    assertThat(workflow).noneMatch(l -> l.contains("secrets."));
  }

  // ---- a small indentation reader -------------------------------------------------------------

  private static List<String> benchSteps() {
    return under(under(under(workflow, "jobs"), "bench"), "steps");
  }

  private static boolean isComment(String line) {
    String t = line.trim();
    return t.isEmpty() || t.startsWith("#");
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  /** Indentation of the direct children of a block: its first non-comment line. */
  private static int base(List<String> block) {
    return block.stream()
        .filter(l -> !isComment(l))
        .findFirst()
        .map(BenchWorkflowTest::indent)
        .orElseThrow();
  }

  /** The lines of the value of {@code key}, a direct child of {@code block}. */
  private static List<String> under(List<String> block, String key) {
    int base = base(block);
    for (int i = 0; i < block.size(); i++) {
      String l = block.get(i);
      if (!isComment(l) && indent(l) == base && l.trim().startsWith(key + ":")) {
        List<String> out = new ArrayList<>();
        for (int j = i + 1; j < block.size(); j++) {
          String c = block.get(j);
          if (!isComment(c) && indent(c) <= base) {
            break;
          }
          out.add(c);
        }
        return out;
      }
    }
    throw new AssertionError("no key '" + key + "' in " + block);
  }

  /** Direct child keys of a block, in order. */
  private static List<String> keys(List<String> block) {
    int base = base(block);
    Pattern key = Pattern.compile("^\"?([A-Za-z0-9_-]+)\"?:");
    List<String> out = new ArrayList<>();
    for (String l : block) {
      if (!isComment(l) && indent(l) == base) {
        Matcher m = key.matcher(l.trim());
        if (m.find()) {
          out.add(m.group(1));
        }
      }
    }
    return out;
  }

  /** The inline value of {@code key}, a direct child of {@code block}. */
  private static String value(List<String> block, String key) {
    int base = base(block);
    for (String l : block) {
      if (!isComment(l) && indent(l) == base && l.trim().startsWith(key + ":")) {
        return l.trim().substring(key.length() + 1).trim();
      }
    }
    throw new AssertionError("no key '" + key + "' in " + block);
  }

  private static List<String> trimmed(List<String> block) {
    return block.stream().filter(l -> !isComment(l)).map(String::trim).toList();
  }

  private static int indexOf(List<String> lines, String text) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(text)) {
        return i;
      }
    }
    throw new AssertionError("no line with '" + text + "'");
  }

  /** The script lines of every {@code run:} key of a job (inline and block scalars). */
  private static List<String> runScripts(List<String> job) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < job.size(); i++) {
      String t = job.get(i).trim();
      if (!t.startsWith("run:") && !t.startsWith("- run:")) {
        continue;
      }
      String inline = t.substring(t.indexOf("run:") + 4).trim();
      if (!inline.equals("|") && !inline.equals(">")) {
        out.add(inline);
        continue;
      }
      int keyIndent = indent(job.get(i));
      for (int j = i + 1; j < job.size(); j++) {
        String c = job.get(j);
        if (!c.isBlank() && indent(c) <= keyIndent) {
          break;
        }
        out.add(c);
      }
    }
    return out;
  }
}
