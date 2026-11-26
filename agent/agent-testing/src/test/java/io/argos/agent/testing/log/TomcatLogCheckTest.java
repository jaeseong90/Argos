/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tomcat log judgement of WAR runs (WO-05 r1-new-1): SEVERE records, WARNING records that name
 * the agent in their line or in their stack, Tomcat's ThreadLocal leak warnings; INFO, the sample
 * apps' own package, repository paths and the thread stacks of {@code clearReferencesThreads} do
 * not count.
 */
class TomcatLogCheckTest {
  private static final String T = "08-Nov-2026 06:10:09.101 ";

  private static final String THREAD_LOCAL_LEAK =
      T
          + "WARNING [main]"
          + " org.apache.catalina.loader.WebappClassLoaderBase.checkThreadLocalMapForLeaks The web"
          + " application [shop-a] created a ThreadLocal with key of type [java.lang.ThreadLocal]"
          + " (value [java.lang.ThreadLocal@1b2c3d4e]) and a value of type"
          + " [io.argos.agent.bootstrap.TraceContext] (value [x]) but failed to remove it when the"
          + " web application was stopped. Threads are going to be renewed over time to try and"
          + " avoid a probable memory leak.";

  @Test
  @DisplayName("a clean shutdown log: INFO with agent settings, thread warnings of the app only")
  void cleanLog() {
    List<String> lines =
        List.of(
            T
                + "INFO [main] org.apache.catalina.startup.VersionLoggerListener.log Command line"
                + " argument: -Dargos.log.dir=C:\\workspace\\Argos\\agent\\agent-testing\\logs",
            T
                + "INFO [main] org.apache.catalina.startup.VersionLoggerListener.log Command line"
                + " argument: -javaagent:C:\\run\\agent\\argos-agent.jar",
            T
                + "WARNING [main] org.apache.catalina.loader.WebappClassLoaderBase"
                + ".clearReferencesThreads The web application [shop-a] appears to have started a"
                + " thread named [HikariPool-1 housekeeper] but has failed to stop it. This is very"
                + " likely to create a memory leak. Stack trace of thread:",
            " java.base/jdk.internal.misc.Unsafe.park(Native Method)",
            " io.argos.agent.core.watch.Example.run(Example.java:1)",
            T
                + "WARNING [main]"
                + " org.apache.catalina.loader.WebappClassLoaderBase.clearReferencesThreads The web"
                + " application [shop-b] appears to have started a thread named"
                + " [sample-scheduler-1] of io.argos.sample.boot3.config.SampleExecutors but has"
                + " failed to stop it.",
            T
                + "WARNING [main] org.apache.tomcat.util.scan.StandardJarScanner.processURLs Failed"
                + " to scan [file:/C:/workspace/Argos/agent/agent-testing/build/x.jar] from"
                + " classloader hierarchy",
            "");
    TomcatLogCheck.Findings findings = TomcatLogCheck.judge(lines);
    assertThat(findings.clean()).as(findings.toString()).isTrue();
  }

  @Test
  @DisplayName("SEVERE records, agent WARNING records and ThreadLocal leak warnings are found")
  void findings() {
    String severe =
        T
            + "SEVERE [main] org.apache.catalina.core.StandardContext.startInternal One or more"
            + " listeners failed to start.";
    String agentThread =
        T
            + "WARNING [main] org.apache.catalina.loader.WebappClassLoaderBase"
            + ".clearReferencesThreads The web application [shop-a] appears to have started a"
            + " thread named [argos-watch] but has failed to stop it.";
    String agentClass =
        T
            + "WARNING [http-nio-127.0.0.1-8080-exec-1] org.apache.catalina.core.ApplicationContext"
            + ".log io.argos.agent.shaded.net.bytebuddy.Example failed";
    TomcatLogCheck.Findings findings =
        TomcatLogCheck.judge(List.of(severe, agentThread, agentClass, THREAD_LOCAL_LEAK));
    assertThat(findings.clean()).isFalse();
    assertThat(findings.severe()).containsExactly(severe);
    assertThat(findings.agentWarnings())
        .containsExactly(agentThread, agentClass, THREAD_LOCAL_LEAK);
    assertThat(findings.threadLocalLeaks()).containsExactly(THREAD_LOCAL_LEAK);
    assertThat(findings.toString())
        .contains("1 SEVERE", "3 WARNING naming the agent", "1 ThreadLocal leak warnings");
  }

