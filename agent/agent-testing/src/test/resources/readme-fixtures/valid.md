# Instrumentation: alpha

Fixture of `ModuleReadmeLintTest`: a README that passes the lint.

## What is recorded

One step per call.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`), `testModern` (`AlphaAdviceTest`) |
| JDK | 8 to 25 | JDK 8 | JDK 25 | `agent-testing` `alpha.AlphaModuleTest` |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and
  `org.example.alpha.Request`, resource lookup only.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test :agent:agent-instrumentation:alpha:testModern`

Older lines are not tested.

## Failure classes

None.
