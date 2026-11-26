/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent overhead bench harness (18 §7, DSN-18-07, 01 §7.3, ADR-0004 C-46 and C-49), formalised by
 * WO-05 from the WO-02 P-2 harness. See test/bench/README.md for the commands, options and outputs.
 */
public final class OverheadBench {
  private OverheadBench() {}

  /** The harness arguments of this invocation, recorded in set.json for part 8 of the report. */
  private static volatile String commandLine = "";

  static String commandLine() {
    return commandLine;
  }

  static final String USAGE =
      """
      usage: java -jar bench-harness.jar <command> [options]   (run from the repository root)

        load     --out DIR [--exporter noop|drop|grpc] [--collector HOST:PORT] [--profile default|worst]
                 [--env-record FILE] [--mode process|container] [--rounds 3] [--warmup 3m]
                 [--duration 10m] [--rate 1000] [--variants off,on[,otel]] [--vt] [--heap 2g]
                 [--jvm-opts "..."] [--java PATH] [--k6 auto|docker|PATH] [--raw on|off] [--seed 42]
                 [--cpus 2] [--memory 2g] [--image IMAGE] [--agent JAR] [--app shop-boot3]
                 [--agent-config key=value,...] [--otel-agent auto|JAR] [--max-load 50]
                 [--max-wait 10m] [--quiet 15s] [--max-background 25] [--retries 3] [--settle 20s]
                 [--keep-app-log] [--label TEXT]
                 [--k6-ssh user@loadhost --app-address <this host's IP> [--k6 k6]]
                 (--otel-agent auto: test/bench/build/otel/opentelemetry-javaagent-2.32.0.jar,
                 SHA-256-checked, after test/bench/fetch-otel-agent.sh; never downloaded by a run)
            A-B-A-B-A-B load set (01 §7.3 items 2-7, 18 §7.3; --mode container: item 9; --vt: item
            10; --k6-ssh: k6 on a separate load machine, item 1). --exporter noop is 18 §5.4's no-op
            exporter (argos.test.exporter=noop), drop is the WO-02 setting (argos.test.exporter=drop),
            grpc measures the transport and needs --collector (and, for a plaintext or token-checking
            collector, --agent-config server.tls=false,server.token-file=FILE).
        startup  --out DIR [--exporter noop|drop|grpc] [--collector HOST:PORT]
                 [--apps shop-boot3,shop-heavy] [--runs 10] [--cds default,off]
                 [--variants off,on] [--jvm-opts "-Xms2g -Xmx2g -XX:+UseG1GC"] [--java PATH]
                 [--agent JAR] [--agent-config key=value,...] [--env-record FILE] [--timeout 5m]
                 [--max-load 50] [--max-wait 5m] [--label TEXT]
                 [--variant-defs "name:key=value;key=value|name2:..."]  (also for load)
            Startup set (01 §7.3 item 8, C-49: process creation to the open port, median).
        report   --out DIR
            Recomputes result.json and report.md of an existing set from its raw files.
        publish  --sets DIR[,DIR...] [--out docs/bench] [--baseline auto|none|REPORT.md]
                 [--name NAME] [--command "reproduction command"] [--seed 42] [--overwrite]
                 [--latest] [--trend]
            The benchmark report of 18 §7.6 (docs/bench/<YYYY-MM-DD>-<commit7>.md, graphs in the
            directory of the same name) with the N-01..N-04, N-26..N-30 verdicts and the 18 §7.7
            regression check: FAIL (an N-xx limit) before WARN (+1.5 percentage points relative
            CPU or +0.3 ms p99 difference against the baseline). --baseline auto takes the newest
            approved report in --out with the same machine profile, app, JDK, exporter and
            encoding (none: NOT COMPARED with the reason); default none. --latest rewrites
            <out>/latest.md (pointer to this report), --trend rewrites <out>/trend.md, trend.svg,
            trend-p99.svg and trend.tsv. A set measured with --exporter grpc adds the transport
            cost section (no verdict, ADR-0009 C-80).
        trend    --dir docs/bench [--out DIR]
            The trend over the report summaries of --dir (the newest report's configuration;
            WO-02 era directories and JSONL-era numbers left out, ADR-0010 C-145 (4)).
        gate1    --reports REPORT.md[,REPORT.md...] --out FILE.md
            Gate 1 fragment: the T-NFR-01..04 and 26..30 rows (requirement, limit, numbers,
            PASS/FAIL/INVALID, evidence) and what only a person can confirm; FILE.json next to it.
        noise    --sets DIR[,DIR...] [--out FILE.md] [--seed 42] [--replicates 1000]
            Noise evidence for 18 open item 3: off-run CV, off-vs-off bootstrap intervals of the
            relative CPU and p99, minimal detectable effect and a recommendation (stdout without
            --out; FILE.json next to FILE.md).

      Exit codes: 0 PASS, 2 usage error, 3 FAIL, 4 INVALID, 1 any other error.
      """;

  public static void main(String[] args) {
    if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
      System.out.print(USAGE);
      System.exit(args.length == 0 ? 2 : 0);
    }
    commandLine = shellQuoted(args);
    List<String> rest = Arrays.asList(args).subList(1, args.length);
    int code;
    try {
      code =
          switch (args[0]) {
            case "load" -> LoadSet.run(Options.parse(rest, LoadSet.FLAGS));
            case "startup" -> StartupSet.run(Options.parse(rest, StartupSet.FLAGS));
            case "report" -> report(Options.parse(rest, new HashSet<>()));
            case "publish" -> BenchReport.publish(Options.parse(rest, BenchReport.PUBLISH_FLAGS));
            case "trend" -> Trend.run(Options.parse(rest, Set.of()));
            case "gate1" -> Gate1Report.run(Options.parse(rest, Set.of()));
            case "noise" -> NoiseCalibration.run(Options.parse(rest, Set.of()));
            default -> throw new UsageException("unknown command " + args[0]);
          };
    } catch (UsageException e) {
      System.err.println("bench-harness: " + e.getMessage() + " (--help lists the options)");
      code = 2;
    } catch (Exception e) {
      System.err.println("bench-harness: " + e);
      e.printStackTrace(System.err);
      code = 1;
    }
    System.exit(code);
  }

  /** The arguments as a bash command line: words with spaces or shell characters single-quoted. */
  static String shellQuoted(String... args) {
    StringBuilder out = new StringBuilder();
    for (String arg : args) {
      if (out.length() > 0) {
        out.append(' ');
      }
      if (!arg.isEmpty() && arg.matches("[A-Za-z0-9_@%+=:,./-]+")) {
        out.append(arg);
      } else {
        out.append('\'').append(arg.replace("'", "'\\''")).append('\'');
      }
    }
    return out.toString();
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
