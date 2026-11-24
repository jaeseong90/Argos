# Instrumentation: alpha

Fixture: the minimum and maximum are swapped.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 2.0.1 | 1.10.3 | `test` (`AlphaAdviceTest`) |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and `org.example.alpha.Request`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`
