/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WO-02 P-2 agent overhead harness (02 §11 P-2, 01 §7.3, 18 §7, ADR-0004 C-46 and C-49). See
 * test/agent/overhead/README.md for the commands, options and outputs.
 */
public final class OverheadBench {
  private OverheadBench() {}

  static final String USAGE =
      """
      usage: java -jar overhead-bench.jar <command> [options]   (run from the repository root)

        load     --out DIR [--mode process|container] [--rounds 3] [--warmup 3m] [--duration 10m]
                 [--rate 1000] [--variants off,on[,otel]] [--vt] [--heap 2g] [--jvm-opts "..."]
                 [--java PATH] [--k6 auto|docker|PATH] [--cpus 2] [--memory 2g] [--image IMAGE]
                 [--agent JAR] [--app shop-boot3] [--agent-config exporter=noop] [--otel-agent JAR]
                 [--max-load 50] [--max-wait 10m] [--quiet 15s] [--max-background 25]
                 [--retries 3] [--settle 20s] [--keep-app-log] [--label TEXT]
                 [--k6-ssh user@loadhost --app-address <this host's IP> [--k6 k6]]
            A-B-A-B-A-B load set (01 §7.3 items 2-7; --mode container: item 9; --vt: item 10;
            --k6-ssh: k6 on a separate load machine, item 1).
        startup  --out DIR [--apps shop-boot3,shop-heavy] [--runs 10] [--cds default,off]
                 [--variants off,on] [--jvm-opts "-Xms2g -Xmx2g -XX:+UseG1GC"] [--java PATH]
                 [--agent JAR] [--timeout 5m] [--max-load 50] [--max-wait 5m] [--label TEXT]
                 [--variant-defs "name:key=value;key=value|name2:..."]  (also for load)
            Startup set (01 §7.3 item 8, C-49: process creation to the open port, median).
        report   --out DIR
            Recomputes result.json and report.md of an existing set from its raw files.

      Exit codes: 0 PASS, 2 usage error, 3 FAIL, 4 INVALID, 1 any other error.
      """;

  public static void main(String[] args) {
    if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
      System.out.print(USAGE);
      System.exit(args.length == 0 ? 2 : 0);
    }
    List<String> rest = Arrays.asList(args).subList(1, args.length);
    int code;
    try {
      code =
          switch (args[0]) {
            case "load" -> LoadSet.run(Options.parse(rest, LoadSet.FLAGS));
            case "startup" -> StartupSet.run(Options.parse(rest, StartupSet.FLAGS));
            case "report" -> report(Options.parse(rest, new HashSet<>()));
            default -> throw new UsageException("unknown command " + args[0]);
          };
    } catch (UsageException e) {
      System.err.println("overhead-bench: " + e.getMessage() + " (--help lists the options)");
      code = 2;
    } catch (Exception e) {
      System.err.println("overhead-bench: " + e);
      e.printStackTrace(System.err);
      code = 1;
    }
    System.exit(code);
  }

  private static int report(Options o) throws Exception {
    Path dir = Path.of(o.required("out")).toAbsolutePath();
    o.rejectUnknown();
    Path setFile = dir.resolve("set.json");
    if (!Files.isRegularFile(setFile)) {
      throw new UsageException("no set.json in " + dir);
    }
    Object kind = Json.at(FileTools.readJson(setFile), "kind");
    Set<String> known = Set.of("load", "startup");
    if (!known.contains(String.valueOf(kind))) {
      throw new UsageException("unknown set kind " + kind + " in " + setFile);
    }
    return "load".equals(kind) ? LoadReport.write(dir) : StartupReport.write(dir);
  }
}
