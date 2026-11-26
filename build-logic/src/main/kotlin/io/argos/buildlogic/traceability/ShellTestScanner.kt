// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

/**
 * Finds the test IDs of shell test scripts (ADR-0010 C-200 (1), C-250 (6)): the IDs inside quoted
 * strings of the script (the descriptions of its assertion calls, `expect_exit 0 "T-SEC-84a: …"`)
 * and the header comments `# test-ids: …` and `# req: …`. IDs in ordinary comments do not count,
 * nor do strings of failure messages (`fail "T-…"`, `echo "FAIL  T-…"`). Every distinct description
 * with an ID is one test; header IDs that no description carries (and a header `# req:` of a script
 * without any ID in its descriptions) form one more test that takes the script's results whose
 * descriptions have no ID ([SourceTest.matchAll]). Requirements of every test are the header's.
 *
 * The lexer knows single and double quotes, `$'…'`, backslash escapes, `$(…)` inside double quotes,
 * comments and here-documents; it does not run or source anything.
 */
object ShellTestScanner {

  const val SOURCE: String = "shell"

  /** Scripts that are not named `*.test.sh` but are test suites (Compose and release rehearsals). */
  val EXTRA_SCRIPTS: Set<String> =
    setOf(
      "deploy/compose/test/compose-smoke.sh",
      "deploy/compose/test/demo-smoke.sh",
      "deploy/compose/test/backup-roundtrip.sh",
      "deploy/test/release-signing-rehearsal.sh",
    )

