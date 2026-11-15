/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CATALINA_BASE for one {@link AppLauncher} run of a WAR app (18 §4.1 shop-war-javax and
 * shop-war-jakarta), after the smoke runner's {@code TomcatBase} (sample-apps/smoke):
 *
 * <ul>
 *   <li>CATALINA_HOME is the Tomcat distribution the sample app's build unpacked ({@code
 *       sample-apps/<app>/build/tomcat/<key>}, {@link SampleApp#tomcatHome});
 *   <li>CATALINA_BASE is {@code <workDir>/catalina-base}: {@code conf/} copied from the home except
 *       {@code server.xml}, which is generated with one HTTP connector on {@code 127.0.0.1:<port>}
 *       and, for a graceful stop, a shutdown port on {@code 127.0.0.1} with a random command per
 *       run ({@link Shutdown}); {@code logs/}, {@code temp/}, {@code work/} and {@code webapps/}
 *       with the WAR copied once per context path ({@code shop-a.war} is served under {@code
 *       /shop-a}) plus any extra web application of the run ({@link AppLauncher#webapp}, e.g. the
 *       Spring-free servlet fixture of agent-testing);
 *   <li>Tomcat's own JULI log goes to {@code catalina-base/logs} only: the console handler is taken
 *       out of the copied {@code logging.properties} (catalina.sh sends the console to {@code
 *       catalina.out} too), so the process's stdout and stderr hold the web applications' output
 *       and whatever the JVM or the agent print (T-AG-060 checks them); the log files are read with
 *       {@link RunningApp#tomcatLogLines()} and judged by {@code log.TomcatLogCheck} (WO-05
 *       r1-new-1);
 *   <li>Tomcat starts with {@code java ... org.apache.catalina.startup.Bootstrap start} directly
 *       (the same command on Windows and Linux), with the {@code --add-opens} of catalina.sh on JDK
 *       9 and later (they also let Tomcat's leak detection read thread locals) and an English
 *       locale ({@code user.language=en}, {@code user.country=US}), so that JULI's level names and
 *       Tomcat's messages are the English ones the log judgement matches, whatever the host's
 *       locale (Tomcat ships translated messages, e.g. Korean).
 * </ul>
 *
 * The JVM presets, the agent, {@code java.io.tmpdir}, the environment and the output redirection
 * are the launcher's, as for jar apps.
 */
public final class TomcatStaging {
  /** The class Tomcat's scripts start. */
  public static final String BOOTSTRAP_CLASS = "org.apache.catalina.startup.Bootstrap";

  /** The JULI console handler (writes to {@code System.err}). */
  static final String CONSOLE_HANDLER = "java.util.logging.ConsoleHandler";

  /**
   * The shutdown port and command of one staged Tomcat: {@code <Server port address="127.0.0.1"
   * shutdown>}. {@link #send()} asks Tomcat to stop its web applications (Tomcat's leak detection
   * logs then) and exit, the graceful stop of {@code catalina.sh stop}.
   *
   * @param port the loopback shutdown port
   * @param command the random command of this run (never logged by the harness)
   */
  public record Shutdown(int port, String command) {
    /** A shutdown port with a random 128-bit command. */
    public static Shutdown random(int port) {
      byte[] bytes = new byte[16];
      new SecureRandom().nextBytes(bytes);
      return new Shutdown(port, "SHUTDOWN-" + HexFormat.of().formatHex(bytes));
    }

    /** Sends the command to {@code 127.0.0.1:port}; Tomcat then stops and its JVM exits. */
    public void send() throws IOException {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
        OutputStream out = socket.getOutputStream();
        out.write(command.getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
    }

    @Override
    public String toString() {
      return "Shutdown[port=" + port + ", command=****]";
    }
  }

  private TomcatStaging() {}

  /** {@code <workDir>/catalina-base}. */
  public static Path base(Path workDir) {
    return workDir.resolve("catalina-base");
  }

  /**
   * Creates the CATALINA_BASE of {@code app} in {@code base} for the HTTP port {@code port},
   * without a shutdown port and without extra web applications (see {@link #stage(SampleApp, Path,
   * Path, int, Shutdown, Map)}).
   */
  public static void stage(SampleApp app, Path home, Path base, int port) throws IOException {
    stage(app, home, base, port, null, Map.of());
  }

  /**
   * Creates the CATALINA_BASE of {@code app} in {@code base} for the HTTP port {@code port}: the
   * configuration of {@code home}, the generated {@code server.xml} (with {@code shutdown}'s port
   * and command, or none when null), one WAR copy per context of the app and one per entry of
   * {@code webapps} (context name to WAR, served under {@code /<name>}).
   */
  public static void stage(
      SampleApp app, Path home, Path base, int port, Shutdown shutdown, Map<String, Path> webapps)
      throws IOException {
    Path conf = Files.createDirectories(base.resolve("conf"));
    Files.createDirectories(base.resolve("logs"));
    Files.createDirectories(base.resolve("temp"));
    Files.createDirectories(base.resolve("work"));
    Path webappsDir = Files.createDirectories(base.resolve("webapps"));
    try (DirectoryStream<Path> files = Files.newDirectoryStream(home.resolve("conf"))) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        if (!Files.isRegularFile(file) || name.equals("server.xml")) {
          continue;
        }
        if (name.equals("logging.properties")) {
          Files.writeString(
              conf.resolve(name),
              withoutConsoleHandler(Files.readString(file, StandardCharsets.ISO_8859_1)),
              StandardCharsets.ISO_8859_1);
        } else {
          Files.copy(file, conf.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    Files.writeString(
        conf.resolve("server.xml"), serverXml(port, shutdown), StandardCharsets.UTF_8);
    Map<String, Path> wars = new LinkedHashMap<>();
    for (String context : app.contexts()) {
      wars.put(warName(context), app.artifact());
    }
    for (Map.Entry<String, Path> extra : webapps.entrySet()) {
      String name = warName(extra.getKey());
      if (wars.containsKey(name)) {
        throw new IllegalArgumentException(
            "web application " + name + " is already a context of " + app.name());
      }
      wars.put(name, extra.getValue());
    }
    for (Map.Entry<String, Path> war : wars.entrySet()) {
      Files.copy(
          war.getValue(),
          webappsDir.resolve(war.getKey() + ".war"),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The WAR file name (without {@code .war}) of a context: {@code /shop-a} -> {@code shop-a}. */
  static String warName(String context) {
    String name = context.replaceAll("^/+|/+$", "");
    return name.isEmpty() ? "ROOT" : name;
  }

  /**
   * The Tomcat part of the command line, after the JVM options and the agent: {@code --add-opens}
   * (JDK 9+), the English locale, {@code catalina.home}/{@code catalina.base}, JULI, the bootstrap
   * class path and {@code Bootstrap start}.
   */
  public static List<String> arguments(Path home, Path base, int jdk) {
    Path absoluteHome = home.toAbsolutePath();
    Path absoluteBase = base.toAbsolutePath();
    List<String> arguments = new ArrayList<>();
    if (jdk >= 9) {
      // As catalina.sh does on JDK 9+: lets Tomcat's leak detection read JDK internals.
      arguments.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
      arguments.add("--add-opens=java.base/java.io=ALL-UNNAMED");
      arguments.add("--add-opens=java.base/java.util=ALL-UNNAMED");
      arguments.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
      arguments.add("--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED");
    }
    // English JULI level names and Tomcat messages for the log judgement (class comment).
    arguments.add("-Duser.language=en");
    arguments.add("-Duser.country=US");
    arguments.add("-Dcatalina.home=" + absoluteHome);
    arguments.add("-Dcatalina.base=" + absoluteBase);
    arguments.add("-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");
    arguments.add(
        "-Djava.util.logging.config.file="
            + absoluteBase.resolve("conf").resolve("logging.properties"));
    arguments.add("-classpath");
    arguments.add(
        absoluteHome.resolve("bin").resolve("bootstrap.jar")
            + File.pathSeparator
            + absoluteHome.resolve("bin").resolve("tomcat-juli.jar"));
    arguments.add(BOOTSTRAP_CLASS);
    arguments.add("start");
    return arguments;
  }

  /** server.xml: one HTTP connector on 127.0.0.1:{@code port}, shutdown port disabled. */
  static String serverXml(int port) {
    return serverXml(port, null);
  }

  /**
   * server.xml: one HTTP connector on 127.0.0.1:{@code port}; the shutdown port of {@code shutdown}
   * on 127.0.0.1 with its command, or disabled ({@code port="-1"}) when null.
   */
  static String serverXml(int port, Shutdown shutdown) {
    String server =
        shutdown == null
            ? "<Server port=\"-1\" shutdown=\"SHUTDOWN\">"
            : "<Server port=\""
                + shutdown.port()
                + "\" address=\"127.0.0.1\" shutdown=\""
                + shutdown.command()
                + "\">";
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- SPDX-License-Identifier: Apache-2.0 -->
        <!-- Generated by the Argos agent test harness (one run, 127.0.0.1 only). -->
        %s
          <Listener className="org.apache.catalina.startup.VersionLoggerListener" />
          <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />
          <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener" />
          <Service name="Catalina">
            <Connector port="%d" address="127.0.0.1" protocol="HTTP/1.1" connectionTimeout="20000" />
            <Engine name="Catalina" defaultHost="localhost">
              <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false" />
            </Engine>
          </Service>
        </Server>
        """
        .formatted(server, port);
  }

  /**
   * {@code logging.properties} without {@code java.util.logging.ConsoleHandler} in its {@code
   * handlers} and {@code .handlers} lists (the file handlers stay).
   */
  static String withoutConsoleHandler(String properties) {
    String[] lines = properties.split("\n", -1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      int equals = line.indexOf('=');
      String key = equals < 0 ? "" : line.substring(0, equals).strip();
      if (key.equals("handlers") || key.equals(".handlers")) {
        List<String> kept = new ArrayList<>();
        for (String handler : line.substring(equals + 1).split(",", -1)) {
          if (!handler.isBlank() && !handler.strip().equals(CONSOLE_HANDLER)) {
            kept.add(handler.strip());
          }
        }
        line = key + " = " + String.join(", ", kept) + (line.endsWith("\r") ? "\r" : "");
      }
      out.append(line);
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }
}
