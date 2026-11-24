# Instrumentation: alpha

Fixture: a Gradle task that the module does not have.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`) |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and `org.example.alpha.Request`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test :agent:agent-instrumentation:alpha:testNope`
