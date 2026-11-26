/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import io.argos.agent.testing.jvm.ClassHistogram;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.TomcatStaging;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The WAR redeploy part of 18 §5.9 (T-AG-053, T-MX): deploys and undeploys a WAR through the Tomcat
 * manager's text interface {@code cycles} times, then the caller runs {@code GC.run} twice and
 * checks {@link #webappLoaders} (1: only the current deployment's {@code
 * ParallelWebappClassLoader}) and {@link #threadLocalLeakWarnings} (0) in the Tomcat log.
 *
 * <p>The redeploy cycles need Tomcat's manager app (role {@code manager-script}): the launcher's
 * WAR mode stages it on request ({@code AppLauncher.tomcatManager(true)}, {@code TomcatStaging},
 * random password per run, 127.0.0.1 only), and {@link #of(RunningApp)} gives the HTTP {@link
 * Manager} of such a run (the matrix's {@code WarRedeployTest}, WO-50). {@link
 * #threadLocalLeakWarnings} is also used on every WAR run of the launcher: {@code
 * log.TomcatLogCheck} judges the staged Tomcat's log after its graceful stop ({@code
 * WarAppAgentTest}, WO-05 r1-new-1).
 */
public final class TomcatRedeployDriver {
  /** Class of a webapp's class loader on Tomcat 9, 10.1 and 11. */
  public static final String WEBAPP_LOADER = "org.apache.catalina.loader.ParallelWebappClassLoader";

  private static final Pattern THREAD_LOCAL_LEAK =
      Pattern.compile(".*ThreadLocal.*(failed to remove|memory leak).*");

  /** The manager's text interface: {@code GET <manager>/text/<command>}, returns the body. */
  @FunctionalInterface
  public interface Manager {
    String get(String commandAndQuery) throws IOException, InterruptedException;
  }

  /**
   * One redeploy run.
   *
   * @param deploys successful deploy commands
   * @param undeploys successful undeploy commands
   * @param responses every manager response in order
   */
  public record Result(int deploys, int undeploys, List<String> responses) {
    public Result {
      responses = List.copyOf(responses);
    }
  }

  private final Manager manager;

  public TomcatRedeployDriver(Manager manager) {
    this.manager = manager;
  }

  /**
   * The manager at {@code managerBase} ({@code http://127.0.0.1:8080/manager}) with basic auth: one
   * {@code GET <managerBase>/text/<command>} per call, the body of a 200 answer; any other status
   * makes {@link Manager#get} throw an {@link IOException} that names the status and the command,
   * never the credentials or the query.
   */
  public static Manager http(URI managerBase, String user, String password) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    return command -> {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(managerBase + "/text/" + command))
              .timeout(Duration.ofMinutes(2))
              .header("Authorization", auth)
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        String name = command.contains("?") ? command.substring(0, command.indexOf('?')) : command;
        throw new IOException(
            "Tomcat manager answered HTTP " + response.statusCode() + " to " + name);
      }
      return response.body();
    };
  }

  /**
   * The HTTP manager of a WAR run started with {@code AppLauncher.tomcatManager(true)} ({@code
   * <baseUrl>/manager}, the run's {@code manager-script} user).
   *
   * @throws IllegalStateException when the run has no manager app
   */
  public static Manager of(RunningApp app) {
    TomcatStaging.ManagerAccess access =
        app.tomcatManager()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        app.app().name()
                            + " runs without the Tomcat manager app (AppLauncher.tomcatManager)"));
    return http(
        URI.create(app.baseUrl() + "/" + TomcatStaging.MANAGER_CONTEXT),
        access.user(),
        access.password());
  }

  /**
   * {@code cycles} times: undeploy {@code contextPath} (when deployed), deploy {@code war} there.
   * The application stays deployed at the end.
   *
   * @throws IllegalStateException when the manager answers anything but {@code OK - ...}
   */
  public Result redeploy(String contextPath, Path war, int cycles)
      throws IOException, InterruptedException {
    String path = URLEncoder.encode(contextPath, StandardCharsets.UTF_8);
    String warUrl = URLEncoder.encode(war.toUri().toString(), StandardCharsets.UTF_8);
    List<String> responses = new ArrayList<>();
    int deploys = 0;
    int undeploys = 0;
    for (int cycle = 0; cycle < cycles; cycle++) {
      String list = manager.get("list");
      responses.add(list);
      if (deployed(list, contextPath)) {
        String undeploy = manager.get("undeploy?path=" + path);
        responses.add(undeploy);
        requireOk(undeploy, "undeploy " + contextPath);
        undeploys++;
      }
      String deploy = manager.get("deploy?path=" + path + "&war=" + warUrl + "&update=true");
      responses.add(deploy);
      requireOk(deploy, "deploy " + war + " at " + contextPath);
      deploys++;
    }
    return new Result(deploys, undeploys, responses);
  }

  /** True when the manager's {@code list} output names {@code contextPath} as running. */
  static boolean deployed(String list, String contextPath) {
    for (String line : list.split("\\R", -1)) {
      if (line.startsWith(contextPath + ":running:")) {
        return true;
      }
    }
    return false;
  }

  private static void requireOk(String response, String what) {
    if (!response.startsWith("OK - ")) {
      throw new IllegalStateException(what + " failed: " + response.strip());
    }
  }

  /** {@value #WEBAPP_LOADER} instances in a histogram (18 §5.9: 1 after the cycles). */
  public static long webappLoaders(ClassHistogram histogram) {
    return histogram.instancesOfClass(WEBAPP_LOADER);
  }

  /** Tomcat's ThreadLocal leak warnings ({@code WebappClassLoaderBase}) in log lines (18: 0). */
  public static List<String> threadLocalLeakWarnings(List<String> tomcatLog) {
    List<String> warnings = new ArrayList<>();
    for (String line : tomcatLog) {
      if (THREAD_LOCAL_LEAK.matcher(line).matches()) {
        warnings.add(line);
      }
    }
    return warnings;
  }
}
