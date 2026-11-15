# agent-core

Agent runtime loaded by `AgentClassLoader` from `inst/**.classdata`: start sequence, ByteBuddy
agent builder, module discovery, helper injection, JPMS read edges, tracer, export, logging.

- Gradle project: `:agent:agent-core`
- Java package: `io.argos.agent.core`
- Design: [10 agent design §2, §4-§14](../../docs/10-agent-design.md), [PoC notes](../../docs/poc/agent-poc.md),
  [ADR-0008](../../docs/adr/0008-agent-shading-and-bytebuddy-unsafe.md)

`agent-bootstrap` is `compileOnly` (its classes come from the bootstrap class loader at runtime).
The only instrumentation project the core references is `agent-instrumentation:api` (B-3); modules
are discovered at runtime from `META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule`
(ADR-0003 C-25). Third-party libraries are shaded below `io.argos.agent.shaded` by `agent-dist`.

| Package | Contents (WO-02, WO-10) |
|---|---|
| `core.boot` | `AgentStarter` (P3-P8 of 10 §4.3, entered through `AgentStarter.Handoff` without `Method.invoke`; shutdown hook registered at P3; a failed start flushes the log or prints the single C-78 line), `StartTimer` (`debug.transform-timing`: ms and agent classes per step, C-79), `Recording` (P5 runtime, P7 `argos-sender`), `ArgosAgentBuilder` (ByteBuddy builder without class injection, ADR-0008), `InstrumentationInstaller`, `ModuleRegistry` (C-25), `ModuleTransformer`, `AdviceFactory` (inline advice + `Argos.adviceError` handler), `HelperInjector`, `ModuleReadEdgeListener`, `Jpms`, `IgnoreMatchers` (10 §4.5: an ignored loader or an ignored name, cheapest test first), `PremainDiscovery`, `TransformRecorder` (`debug.transform-list`, `debug.transform-log`), `TestFaults` (C-86), `WatchThread` |
| `core.guard` | `SelfProtection` (advice errors >= `instrumentation.error-threshold` and transformation errors >= `instrumentation.transform-error-threshold` disable a module, one WARN), `TransformErrorListener` (`circularity` reason), `RetransformLimiter` (50 classes, one request per minute, injected clock; executed by WO-17), `AgentCounters` (including the scope chain cut counter `argos_agent_scope_chain_cut_total`: 64 unclosed activations on one thread), `SelfDiagnostics` + `SelfDiagnosticsCollector` (18 §19: missing is absent, never 0) + `SelfDiagnosticsFile` (`test.selfdiag-file`, development builds, C-95) |
| `core.config` | `ConfigLoader` (defaults < `argos-agent.yml` < `-Dargos.<key>` < `ARGOS_<KEY>`, release gate for `test.*`, required keys, never throws), `YamlSubset` (own bounded YAML subset parser), `TokenHolder`/`TokenFile` (`server.token` > `server.token-file` > `ARGOS_TOKEN`, shown as `argos_****`, SEC-06), `ConfigKeys`/`KeySpec` (every key of 10 §15), `AgentConfig` (typed snapshot), `EffectiveConfig` (value and source), `ConfigHash` (xxh3, token excluded), `RemoteConfigGate` + `AllowList` (16 §5.5 evaluation, C-92 defaults; applied by WO-17), `HttpSettings`. Reference: [docs/agent-config.md](../../docs/agent-config.md) |
| `core.masking` | `Masker` + `MaskPatterns`: 16 §6.6 default set (email, Korean mobile, RRN, card with Luhn, IPv4, JWT) then `capture.mask-patterns` (32 x 512 chars, read budget, fail closed), input cut to 64 KiB, message limit applied after masking |
| `core.hash` | `Xxh3` (own XXH3-64, no `Unsafe`, reference vectors; 10 U-17, C-91) |
| `core.context` | `VirtualThreads` (cached `MethodHandle`), `ThreadNames` (normalization, 1,000-name dictionary cap, `_other_`) |
| `core.transport` | `Queues`: five bounded queues (JCTools `MpscAtomicArrayQueue`; count, 120 s age and shared byte budget; drop the oldest once and retry once; `argos_agent_dropped_total{queue}`), `LatestSlot` (active snapshot). The gRPC transport (WO-11, 10 §10.2-§10.4, 11): `GrpcTransport` (the channel of 10 §10.3: one `argos-io` thread, no gRPC shared threads, 4 MiB inbound, keepalive, JDK TLS, token `CallCredentials`, `NettyProps` with the relocated names), `SessionClient` (hello, heartbeat, dictionary answers, reconnect `Backoff`, `isReady` pending list), `Sender` (`argos-sender`: per-kind `seq` and `agent_epoch`, 4 in flight, deadlines, retries of 11 §6, `SESSION_REQUIRED`, split, drop and count), `BatchAssembler` (`Limits`, 1 MiB, gzip above 1 KiB), `DictionaryCache`, `ClockSync`, `RejectingDispatcher` (WO-17 replaces it), `AgentSession` (`InstanceUpdate` API for later work orders), `TransportCounters` (self metrics), `GrpcExporter` (`QueueExporter`, the shutdown sequence) |
| `core.stats`, `core.metric`, `core.command`, `core.watch` | `package-info.java` only: owners WO-16, WO-16, WO-17, WO-19 |
| `core.sql` | `SqlNormalizer` (10 §9.4, SEC-35, WO-02 subset): literals become `?` (strings, prefixed, dollar-quoted, numbers), comments and whitespace collapse, `IN (?, ?)` and multi-row `VALUES` collapse, `<unparsed>` on failure; `TracerImpl.dict(DictKind.SQL, raw)` goes through it (`core.trace.SqlIds`, bounded raw-text cache), so no SQL literal is kept or exported |
| `core.log` | `AgentLogger` (async `argos-logger` thread, `log.dir/argos-agent.log` rolled `log.max-file-mb` x `log.max-files` (10 MB x 5), directory created 0700 on POSIX, `<jar dir>/../logs` else `java.io.tmpdir/argos-agent-<pid>` without `log.dir` (C-76), stderr fallback 10 lines/min and the same message once a minute, held until the start completes, nothing on stdout; a failed start without a log file prints only `argos: agent disabled: <reason>`, C-78) |
| `core.trace` | `TraceRuntime` (P5 wiring), `TracerImpl`, `Transaction`, `StepBuffer` (SoA, 64 doubling to the step cap, summary mode, open-step stack per activation scope, repeat folding, `truncated`/`total_steps`), `ProfileSteps`/`ProfileRecord`, `ActiveRegistry`, `ThreadSlots`, `Sampler`, `TxIdGenerator` (10 §9.5, C-91), `TraceIds`, `Dictionary` (xxh3 ids, per-kind caps), `IgnoreRules` (10 §7.2, `Tracer.ignoreReason`), `ServiceNames` (F-A29 fallback normalization, 10,000-name cap), `HttpPaths` (path parameters, extensions, `{*}` segments), `RemoteIps` (SEC-31 masked/full/off) |
| `core.export` | Exporter SPI (`Exporter`, `ExporterFactory`, `ExporterContext`), `NoopExporter`, `JsonlFileExporter`, the `grpc` slot (`io.argos.agent.core.transport.GrpcExporterFactory`), `ExportWorker` (`argos-sender`, the only consumer of the queues) |
| `core.check` | `CheckCommand` (`--version`; `--check` JDK item, the rest in WO-19) |

