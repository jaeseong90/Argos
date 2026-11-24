# Instrumentation: alpha

Fixture: the section has prose but no version range subsection.

## Supported library versions

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`) |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and `org.example.alpha.Request`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`

## Version range and witness

A level-2 heading of the same name does not count.
