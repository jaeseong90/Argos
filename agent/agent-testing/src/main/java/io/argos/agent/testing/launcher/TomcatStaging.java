/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A CATALINA_BASE for one {@link AppLauncher} run of a WAR app (18 §4.1 shop-war-javax and
 * shop-war-jakarta), after the smoke runner's {@code TomcatBase} (sample-apps/smoke):
 *
 * <ul>
 *   <li>CATALINA_HOME is the Tomcat distribution the sample app's build unpacked ({@code
 *       sample-apps/<app>/build/tomcat/<key>}, {@link SampleApp#tomcatHome});
 *   <li>CATALINA_BASE is {@code <workDir>/catalina-base}: {@code conf/} copied from the home except
 *       {@code server.xml}, which is generated with one HTTP connector on {@code 127.0.0.1:<port>}
 *       and the shutdown port disabled, {@code logs/}, {@code temp/}, {@code work/} and {@code
 *       webapps/} with the WAR copied once per context path ({@code shop-a.war} is served under
 *       {@code /shop-a});
 *   <li>Tomcat's own JULI log goes to {@code catalina-base/logs} only: the console handler is taken
 *       out of the copied {@code logging.properties} (catalina.sh sends the console to {@code
 *       catalina.out} too), so the process's stdout and stderr hold the web applications' output
 *       and whatever the JVM or the agent print (T-AG-060 checks them);
 *   <li>Tomcat starts with {@code java ... org.apache.catalina.startup.Bootstrap start} directly
 *       (the same command on Windows and Linux), with the {@code --add-opens} of catalina.sh on JDK
 *       9 and later.
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

  private TomcatStaging() {}

  /** {@code <workDir>/catalina-base}. */
  public static Path base(Path workDir) {
    return workDir.resolve("catalina-base");
  }

  /**
   * Creates the CATALINA_BASE of {@code app} in {@code base} for the HTTP port {@code port}: the
   * configuration of {@code home}, the generated {@code server.xml} and one WAR copy per context.
   */
  public static void stage(SampleApp app, Path home, Path base, int port) throws IOException {
    Path conf = Files.createDirectories(base.resolve("conf"));
    Files.createDirectories(base.resolve("logs"));
    Files.createDirectories(base.resolve("temp"));
    Files.createDirectories(base.resolve("work"));
    Path webapps = Files.createDirectories(base.resolve("webapps"));
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
    Files.writeString(conf.resolve("server.xml"), serverXml(port), StandardCharsets.UTF_8);
    for (String context : app.contexts()) {
      String name = context.replaceAll("^/+|/+$", "");
      if (name.isEmpty()) {
        name = "ROOT";
      }
      Files.copy(
          app.artifact(), webapps.resolve(name + ".war"), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * The Tomcat part of the command line, after the JVM options and the agent: {@code --add-opens}
   * (JDK 9+), {@code catalina.home}/{@code catalina.base}, JULI, the bootstrap class path and
   * {@code Bootstrap start}.
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
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- SPDX-License-Identifier: Apache-2.0 -->
        <!-- Generated by the Argos agent test harness (one run, 127.0.0.1 only). -->
        <Server port="-1" shutdown="SHUTDOWN">
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
        .formatted(port);
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
