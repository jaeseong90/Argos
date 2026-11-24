# Instrumentation: alpha

Fixture: the witness bullet is missing.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`) |

The module checks `org.example.alpha.Client` and `org.example.alpha.Request` first.

- Run: `./gradlew :agent:agent-instrumentation:alpha:test`
