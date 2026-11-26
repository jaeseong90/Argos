/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.matrix.MatrixRuns.Combo;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * A real JSP forward in the row's WAR app (ADR-0010 C-162 (2), proposed detailed ID T-AG-012j,
 * D-04, F-A28): {@code GET <context>/api/jsp-forward} forwards to {@code /WEB-INF/jsp/forward.jsp},
 * which the staged Tomcat's Jasper compiles with the distribution's JDT compiler ({@code
 * lib/ecj-*.jar}, test-only, docs/licenses/dev-only.md) and runs: {@value #REPEAT} requests are
 * {@value #REPEAT} transactions with status 200 named after the original request ({@code <context>
 * GET /api/jsp-forward}: a FORWARD dispatch never renames, D-04) and {@code dispatch_count} 2 (the
 * REQUEST and its FORWARD; Jasper's {@code JspServlet} handing the request to the compiled page
 * counts no third dispatch), no {@code /error} transaction, the compiled page class in Tomcat's
 * work directory, no agent ERROR. Without the JDT compiler in the distribution it is skipped with
 * the reason.
 */
@Tag("req:D-04")
@Tag("req:F-A28")
@Tag("req:F-A36")
class JspForwardTest {
  static final int REPEAT = 10;
  static final String PATH = "/api/jsp-forward";

  @TestFactory
  Stream<DynamicTest> jspForward() {
    Combo combo = MatrixRuns.firstWarCombo();
    return Stream.of(
        DynamicTest.dynamicTest(
            MatrixRuns.comboId()
                + " T-AG-012j/D-04 "
                + combo
                + ": a forward to a JSP compiled by Jasper is one transaction named after the"
                + " request, dispatch_count 2",
            () -> run(combo)));
  }

  void run(Combo combo) throws Exception {
    SampleApp sample = combo.sampleApp();
    AppLauncher launcher = AppLauncher.of(sample, Jdk.of(combo.jdk()));
    String tomcat = launcher.tomcatKey();
    Path home = sample.tomcatHome(tomcat);
    Assumptions.assumeTrue(
        hasJdtCompiler(home),
        "the staged Tomcat "
            + tomcat
            + " has no JDT compiler (lib/ecj-*.jar) for Jasper; the sample-app staging keeps it for"
            + " WO-50 (C-162 (2))");
    try (MatrixRuns.TempTree tree = MatrixRuns.TempTree.create("mx-jsp-");
        ExtStub stub = MatrixRuns.startExtStub()) {
      Path work = tree.root();
      RunningApp app =
          launcher
              .withEnv("SAMPLE_EXT_URL", stub.baseUri().toString())
              .workDir(work.resolve("jsp-" + combo.app() + "-" + combo.jdk()))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(MatrixRuns.releaseJar())
              .start();
      try {
        String context = app.app().contextPaths().get(0);
        Predicate<TransactionRecord> forward =
            tx -> tx.path() != null && MatrixRuns.bare(tx.path()).endsWith(PATH);
        HttpResult first = app.get(context + PATH);
        assertThat(first.status())
            .as("GET %s%s (lane C sample endpoint): %s", context, PATH, first.body())
            .isEqualTo(200);
        for (int i = 1; i < REPEAT; i++) {
          assertThat(app.get(context + PATH).status()).isEqualTo(200);
        }
        app.transactions().await(forward, REPEAT, MatrixRuns.WAIT);
        Thread.sleep(1_000);
        List<TransactionRecord> txs = app.transactions().read(forward);
        assertThat(txs).as("one transaction per request%n%s", app.describe()).hasSize(REPEAT);
        for (TransactionRecord tx : txs) {
          assertThat(tx.service()).as("D-04: %s", tx).isEqualTo(context + " GET " + PATH);
          assertThat(tx.status()).isEqualTo(200);
          assertThat(tx.error()).isFalse();
          assertThat(tx.dispatchCount()).as("F-A28 dispatch_count: %s", tx).isEqualTo(2);
        }
        assertThat(app.transactions().read()).noneMatch(MatrixRuns::isErrorTransaction);
        Path compiled = compiledPage(app.workDir().resolve("catalina-base").resolve("work"));
        assertThat(compiled)
            .as("forward_jsp.class compiled by Jasper in Tomcat's work directory")
            .isNotNull();
        assertThatAgentLog(app.agentLog()).hasStartBanner().hasNoErrors();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("app", combo.app());
        values.put("jdk", combo.jdk());
        values.put("tomcat", tomcat);
        values.put("requests", REPEAT);
        values.put("transactions", txs.size());
        values.put("dispatch_count", txs.get(0).dispatchCount());
        MatrixRuns.result("jsp-forward", values);
      } finally {
        app.close();
        MatrixRuns.keepEvidence("jsp-" + combo.app() + "-" + combo.jdk(), app);
      }
    }
  }

  /** True when {@code home/lib} has the JDT compiler that Jasper uses ({@code ecj-*.jar}). */
  static boolean hasJdtCompiler(Path home) throws IOException {
    try (Stream<Path> files = Files.list(home.resolve("lib"))) {
      return files.anyMatch(f -> f.getFileName().toString().matches("ecj-.*\\.jar"));
    }
  }

  /** The compiled page class of {@code forward.jsp} under {@code work}, or null. */
  static Path compiledPage(Path work) throws IOException {
    if (!Files.isDirectory(work)) {
      return null;
    }
    try (Stream<Path> files = Files.walk(work)) {
      return files
          .filter(f -> f.getFileName().toString().equals("forward_jsp.class"))
          .findFirst()
          .orElse(null);
    }
  }
}
