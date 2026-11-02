# transport-poc

WO-02 P-3 agent transport comparison (02 §11 P-3, 10 §10.3, 11 §15 #2–#4, A-1, A-14, ADR-0003 C-23). Development-only: nothing in this project is shipped, and its tasks are not part of `build` or `check` (only formatting, README and license data run there).

Results and decisions: [docs/poc/agent-poc.md §9](../../../docs/poc/agent-poc.md) (P-3 transport comparison), [ADR-0005 transport](../../../docs/adr/0005-agent-transport.md).

## What it builds

Nine comparison agent jars, assembled like `:agent:agent-dist` (shade, relocate below `io.argos.agent.shaded`, minimize, `inst/**.classdata`, bootstrap classes at the root, META-INF notices, manifest), each with the whole agent (core and every instrumentation module) plus a minimal sender in the `grpc` exporter slot that sends one small `TransactionBatch` per second to a local fake collector:

| id | transport | protobuf runtime |
|---|---|---|
| `grpc-netty-lite`, `grpc-netty-full` | grpc-java `NettyChannelBuilder`, NIO, JDK TLS, settings of 10 §10.3 | javalite / protobuf-java |
| `grpc-okhttp-lite`, `grpc-okhttp-full` | grpc-java `OkHttpChannelBuilder`, JDK TLS | javalite / protobuf-java |
| `http1-lite`, `http1-full` | own HTTP/1.1 over a JDK `SSLSocket`, blocking on `argos-sender` | javalite / protobuf-java |
| `grpc-netty41-full` | as `grpc-netty-full`, Netty 4.1 (ADR-0003 C-22 comparison: Netty 4.2 ships Java 11 class files) | protobuf-java |
| `grpc-netty41-full-x`, `http1-full-x` | the jar without the `sun.misc.Unsafe` users that are never loaded (`unsafe/<base id>.excluded.txt`); their check-shading uses `unsafe/<id>.allowlist.txt` | protobuf-java |

Every jar relocates `java.util.logging.Logger` to the agent's `JulLogger` (no JUL in the agent) and keeps the gRPC artifacts whole (minimize roots: providers are loaded through `ServiceLoader`).

The messages are generated from `src/proto`, a snapshot of the 11 §4 protos with the Java package moved below `io.argos.agent` (the real protos arrive with WO-11).

## Layout

| path | content |
|---|---|
| `src/proto` | 11 §4 snapshot + `AgentService` (11 §3) |
| `src/sender/java` | shared sender: settings, batch builder, exporter (1 s cadence, backoff), JDK TLS trust |
| `src/grpcCommon/java`, `src/grpcNetty/java`, `src/grpcOkhttp/java`, `src/http1/java` | transports; each provides `io.argos.agent.core.transport.GrpcExporterFactory` |
| `src/collector/java` | fake collector (gRPC + HTTPS on 127.0.0.1, JDK 21 process) |
| `src/host/java` | host application that reports metaspace, classes, threads, direct memory |
| `src/bench/java` | runner of the smoke test and the measurement matrix |
| `src/protoTest/java` | javalite / full `map` and `optional` tests (run twice) |
| `unsafe/` | exclusion lists and allow-lists of the `-x` jars (from the class-load runs) |

## Tasks

```
./gradlew :test:agent:transport-poc:transportPocJars -Pargos.ui.skip=true    # jars + reports in build/poc/<id>/
./gradlew :test:agent:transport-poc:transportPocTest -Pargos.ui.skip=true    # proto tests + smoke (JDK 8, 21)
./gradlew :test:agent:transport-poc:transportPocBench -Pargos.ui.skip=true \
    -Ptransport.poc.jdk8u242=<JDK 8u242 home> -Ptransport.poc.jdk8u252=<JDK 8u252 home> \
    [-Ptransport.poc.repetitions=5] [-Ptransport.poc.variants=grpc-netty-full,http1-full]
```

`-Ptransport.poc.variants=<ids>` limits the jars that are configured and built (faster iteration). The smoke also fails on temporary files created during the run (T-BLD-006, watched with `WatchService`).

Known findings. Two P-3 findings wait for a PM decision (ADR-0005 transport P1 and P3). The tasks assert them instead of failing on them, and report them as known findings; every other result fails as before:

| finding | where | what the task asserts |
|---|---|---|
| P1: Netty 4.2 ships Java 11 class files (ADR-0003 C-22) | `pocCheckMajorVersion` of `grpc-netty-full`, `grpc-netty-lite` | the jar breaks C-22, and only with classes below `inst/io/argos/agent/shaded/io/netty/` (`build/poc/<id>/reports/major-version.txt`). No violation at all also fails (the finding is gone: update the ADR) |
| P3: on Windows with JDK 16+, the NIO selector of the grpc-netty event loop creates its AF_UNIX wake-up socket file `socket_<n>` in the temporary directory and deletes it at once | `transportPocSmoke`, grpc-netty variants | only files named `socket_<n>`, none left after the run, Windows, JDK 16+ (`KNOWN FINDING` rows in `build/poc/smoke/smoke.md`) |

`-Ptransport.poc.strict=true` turns both back into failures (the plain `checkMajorVersion` and a smoke without known findings).

The bench writes `build/poc/bench/<timestamp>/summary.md`, `runs.tsv` and `raw/<run>/` (command line, stdout, stderr, agent log, host report, collector events, class load log). It records the machine CPU load before and during every run; on a shared machine the numbers are reference values only.
