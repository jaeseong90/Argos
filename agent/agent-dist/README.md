# agent-dist

Assembles the single `argos-agent-<version>.jar` (shadow, classdata, final jar with manifest, LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt and SBOM) and verifies it.

- Gradle project: `:agent:agent-dist`
- Java package: `io.argos.agent.dist`
- Design: [10 agent design §4.1; 19 §3.3](../../docs/10-agent-design.md)

Build: `./gradlew :agent:agent-dist:agentJar` (output `build/libs/argos-agent-<version>.jar`).

Verification: `./gradlew :agent:agent-dist:verifyAgentJar` (also part of `check`):

| Task | Test ID | Checks |
|---|---|---|
| `checkShading` | T-BLD-001..004, T-BLD-006 | runs [`test/agent/check-shading.sh`](../../test/agent/check-shading.sh) (19 §5.6): packages, root exposure, META-INF files and manifest, signatures, major 52, `sun.misc.Unsafe` outside [`unsafe-allowlist.txt`](../../test/agent/unsafe-allowlist.txt), native libraries, size, duplicates |
| `checkMajorVersion` | T-BLD-001 | every `.class`/`.classdata` entry has class file major version 52 |
| `checkRelocatedStrings` | T-BLD-005 | reports property-name constants (`io.netty.noUnsafe`, ...) in original and relocated form; enforcing policy pending (10 §10.3, WO-02) |
| `checkLicenses` | T-BLD-007 | licenses of the bundled dependencies (also feeds the license column of `argos-deps.txt` and `THIRD-PARTY-NOTICES`) |

The script can also be run directly: `bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-<version>.jar`.