  @Test
  @DisplayName(
      "an agent frame in the stack of a WARNING or SEVERE record names the agent; INFO stacks,"
          + " application stacks and clearReferencesThreads thread stacks do not")
  void agentFramesInStacks() {
    String servletError =
        T
            + "SEVERE [http-nio-127.0.0.1-8080-exec-3]"
            + " org.apache.catalina.core.StandardWrapperValve.invoke Servlet.service() for servlet"
            + " [dispatcherServlet] in context with path [/shop-a] threw exception";
    String agentFrame =
        "\tat io.argos.agent.instrumentation.servlet.ServletHelperJakarta.enter"
            + "(ServletHelperJakarta.java:60)";
    String listenerWarning =
        T
            + "WARNING [http-nio-127.0.0.1-8080-exec-4] org.apache.catalina.core.AsyncContextImpl"
            + ".onTimeout Failed to fire onTimeout event";
    String shadedCause =
        "Caused by: io.argos.agent.shaded.io.grpc.StatusRuntimeException: UNAVAILABLE";
    String appWarning =
        T + "WARNING [main] org.apache.catalina.core.StandardContext.x Application stack only";
    String unstoppedThread =
        T
            + "WARNING [main] org.apache.catalina.loader.WebappClassLoaderBase"
            + ".clearReferencesThreads The web application [shop-a] appears to have started a"
            + " thread named [task-1] but has failed to stop it. Stack trace of thread:";
    List<String> lines =
        List.of(
            T + "INFO [main] org.apache.catalina.startup.HostConfig.deployWAR Deploying",
            "\tat io.argos.agent.core.boot.AgentStarter.start(AgentStarter.java:1)",
            servletError,
            "java.lang.IllegalStateException: boom",
            "\tat io.argos.sample.boot3.web.DiagnosticsController.error500(X.java:1)",
            agentFrame,
            "\tat io.argos.agent.core.trace.TracerImpl.end(TracerImpl.java:2)",
            appWarning,
            "\tat io.argos.sample.boot3.web.DiagnosticsController.error500(X.java:1)",
            unstoppedThread,
            "\tat io.argos.agent.instrumentation.executor.Example.run(Example.java:1)",
            listenerWarning,
            "java.lang.IllegalStateException: wrapped",
            shadedCause);
    TomcatLogCheck.Findings findings = TomcatLogCheck.judge(lines);
    assertThat(findings.agentWarnings())
        .as("one entry per record, with the first agent frame")
        .containsExactly(
            servletError + " [stack: " + agentFrame.strip() + "]",
            listenerWarning + " [stack: " + shadedCause + "]");
    assertThat(findings.severe()).containsExactly(servletError);
    assertThat(findings.toString()).contains("2 WARNING naming the agent");
  }

  @Test
  @DisplayName("a ThreadLocal leak of the application itself is still a leak warning")
  void applicationThreadLocal() {
    String leak =
        THREAD_LOCAL_LEAK.replace(
            "io.argos.agent.bootstrap.TraceContext", "io.argos.sample.boot3.Holder");
    TomcatLogCheck.Findings findings = TomcatLogCheck.judge(List.of(leak));
    assertThat(findings.threadLocalLeaks()).containsExactly(leak);
    assertThat(findings.agentWarnings()).isEmpty();
  }

  @Test
  @DisplayName(
      "beyond(baseline): the application's own SEVERE leak of a run without the agent is excused"
          + " (other timestamp and hash codes); new records and agent warnings are kept")
  void beyondBaseline() {
    String appLeakWithAgent =
        "08-Nov-2026 06:44:00.997 SEVERE [main] org.apache.catalina.loader.WebappClassLoaderBase"
            + ".checkThreadLocalMapForLeaks The web application [shop-b] created a ThreadLocal with"
            + " key of type [java.lang.ThreadLocal] (value [java.lang.ThreadLocal@144f832e]) and a"
            + " value of type [io.netty.util.internal.InternalThreadLocalMap] (value"
            + " [io.netty.util.internal.InternalThreadLocalMap@23e3fdc8]) but failed to remove it"
            + " when the web application was stopped.";
    String appLeakWithoutAgent =
        appLeakWithAgent
            .replace("06:44:00.997", "07:01:12.003")
            .replace("@144f832e", "@5c2fe627")
            .replace("@23e3fdc8", "@b4867bc");
    String agentLeak =
        appLeakWithAgent.replace(
            "io.netty.util.internal.InternalThreadLocalMap]", "io.argos.agent.bootstrap.Scope]");
    String newSevere = T + "SEVERE [main] org.apache.catalina.core.StandardContext.x Failed";
    TomcatLogCheck.Findings withAgent =
        TomcatLogCheck.judge(List.of(appLeakWithAgent, agentLeak, newSevere));
    TomcatLogCheck.Findings withoutAgent =
        TomcatLogCheck.judge(List.of(appLeakWithoutAgent, agentLeak));
    assertThat(withAgent.agentWarnings()).containsExactly(agentLeak);
    TomcatLogCheck.Findings added = withAgent.beyond(withoutAgent);
    assertThat(added.severe()).containsExactly(newSevere);
    assertThat(added.threadLocalLeaks()).isEmpty();
    assertThat(added.agentWarnings()).as("never excused").containsExactly(agentLeak);
    assertThat(TomcatLogCheck.judge(List.of(appLeakWithAgent)).beyond(withoutAgent).clean())
        .isTrue();
    assertThat(TomcatLogCheck.normalized(appLeakWithAgent))
        .isEqualTo(TomcatLogCheck.normalized(appLeakWithoutAgent))
        .startsWith("SEVERE [*] org.apache.catalina.loader.WebappClassLoaderBase");
  }

