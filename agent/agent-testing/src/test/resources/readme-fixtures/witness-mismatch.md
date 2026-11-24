# Instrumentation: alpha

Fixture: the witness line names one of the two classes the source checks.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`) |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`

The request class `org.example.alpha.Request` named outside the witness line does not count.
