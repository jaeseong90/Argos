# Instrumentation: alpha

Fixture: the table has no maximum verified version.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Boundary tests |
|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | `test` (`AlphaAdviceTest`) |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and `org.example.alpha.Request`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`