Settings: every key of the 10 §15 table is registered in `core.config.ConfigKeys` (`KeySpec`:
type, default, the "remote" column, bounds, secrecy, kind PUBLIC/DIAGNOSTIC/TEST) and read through
the typed snapshot `AgentConfig` (WO-10). Diagnostics keys: `debug.transform-timing`,
`debug.transform-log`, `debug.transform-list`; test keys (development builds only):
`test.exporter`, `test.faults`, `test.selfdiag-file`. The WO-02 PoC keys `exporter` and
`exporter.file.*` were removed (ADR-0009 C-88).

Tests (`./gradlew :agent:agent-core:test`, run on Java 8): step buffer and summary mode, tracer,
ids and dictionary, JSONL export and queue bounds, configuration, logger (format, rolling, stderr
limit, timestamps), ignore list and globs, module discovery, agent builder factory, `--check`,
request exclusion rules, fallback service names and cap, path cleaning, IP masking, message
masking (`HttpRulesTest`, `HttpSettingsTest`, `MaskerTest`), SQL normalization and SQL ids
(`SqlNormalizerTest`, `SqlIdsTest`), the transport with fake seams and a manual clock (`SenderTest`,
`SessionClientTest`, `BatchAssemblerTest`, `TransportPartsTest`), the log bridge and byte bound
(`JulLoggerTest`, `LogPipelineBytesTest`).
`:agent:agent-core:integrationTest` adds `SessionFlowControlIntegrationTest`: the real grpc-netty
stack against a raw gRPC server on the loopback (`isReady` back pressure, call metadata), and
`ExportWorkerShutdownIntegrationTest`: the shutdown hook's bounds with the real bye (1 s) and flush
(1 s) times (`ExportWorkerShutdownTest` checks the same properties with a scaled budget, ADR-0010
C-146 (2)).
End-to-end behaviour on JDK 8-25 is tested in `agent-testing` (`AgentAttachTest`, `transport.*`).
