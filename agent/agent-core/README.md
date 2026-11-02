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

| Package | Contents (WO-02) |
|---|---|
| `core.boot` | `AgentStarter` (P3-P8), `ArgosAgentBuilder` (ByteBuddy builder without class injection, ADR-0008), `InstrumentationInstaller`, `ModuleRegistry` (C-25), `ModuleTransformer`, `AdviceFactory` (inline advice + `Argos.adviceError` handler), `HelperInjector`, `ModuleReadEdgeListener`, `Jpms`, `IgnoreMatchers` (10 §4.5), `PremainDiscovery`, `TransformRecorder` (`debug.transform-list`), `TransformErrorListener`, `WatchThread` |
| `core.config` | `AgentConfig` (`-Dargos.<key>`, then `ARGOS_<KEY>`; YAML in WO-10), `ConfigKeys`, `HttpSettings` (exclusion rules, service name cap, `capture.remote-ip`) |
| `core.masking` | `Masker`: SEC-36 default set (16 §6.6: email, Korean mobile, RRN, card with Luhn, IPv4, JWT) applied to exception messages before the length cut |
| `core.sql` | `SqlNormalizer` (10 §9.4, SEC-35, WO-02 subset): literals become `?` (strings, prefixed, dollar-quoted, numbers), comments and whitespace collapse, `IN (?, ?)` and multi-row `VALUES` collapse, `<unparsed>` on failure; `TracerImpl.dict(DictKind.SQL, raw)` goes through it (`core.trace.SqlIds`, bounded raw-text cache), so no SQL literal is kept or exported |
| `core.log` | `AgentLogger` (async `argos-logger` thread, `log.dir/argos-agent.log` rolled 10 MB x 5, stderr fallback 10 lines/min, nothing on stdout) |
| `core.trace` | `TracerImpl`, `Transaction`, `StepBuffer` (bounded, summary mode), `TxIdGenerator`, `TraceIds`, `Dictionary` (FNV-1a 64 until WO-10's xxh3), `IgnoreRules` (10 §7.2, `Tracer.ignoreReason`), `ServiceNames` (F-A29 fallback normalization, 10,000-name cap), `HttpPaths` (path parameters, extensions, `{*}` segments), `RemoteIps` (SEC-31 masked/full/off) |
| `core.export` | Exporter SPI (`Exporter`, `ExporterFactory`, `ExporterContext`), `NoopExporter`, `JsonlFileExporter`, the `grpc` slot (`io.argos.agent.core.transport.GrpcExporterFactory`), bounded `TransactionQueue`, `ExportWorker` (`argos-sender`) |
| `core.check` | `CheckCommand` (`--version`; `--check` JDK item, the rest in WO-19) |

Agent settings read in WO-02: `enabled`, `log.level`, `log.dir`, `log.max-file-mb`,
`log.max-files`, `instrumentation.disabled`, `instrumentation.exclude-classes`,
`instrumentation.error-threshold`, `instrumentation.transform-error-threshold`,
`sampling.profile.max-steps`, `buffer.transaction.capacity`, `buffer.max-bytes`,
`sender.batch-size`, `sender.flush-interval-ms`, `debug.transform-timing`,
`instrumentation.ignore-paths`, `instrumentation.ignore-extensions`,
`instrumentation.ignore-user-agents`, `instrumentation.ignore-methods` (unset: 10 §15 defaults,
empty: rule off), `instrumentation.service-dictionary-max`, `capture.remote-ip`,
`capture.error-message-max-length`, `capture.stack-depth`, `async.step` (executor ASYNC steps,
default off; carried to advice in `CaptureConfig.asyncStep()`), and the PoC keys
`exporter` (`grpc` default, `file`, `noop`), `exporter.file.path`, `exporter.file.max-mb`,
`debug.transform-list`, `test.faults` (`advice-throw:<module id>`, tests only).

Tests (`./gradlew :agent:agent-core:test`, run on Java 8): step buffer and summary mode, tracer,
ids and dictionary, JSONL export and queue bounds, configuration, logger (format, rolling, stderr
limit, timestamps), ignore list and globs, module discovery, agent builder factory, `--check`,
request exclusion rules, fallback service names and cap, path cleaning, IP masking, message
masking (`HttpRulesTest`, `HttpSettingsTest`, `MaskerTest`), SQL normalization and SQL ids
(`SqlNormalizerTest`, `SqlIdsTest`).
End-to-end behaviour on JDK 8-25 is tested in `agent-testing` (`AgentAttachTest`).