  @Test
  @DisplayName(
      "beyond(baseline) across manager undeploys (WO-50): the same leak report on request threads"
          + " of another port and pool number is excused; another application's report is not")
  void beyondBaselineOnRequestThreads() {
    String leak =
        "26-Nov-2026 13:10:00.461 SEVERE [http-nio-127.0.0.1-61476-exec-8]"
            + " org.apache.catalina.loader.WebappClassLoaderBase.checkThreadLocalMapForLeaks The"
            + " web application [shop-a] created a ThreadLocal with key of type"
            + " [java.lang.ThreadLocal] (value [java.lang.ThreadLocal@6019a181]) and a value of"
            + " type [io.netty.util.internal.InternalThreadLocalMap] (value"
            + " [io.netty.util.internal.InternalThreadLocalMap@2f08bbcc]) but failed to remove it"
            + " when the web application was stopped.";
    String withoutAgentLeak =
        leak.replace("13:10:00.461", "13:35:32.336")
            .replace("61476-exec-8", "60716-exec-6")
            .replace("@6019a181", "@5882952b")
            .replace("@2f08bbcc", "@7cc74d19");
    String otherApp = withoutAgentLeak.replace("[shop-a]", "[shop-c]");
    TomcatLogCheck.Findings withAgent = TomcatLogCheck.judge(List.of(leak));
    assertThat(withAgent.beyond(TomcatLogCheck.judge(List.of(withoutAgentLeak))).clean()).isTrue();
    assertThat(withAgent.beyond(TomcatLogCheck.judge(List.of(otherApp))).severe())
        .containsExactly(leak);
    assertThat(TomcatLogCheck.normalized(leak))
        .startsWith("SEVERE [*] org.apache.catalina.loader.WebappClassLoaderBase")
        .doesNotContain("61476");
  }

  @Test
  @DisplayName("record lines and level names: continuation lines have no level; localized names")
  void levels() {
    assertThat(TomcatLogCheck.level(T + "INFO [main] x")).isEqualTo("INFO");
    assertThat(TomcatLogCheck.level("08-Nov-2026 6:10:09.1 SEVERE [main] x")).isEqualTo("SEVERE");
    assertThat(TomcatLogCheck.level(" at x.y(Z.java:1)")).isNull();
    assertThat(TomcatLogCheck.level("SEVERE: plain text")).isNull();
    String localized =
        "08-Nov-2026 06:10:09.101 " + Level.SEVERE.getLocalizedName() + " [main] x failed";
    assertThat(TomcatLogCheck.judge(List.of(localized)).severe()).containsExactly(localized);
  }

  @Test
  @DisplayName("agent mentions: packages, jar, threads, settings, prefix; not the sample package")
  void agentMentions() {
    for (String text :
        List.of(
            "io.argos.agent.core.X",
            "io/argos/agent/bootstrap/Argos",
            "argos-agent.jar",
            "thread [argos-io]",
            "-Dargos.capture.remote-ip=full",
            "argos: agent disabled")) {
      assertThat(TomcatLogCheck.mentionsAgent(text)).as(text).isTrue();
    }
    for (String text :
        List.of(
            "io.argos.sample.boot3.ShopBoot3Application",
            "C:\\workspace\\Argos\\agent\\agent-testing",
            "/home/ci/Argos/agent/build",
            "shop-war-jakarta")) {
      assertThat(TomcatLogCheck.mentionsAgent(text)).as(text).isFalse();
    }
  }
}
