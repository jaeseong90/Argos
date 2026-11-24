# Instrumentation: alpha

Fixture: the boundary cell names no test class.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | the module tests |

- Witness (`AlphaModule.witness()`): `org.example.alpha.Client` and `org.example.alpha.Request`.
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`