  private val containerCases = Regex("""(?:^|.*/)test/container/[^/]+-cases\.sh""")
  private val headerTestIds = Regex("""^#\s*test-ids\s*:(.*)$""")
  private val headerRequirements = Regex("""^#\s*req\s*:(.*)$""")
  private val heredoc = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1""")
  private val failureCommands = setOf("fail", "die", "fatal", "error")
  private val statusPrefix = Regex("""^(?:PASS|SKIP|ok:)\s+""")
  private val statementSeparator = Regex("""\|\||&&|;|\||\(|\{""")

  data class Scan(val tests: List<SourceTest>, val headerTestIds: List<String>, val headerRequirements: List<String>)

  /** True for a repository-relative path of a shell test script this scanner reads. */
  fun isShellTest(relativePath: String): Boolean {
    if (relativePath.contains("/src/test/resources/") || relativePath.contains("/node_modules/")) return false
    val name = relativePath.substringAfterLast('/')
    return name.endsWith(".test.sh") || containerCases.matches(relativePath) || relativePath in EXTRA_SCRIPTS
  }

  fun scan(file: String, text: String): Scan {
    val ids = mutableListOf<String>()
    val reqs = mutableListOf<String>()
    var headerLine = 0
    text.lineSequence().forEachIndexed { index, raw ->
      val line = raw.trim()
      headerTestIds.matchEntire(line)?.let {
        ids += TestIds.find(it.groupValues[1])
        if (headerLine == 0) headerLine = index + 1
      }
      headerRequirements.matchEntire(line)?.let { m ->
        reqs += m.groupValues[1].split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (headerLine == 0) headerLine = index + 1
      }
    }
    val headerIds = ids.distinct()
    val headerReqs = reqs.distinct()
    val projectDir = file.substringBeforeLast('/', "")
    val tests = mutableListOf<SourceTest>()
    val seen = HashSet<String>()
    for (s in strings(text)) {
      val found = TestIds.find(s.text)
      if (found.isEmpty() || s.failure) continue
      val title = statusPrefix.replace(s.text.trim(), "")
      if (!seen.add(title)) continue
      tests += SourceTest(file, s.line, projectDir, SOURCE, file, null, title, null, false, found, headerReqs, emptyList())
    }
    val described = tests.flatMap { it.testIds }.toSet()
    val headerOnly = headerIds.filterNot { it in described }
    if (headerOnly.isNotEmpty() || (tests.isEmpty() && headerReqs.isNotEmpty())) {
      tests +=
        SourceTest(
          file,
          maxOf(headerLine, 1),
          projectDir,
          SOURCE,
          file,
          null,
          "${file.substringAfterLast('/')} (header test-ids)",
          null,
          false,
          headerOnly,
          headerReqs,
          emptyList(),
          matchAll = true,
        )
    }
    return Scan(tests, headerIds, headerReqs)
  }

  /** A quoted string: its text (escapes resolved, `$…` kept), start line, whether it is a failure message. */
  internal data class QuotedString(val text: String, val line: Int, val failure: Boolean)

  /** Quoted strings outside comments and here-documents, in order. */
  internal fun strings(text: String): List<QuotedString> {
    val out = mutableListOf<QuotedString>()
    val n = text.length
    var i = 0
    var line = 1
    var atWordStart = true
    var lineStart = 0
    val pendingHeredocs = ArrayDeque<Pair<String, Boolean>>()

    fun record(content: String, startLine: Int, start: Int) {
      // The command of the statement the string belongs to: the first word after the last ;, |, &&, ||.
      val statement = text.substring(minOf(lineStart, start), start).split(statementSeparator).last().trim()
      val command = statement.split(' ', '\t').firstOrNull { it.isNotEmpty() }
      out += QuotedString(content, startLine, content.trimStart().startsWith("FAIL") || command in failureCommands)
    }

    while (i < n) {
      val c = text[i]
      when {
        c == '\n' -> {
          line++
          i++
          lineStart = i
          atWordStart = true
          while (pendingHeredocs.isNotEmpty()) {
            val (delimiter, dash) = pendingHeredocs.removeFirst()
            while (i < n) {
              val end = text.indexOf('\n', i).let { if (it < 0) n else it }
              val body = text.substring(i, end).removeSuffix("\r")
              i = minOf(n, end + 1)
              line++
              if ((if (dash) body.trimStart('\t') else body) == delimiter) break
            }
            lineStart = i
          }
        }
        c == '\\' -> {
          if (i + 1 < n && text[i + 1] == '\n') line++
          i += 2
          atWordStart = false
        }
        c == '#' && atWordStart -> {
          while (i < n && text[i] != '\n') i++
        }
        c == '\'' -> {
          val start = i
          val end = text.indexOf('\'', i + 1).let { if (it < 0) n else it }
          val content = text.substring(i + 1, end)
          record(content, line, start)
          line += content.count { it == '\n' }
          i = end + 1
          atWordStart = false
        }
        c == '$' && i + 1 < n && text[i + 1] == '\'' -> {
          val start = i
          val sb = StringBuilder()
          var j = i + 2
          val startLine = line
          while (j < n && text[j] != '\'') {
            if (text[j] == '\\' && j + 1 < n) {
              sb.append(text[j + 1])
              j += 2
            } else {
              if (text[j] == '\n') line++
              sb.append(text[j])
              j++
            }
          }
          record(sb.toString(), startLine, start)
          i = j + 1
          atWordStart = false
        }
        c == '"' -> {
          val start = i
          val startLine = line
          val sb = StringBuilder()
          val end = readDouble(text, i + 1, sb)
          line += text.substring(i, minOf(n, end)).count { it == '\n' }
          record(sb.toString(), startLine, start)
          i = end + 1
          atWordStart = false
        }
        c == '<' && text.startsWith("<<", i) && !text.startsWith("<<<", i) -> {
          val m = heredoc.find(text, i)?.takeIf { it.range.first == i }
          if (m != null) {
            pendingHeredocs.addLast(m.groupValues[2] to text.startsWith("<<-", i))
            i = m.range.last + 1
          } else {
            i += 2
          }
          atWordStart = false
        }
        else -> {
          // `${#x}` and `$#` are no comments: '{' and '$' never start a word here.
          atWordStart = c.isWhitespace() || c == ';' || c == '|' || c == '&' || c == '(' || c == ')'
          i++
        }
      }
    }
    return out
  }

  /**
   * Reads a double-quoted string from [from] (after the opening quote) into [sb]: `\"`, `\\`, `\$`
   * and `` \` `` lose their backslash, `$(…)` (with nested quotes) is kept verbatim. Returns the
   * index of the closing quote (or the end of the text).
   */
  private fun readDouble(text: String, from: Int, sb: StringBuilder): Int {
    var j = from
    val n = text.length
    while (j < n) {
      val ch = text[j]
      when {
        ch == '"' -> return j
        ch == '\\' && j + 1 < n -> {
          val next = text[j + 1]
          if (next == '"' || next == '\\' || next == '$' || next == '`') sb.append(next)
          else if (next != '\n') sb.append(ch).append(next)
          j += 2
        }
        ch == '$' && j + 1 < n && text[j + 1] == '(' -> {
          val end = readSubstitution(text, j + 2)
          sb.append(text, j, minOf(n, end + 1))
          j = end + 1
        }
        else -> {
          sb.append(ch)
          j++
        }
      }
    }
    return n
  }

  /** Index of the `)` that closes a `$(` whose body starts at [from]; quotes inside are skipped. */
  private fun readSubstitution(text: String, from: Int): Int {
    var depth = 1
    var j = from
    val n = text.length
    while (j < n) {
      when (text[j]) {
        '\\' -> j++
        '\'' -> j = text.indexOf('\'', j + 1).let { if (it < 0) n else it }
        '"' -> j = readDouble(text, j + 1, StringBuilder())
        '(' -> depth++
        ')' -> if (--depth == 0) return j
      }
      j++
    }
    return n
  }

  /**
   * Whether a shell description template (`T-SEC-84a: $name exits 0`) produced [description]: `$x`,
   * `${…}` and `$(…)` match anything; a failure detail in parentheses may follow (`(exit 1, expected 0)`).
   */
  fun matches(template: String, description: String): Boolean =
    Regex("^" + templatePattern(template, shell = true) + "(?: \\(.*\\))?$", RegexOption.DOT_MATCHES_ALL).matches(description)

  /** Regex source of [template]: literal text, with `${…}` (and for shell also `$x`, `$(…)`) as `.*`. */
  fun templatePattern(template: String, shell: Boolean): String {
    val sb = StringBuilder()
    val literal = StringBuilder()
    fun flush() {
      if (literal.isNotEmpty()) sb.append(Regex.escape(literal.toString()))
      literal.setLength(0)
    }
    var i = 0
    val n = template.length
    while (i < n) {
      val c = template[i]
      if (c == '$' && i + 1 < n) {
        val next = template[i + 1]
        val end =
          when {
            next == '{' -> template.indexOf('}', i + 2).takeIf { it >= 0 }
            shell && next == '(' -> readSubstitution(template, i + 2).takeIf { it < n }
            shell && (next.isLetterOrDigit() || next == '_' || next in "@*#?!-") -> {
              var j = i + 1
              if (next.isLetter() || next == '_') while (j + 1 < n && (template[j + 1].isLetterOrDigit() || template[j + 1] == '_')) j++
              j
            }
            else -> null
          }
        if (end != null) {
          flush()
          sb.append(".*")
          i = end + 1
          continue
        }
      }
      literal.append(c)
      i++
    }
    flush()
    return sb.toString()
  }
}
