/* SPDX-License-Identifier: Apache-2.0 */
package example.logging;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

/**
 * Spring-free logging fixture of the WO-18 logging suite (agent-testing, test-only, never shipped):
 * an embedded Tomcat (jakarta) with one servlet that logs through Log4j 2, Log4j 1.2 or reload4j
 * (the first program argument, {@code log4j2} or {@code log4j1}; the class path of the jar decides
 * Log4j 1.2.17 or reload4j). Log lines go to standard output with the pattern {@code fixture
 * %X{argos.trace_id}|%X{argos.tx_id}|%X{user}|%m}.
 *
 * <ul>
 *   <li>{@code GET /}: 200, nothing logged (readiness).
 *   <li>{@code GET /log?tag=<t>[&clear=true][&async=true][&user=<id>]}: one log line {@code
 *       fixture-log mode=<plain|clear|async> tag=<t>}; {@code clear} clears the MDC first, {@code
 *       async} logs from an application thread pool (a task the agent propagates), {@code user}
 *       puts the {@code user} MDC key for the line.
 * </ul>
 *
 * <p>The port and address come from {@code SERVER_PORT} and {@code SERVER_ADDRESS} (AppLauncher).
 * One line is logged at start, before any request (no transaction).
 */
public final class LoggingFixtureApp {
  private LoggingFixtureApp() {}

  public static void main(String[] args) throws Exception {
    String framework = args.length > 0 ? args[0] : "log4j2";
    FixtureLog log = framework.equals("log4j1") ? new Log4j1Log() : new Log4j2Log();
    log.info("fixture-start framework=" + framework);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Tomcat tomcat = new Tomcat();
    File base = Files.createTempDirectory("logging-fixture-tomcat").toFile();
    tomcat.setBaseDir(base.getAbsolutePath());
    Connector connector = new Connector();
    connector.setPort(Integer.parseInt(env("SERVER_PORT", "8080")));
    connector.setProperty("address", env("SERVER_ADDRESS", "127.0.0.1"));
    tomcat.setConnector(connector);
    Context context = tomcat.addContext("", base.getAbsolutePath());
    Tomcat.addServlet(context, "log", new LogServlet(log, pool)).addMapping("/");
    tomcat.start();
    tomcat.getServer().await();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  /** The servlet of the fixture. */
  static final class LogServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private final transient FixtureLog log;
    private final transient ExecutorService pool;

    LogServlet(FixtureLog log, ExecutorService pool) {
      this.log = log;
      this.pool = pool;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      if (!request.getRequestURI().equals("/log")) {
        response.setContentType("text/plain");
        response.getWriter().write("ok");
        return;
      }
      boolean clear = Boolean.parseBoolean(request.getParameter("clear"));
      boolean async = Boolean.parseBoolean(request.getParameter("async"));
      String user = request.getParameter("user");
      String tag = request.getParameter("tag");
      String safeTag = tag != null && tag.matches("[A-Za-z0-9-]{1,40}") ? tag : "invalid";
      String mode = async ? "async" : clear ? "clear" : "plain";
      if (clear) {
        log.clear();
      }
      Runnable line =
          () -> {
            if (user != null) {
              log.put("user", user);
            }
            try {
              log.info("fixture-log mode=" + mode + " tag=" + safeTag);
            } finally {
              if (user != null) {
                log.remove("user");
              }
            }
          };
      try {
        if (async) {
          pool.submit(line).get(10, TimeUnit.SECONDS);
        } else {
          line.run();
        }
      } catch (Exception e) {
        response.sendError(500);
        return;
      }
      response.setContentType("text/plain");
      response.getWriter().write("logged " + mode);
    }
  }
}
