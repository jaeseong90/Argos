// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.testing.MatrixYaml

/** Kind of a non-test evidence entry (test/traceability/evidence.yaml, WO-53). */
enum class EvidenceKind(val label: String) {
  /** A measurement on a dedicated machine or target hardware (bench, server load, API bench, UI perf). */
  MEASUREMENT("measurement"),
  /** A person's check: acceptance checklist (T-ACPT), review, rehearsal, non-participant install. */
  HUMAN("human"),
  /** A design check without a test (18 §16.1 `(설계 확인, 테스트 없음)`). */
  DESIGN("design"),
  /** Evidence owned by a later work order or gate (egress capture, chaos, soak). */
  DEFERRED("deferred");

  companion object {
    fun of(text: String): EvidenceKind? = entries.firstOrNull { it.label == text }
  }
}

/**
 * One evidence entry: what it covers (test IDs and requirements), its kind, where the result comes
 * from, the work order that owns it and the gate that needs it.
 */
data class EvidenceEntry(
  val idExpressions: List<String>,
  val ids: List<String>,
  val requirements: List<String>,
  val kind: EvidenceKind,
  val source: String,
  val owner: String,
  val gate: String,
  val note: String,
  val line: Int,
)

class EvidenceException(message: String) : RuntimeException(message)

/**
 * The evidence that is not a test result (WO-53, PM question 2 assumption): 18 §16.1 rows whose
 * evidence is a dedicated-machine measurement, a person, a design check or a later work order. An
 * entry counts as coverage of its requirements and IDs; the check report shows its kind. When a
 * source test with the same ID exists, the test is shown first (sources win).
 *
 * Format (the YAML subset of [MatrixYaml]):
 * ```
 * format: 1
 * entries:
 *   - ids: [T-NFR-01~04, T-NFR-26~30]       # test ID expressions, catalogued in docs/18 (may be empty)
 *     requirements: [N-01, N-02]             # requirement IDs it covers (may be empty when ids are not)
 *     kind: measurement                      # measurement | human | design | deferred
 *     source: "docs/bench/gate1-agent-*.md"  # where the result comes from
 *     owner: WO-51                           # work order that produces it
 *     gate: Gate 1                           # gate that needs it
 *     note: "..."                            # optional
 * ```
 */
class EvidenceRegistry(val entries: List<EvidenceEntry>) {

  val ids: Set<String> by lazy { entries.flatMap { it.ids }.toSortedSet(Traceability.naturalOrder) }

  fun forRequirement(requirement: String): List<EvidenceEntry> = entries.filter { requirement in it.requirements }

  fun forId(id: String): List<EvidenceEntry> = entries.filter { id in it.ids }

  companion object {
    const val PATH = "test/traceability/evidence.yaml"
    const val FORMAT = "1"

    val EMPTY = EvidenceRegistry(emptyList())

    fun parse(text: String, source: String = PATH): EvidenceRegistry {
      val root =
        try {
          MatrixYaml.parse(text, source)
        } catch (e: MatrixYaml.ParseException) {
          throw EvidenceException(e.message ?: "$source: not readable")
        }
      if (root !is MatrixYaml.Mapping) throw EvidenceException("$source: the document must be a mapping")
      val format = (root.entries["format"] as? MatrixYaml.Scalar)?.value
      if (format != FORMAT) throw EvidenceException("$source: 'format: $FORMAT' is required (found ${format ?: "none"})")
      val list = root.entries["entries"] ?: return EMPTY
      if (list !is MatrixYaml.Sequence) throw EvidenceException("$source:${list.line}: 'entries' must be a sequence")
      val entries =
        list.items.map { item ->
          if (item !is MatrixYaml.Mapping) throw EvidenceException("$source:${item.line}: an entry must be a mapping")
          fun error(message: String): Nothing = throw EvidenceException("$source:${item.line}: $message")
          val unknown = item.entries.keys - setOf("ids", "requirements", "kind", "source", "owner", "gate", "note")
          if (unknown.isNotEmpty()) error("unknown keys $unknown")
          fun scalar(key: String, required: Boolean): String {
            val node = item.entries[key]
            if (node == null) {
              if (required) error("'$key' is required")
              return ""
            }
            if (node !is MatrixYaml.Scalar) error("'$key' must be a scalar")
            if (required && node.value.isBlank()) error("'$key' must not be empty")
            return node.value
          }
          fun list(key: String): List<String> =
            when (val node = item.entries[key]) {
              null -> emptyList()
              is MatrixYaml.Sequence -> node.items.map { (it as? MatrixYaml.Scalar)?.value ?: error("'$key' items must be scalars") }
              is MatrixYaml.Scalar -> listOf(node.value)
              else -> error("'$key' must be a sequence")
            }
          val expressions = list("ids")
          val ids = expressions.flatMap { expr ->
            IdExpressions.expand(expr).ifEmpty { error("'$expr' is not a test ID expression") }
          }
          val requirements = list("requirements").flatMap { r -> RequirementIds.expand(r).ifEmpty { listOf(r) } }
          if (ids.isEmpty() && requirements.isEmpty()) error("an entry needs 'ids' or 'requirements'")
          val kindText = scalar("kind", true)
          val kind = EvidenceKind.of(kindText) ?: error("kind '$kindText' is not one of ${EvidenceKind.entries.map { it.label }}")
          EvidenceEntry(
            idExpressions = expressions,
            ids = ids.distinct(),
            requirements = requirements.distinct(),
            kind = kind,
            source = scalar("source", true),
            owner = scalar("owner", true),
            gate = scalar("gate", true),
            note = scalar("note", false),
            line = item.line,
          )
        }
      return EvidenceRegistry(entries)
    }
  }
}
