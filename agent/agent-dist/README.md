# agent-dist

Assembles the single `argos-agent-<version>.jar` (shadow, classdata, final jar with manifest, LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt and SBOM) and verifies it.

- Gradle project: `:agent:agent-dist`
- Java package: `io.argos.agent.dist`
- Design: [10 agent design §4.1; 19 §3.3](../../docs/10-agent-design.md), [PoC notes](../../docs/poc/agent-poc.md),
  [ADR-0008](../../docs/adr/0008-agent-shading-and-bytebuddy-unsafe.md)

Build: `./gradlew :agent:agent-dist:agentJar` (output `build/libs/argos-agent-<version>.jar`).

| Stage | Task | Output |
|---|---|---|
| 1 | `shadowJar` | `build/shadow/agent-inst.jar`: agent-core, instrumentation modules and their runtime dependencies (ByteBuddy 1.18.15), external packages relocated below `io.argos.agent.shaded`, `minimize()`, merged service files (module index, C-25), the `sun.misc.Unsafe` users of ByteBuddy removed (`AgentShading.UNSAFE_EXCLUDED_CLASSES`) |
| 2 | `classdataJar` | `build/classdata/inst/**`: `.class` renamed to `.classdata`, resources below `inst/` |
| 3 | `agentJar` | the jar: bootstrap classes and `io/argos/agent/AgentPremain.class` at the root, `inst/`, `META-INF` notices and SBOM, manifest (`Premain-Class`/`Agent-Class`/`Main-Class` = `io.argos.agent.AgentPremain`, `Argos-Commit`, C-21) |

Outgoing variants for other projects: `agentJarElements` (the jar; Category `argos-agent-jar`)
and `agentInstElements` (stage 1 jar; Category `argos-agent-inst-jar`).

Verification: `./gradlew :agent:agent-dist:verifyAgentJar` (also part of `check`):

| Task | Test ID | Checks |
|---|---|---|
| `checkShading` | T-BLD-001..004, T-BLD-006 | runs [`test/agent/check-shading.sh`](../../test/agent/check-shading.sh) (19 §5.6): packages, root exposure, META-INF files and manifest, signatures, major version (own classes 52, shaded classes <= 52, ADR-0003 C-22), `sun.misc.Unsafe` outside [`unsafe-allowlist.txt`](../../test/agent/unsafe-allowlist.txt) (empty), native libraries, size, duplicates |
| `checkMajorVersion` | T-BLD-001 | own `.class`/`.classdata` entries major 52, shaded entries (`inst/io/argos/agent/shaded/`) major <= 52 (C-22) |
| `checkRelocatedStrings` | T-BLD-005 | property-name constants (`net.bytebuddy.*`, `io.netty.*`) only in relocated form, policy RELOCATED (C-23, ADR-0008) |
| `checkOwnRelocatedLiterals` | T-BLD-005 | no string constant of the agent's own relocated classes (`inst/io/argos/agent/`, outside `shaded/`) lies in the relocated namespace: Shadow rewrites literals such as `"net.bytebuddy."` too, so build them at run time (C-23, ADR-0005 transport); report `build/reports/agent-jar/own-relocated-literals.txt` |
| `checkModuleIndex` | C-25 | every class in `inst/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule` exists in the jar |
| `checkLicenses` | T-BLD-007 | licenses of the bundled dependencies (also feeds the license column of `argos-deps.txt` and `THIRD-PARTY-NOTICES`) |

THIRD-PARTY-NOTICES carries each bundled module's original `META-INF/NOTICE` text and the
licenses of code embedded in its jar (`META-INF/licenses/*`, e.g. ByteBuddy's ASM, BSD-3-Clause).

The script can also be run directly: `bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-<version>.jar`.
