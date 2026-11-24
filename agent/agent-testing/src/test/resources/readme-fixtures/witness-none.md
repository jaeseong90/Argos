# Instrumentation: alpha

Fixture: the README says there is no witness, but the source has one. Valid for a module that
keeps the default witness (NoWitnessModule).

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.example:alpha` | 1.2 to 2.0 | 1.2.3 | 2.0.1 | `test` (`AlphaAdviceTest`) |

- Witness: none (the module keeps the default witness, every class loader).
- Run: `./gradlew :agent:agent-instrumentation:alpha:test`
