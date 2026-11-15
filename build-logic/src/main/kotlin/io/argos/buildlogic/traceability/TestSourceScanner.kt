// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

/** Test IDs of 18 §3 (DSN-18-01) and the requirement tag prefix. */
object TestIds {
  val PREFIXES: List<String> =
    listOf("AG", "SV", "ST", "API", "UI", "E2E", "ACC", "NFR", "SEC", "MX", "CH", "SOAK", "BLD", "ACPT")

  /**
   * T-<prefix>-<number>, an optional capital letter of the catalogue ID (T-SEC-3A) and an optional
   * lower-case letter of a partial check (T-AG-031a, T-SEC-3Aa; 18 appendix A, ADR-0010 C-122, C-182).
   */
  private val pattern =
    Regex("""(?<![A-Za-z0-9-])T-(?:${PREFIXES.joinToString("|")})-[0-9]+[A-Z]?[a-z]?(?![A-Za-z0-9])""")

  /** Catalogue IDs that end in a lower-case letter themselves (18 §3: N-10a..d -> T-NFR-10a..d). */
  private val CATALOG_IDS_ENDING_IN_LOWER_CASE: Set<String> = setOf("T-NFR-10a", "T-NFR-10b", "T-NFR-10c", "T-NFR-10d")

  /** Requirement tag prefix: `@Tag("req:D-24")` (18 §3). */
  const val REQUIREMENT_TAG_PREFIX: String = "req:"

  /** Distinct test IDs in [text], in order of appearance. */
  fun find(text: String?): List<String> =
    if (text == null) emptyList() else pattern.findAll(text).map { it.value }.distinct().toList()

  /**
   * The catalogue ID that [id] checks: [id] without its trailing lower-case letter (T-AG-031a ->
   * T-AG-031, T-SEC-3Aa -> T-SEC-3A, ADR-0010 C-182), [id] itself when it has none or is a catalogue
   * ID that ends in one (T-NFR-10a).
   */
  fun catalogId(id: String): String =
    if (id.length > 1 && id.last() in 'a'..'z' && id[id.length - 2].let { it in '0'..'9' || it in 'A'..'Z' } &&
      id !in CATALOG_IDS_ENDING_IN_LOWER_CASE
    )
      id.dropLast(1)
    else id

  /** True when [id] is a partial check of another catalogue ID (C-122, C-182). */
  fun isPartial(id: String): Boolean = catalogId(id) != id
}

/** A test class of a Java or Kotlin source: its tags apply to every test inside. */
data class SourceClass(
  val className: String,
  val file: String,
  val line: Int,
  val displayName: String?,
  val testIds: List<String>,
  val requirements: List<String>,
  val tags: List<String>,
)

/**
 * A test found in a source file: a JUnit test method (or ArchUnit `@ArchTest` field), or a Vitest or
 * Playwright test title. [dynamic] marks JUnit tests whose runtime names differ from the source
 * (parameterized, repeated, factories, templates); [nameTemplate] is the `name` attribute of
 * `@ParameterizedTest`/`@RepeatedTest`.
 */
data class SourceTest(
  val file: String,
  val line: Int,
  val projectDir: String,
  val sourceSet: String,
  val className: String,
  val methodName: String?,
  val displayName: String?,
  val nameTemplate: String?,
  val dynamic: Boolean,
  val testIds: List<String>,
  val requirements: List<String>,
  val tags: List<String>,
)

/** Result of scanning one Java or Kotlin file. */
data class ScannedFile(val classes: List<SourceClass>, val tests: List<SourceTest>)

/**
 * Finds JUnit 5 tests, their display names and their `@Tag`s in Java and Kotlin sources with a small
 * lexer (comments, strings, text blocks, char literals, Kotlin backtick names) and a brace-depth
 * parser. It does not compile anything, so it stays fast and needs no classpath. Known limits
 * (documented in test/README.md): names built from constants, and tags added by meta-annotations
 * or at runtime, are not seen.
 */
object JavaKotlinTestScanner {

  private val TEST_ANNOTATIONS = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "ArchTest")
  private val DYNAMIC_ANNOTATIONS = setOf("ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
  private val JAVA_CLASS_KEYWORDS = setOf("class", "interface", "enum", "record")
  private val KOTLIN_CLASS_KEYWORDS = setOf("class", "interface", "object")
  private val KOTLIN_MEMBER_KEYWORDS = setOf("fun", "val", "var", "class", "object", "interface")

  private enum class Kind {
    IDENT,
    STRING,
    SYMBOL,
  }

  private data class Token(val kind: Kind, val text: String, val line: Int)

  /** An annotation: simple name, attribute values (unnamed = "value"), nested annotations. */
  private data class Annotation(val name: String, val values: Map<String, String>, val line: Int)

  private class Frame(val name: String, val info: ClassInfo, var depth: Int = -1)

  private class ClassInfo(
    val className: String,
    val line: Int,
    val displayName: String?,
    val testIds: List<String>,
    val requirements: List<String>,
    val tags: List<String>,
  )

  fun scan(file: String, text: String, projectDir: String, sourceSet: String): ScannedFile {
    val kotlin = file.endsWith(".kt") || file.endsWith(".kts")
    val classKeywords = if (kotlin) KOTLIN_CLASS_KEYWORDS else JAVA_CLASS_KEYWORDS
    val tokens = lex(text)
    val classes = mutableListOf<SourceClass>()
    val tests = mutableListOf<SourceTest>()
    var pkg = ""
    val stack = ArrayDeque<Frame>()
    var depth = 0
    var parens = 0
    val pending = mutableListOf<Annotation>()
    var pendingClass: Frame? = null
    var pendingClassParens = 0
    var i = 0

    fun ident(at: Int, value: String? = null) =
      at in tokens.indices && tokens[at].kind == Kind.IDENT && (value == null || tokens[at].text == value)
    fun sym(at: Int, value: String) = at in tokens.indices && tokens[at].kind == Kind.SYMBOL && tokens[at].text == value

    fun emit(name: String, line: Int) {
      val frame = stack.lastOrNull() ?: return
      if (pending.none { it.name in TEST_ANNOTATIONS }) return
      val display = pending.firstOrNull { it.name == "DisplayName" }?.values?.get("value")
      val template =
        pending.firstOrNull { it.name == "ParameterizedTest" || it.name == "RepeatedTest" }?.values?.get("name")
      val own = TestIds.find(display) + TestIds.find(template) + (if (display == null) TestIds.find(name) else emptyList())
      val (reqs, tags) = splitTags(pending)
      tests +=
        SourceTest(
          file = file,
          line = line,
          projectDir = projectDir,
          sourceSet = sourceSet,
          className = frame.info.className,
          methodName = name,
          displayName = display,
          nameTemplate = template,
          dynamic = pending.any { it.name in DYNAMIC_ANNOTATIONS },
          testIds = own.distinct().ifEmpty { frame.info.testIds },
          requirements = (reqs + frame.info.requirements).distinct(),
          tags = (tags + frame.info.tags).distinct(),
        )
    }

    while (i < tokens.size) {
      val t = tokens[i]
      // A Kotlin class without a body (`data class Row(val a: Int)`) ends where the next member
      // starts; never let its header capture an unrelated block.
      if (pendingClass != null && parens == pendingClassParens && kotlin &&
        ((t.kind == Kind.IDENT && t.text in KOTLIN_MEMBER_KEYWORDS) || (t.kind == Kind.SYMBOL && (t.text == "@" || t.text == "}")))
      ) {
        pendingClass = null
      }
      when {
        t.kind == Kind.SYMBOL && t.text == "(" -> parens++
        t.kind == Kind.SYMBOL && t.text == ")" -> parens = maxOf(0, parens - 1)
      }
      when {
        t.kind == Kind.SYMBOL && t.text == "@" -> {
          if (ident(i + 1, "interface")) {
            i += 1
            continue
          }
          val (annotations, next) = parseAnnotation(tokens, i)
          pending += annotations
          i = next
          continue
        }
        t.kind == Kind.IDENT && t.text == "package" && depth == 0 && stack.isEmpty() -> {
          val (name, next) = qualifiedName(tokens, i + 1)
          pkg = name
          i = next
          continue
        }
        t.kind == Kind.IDENT && t.text == "import" && depth == 0 -> {
          var j = i + 1
          if (ident(j, "static")) j++
          j = qualifiedName(tokens, j).second
          if (sym(j, ".") && sym(j + 1, "*")) j += 2
          if (ident(j, "as")) j += 2
          i = j
          continue
        }
        t.kind == Kind.IDENT && t.text in classKeywords && !sym(i - 1, ".") && !sym(i - 1, ":") &&
          isDeclaration(tokens, i, kotlin) -> {
          val hasName = ident(i + 1)
          val name = if (hasName) tokens[i + 1].text else "Companion"
          val outer = stack.lastOrNull()?.info
          val className =
            when {
              outer != null -> outer.className + "$" + name
              pkg.isEmpty() -> name
              else -> "$pkg.$name"
            }
          val display = pending.firstOrNull { it.name == "DisplayName" }?.values?.get("value")
          val (reqs, tags) = splitTags(pending)
          val info =
            ClassInfo(
              className = className,
              line = t.line,
              displayName = display,
              testIds = TestIds.find(display).ifEmpty { outer?.testIds.orEmpty() },
              requirements = (reqs + outer?.requirements.orEmpty()).distinct(),
              tags = (tags + outer?.tags.orEmpty()).distinct(),
            )
          pendingClass = Frame(name, info)
          pendingClassParens = parens
          pending.clear()
          i += if (hasName) 2 else 1
          continue
        }
        t.kind == Kind.SYMBOL && t.text == "{" -> {
          depth++
          pendingClass?.let { frame ->
            frame.depth = depth
            stack.addLast(frame)
            val info = frame.info
            classes +=
              SourceClass(info.className, file, info.line, info.displayName, info.testIds, info.requirements, info.tags)
          }
          pendingClass = null
          pending.clear()
        }
        t.kind == Kind.SYMBOL && t.text == "}" -> {
          if (stack.isNotEmpty() && stack.last().depth == depth) stack.removeLast()
          depth--
          pending.clear()
        }
        t.kind == Kind.SYMBOL && (t.text == ";" || t.text == "=") -> {
          // ArchUnit rules are fields: @ArchTest static final ArchRule name = ...;
          if (pending.isNotEmpty() && ident(i - 1) && atMemberLevel(stack, depth)) emit(tokens[i - 1].text, tokens[i - 1].line)
          pending.clear()
        }
        t.kind == Kind.IDENT && sym(i + 1, "(") && pending.isNotEmpty() -> {
          if (atMemberLevel(stack, depth)) emit(t.text, t.line)
          pending.clear()
        }
      }
      i++
    }
    return ScannedFile(classes, tests)
  }

  private fun atMemberLevel(stack: ArrayDeque<Frame>, depth: Int) = stack.isNotEmpty() && stack.last().depth == depth

  /**
   * Whether the class keyword at [at] starts a declaration. A name must follow (Java `record` also
   * needs `(` or `<` after it, since `record` is a valid variable name); a Kotlin `object` may be
   * nameless (`companion object {`, `object : Foo {`).
   */
  private fun isDeclaration(tokens: List<Token>, at: Int, kotlin: Boolean): Boolean {
    val keyword = tokens[at].text
    val next = tokens.getOrNull(at + 1)
    val named = next != null && next.kind == Kind.IDENT
    return when {
      kotlin && keyword == "object" -> named || (next != null && next.kind == Kind.SYMBOL && (next.text == "{" || next.text == ":"))
      !kotlin && keyword == "record" ->
        named && tokens.getOrNull(at + 2)?.let { it.kind == Kind.SYMBOL && (it.text == "(" || it.text == "<") } == true
      else -> named
    }
  }

  private fun splitTags(annotations: List<Annotation>): Pair<List<String>, List<String>> {
    val values = annotations.filter { it.name == "Tag" }.mapNotNull { it.values["value"]?.trim() }.filter { it.isNotEmpty() }
    val reqs = values.filter { it.startsWith(TestIds.REQUIREMENT_TAG_PREFIX) }.map { it.removePrefix(TestIds.REQUIREMENT_TAG_PREFIX).trim() }
    return reqs.filter { it.isNotEmpty() } to values.filterNot { it.startsWith(TestIds.REQUIREMENT_TAG_PREFIX) }
  }

  /** `a.b.C` starting at [start]; returns the name and the index after it. */
  private fun qualifiedName(tokens: List<Token>, start: Int): Pair<String, Int> {
    val parts = mutableListOf<String>()
    var j = start
    while (j < tokens.size && tokens[j].kind == Kind.IDENT) {
      parts += tokens[j].text
      if (j + 2 < tokens.size && tokens[j + 1].text == "." && tokens[j + 1].kind == Kind.SYMBOL && tokens[j + 2].kind == Kind.IDENT) {
        j += 2
      } else {
        j++
        break
      }
    }
    return parts.joinToString(".") to j
  }

  /**
   * Parses the annotation at [start] ('@'): name (qualified, Kotlin use-site targets skipped) and
   * arguments. Nested annotations (`@Tags({@Tag("a"), @Tag("b")})`, Kotlin `@Tags(Tag("a"))`) are
   * returned as separate annotations after the outer one.
   */
  private fun parseAnnotation(tokens: List<Token>, start: Int): Pair<List<Annotation>, Int> {
    var j = start + 1
    // Kotlin use-site target: @field:Tag, @get:DisplayName
    if (j + 2 < tokens.size && tokens[j].kind == Kind.IDENT && tokens[j + 1].text == ":" && tokens[j + 2].kind == Kind.IDENT) j += 2
    val (qualified, afterName) = qualifiedName(tokens, j)
    if (qualified.isEmpty()) return emptyList<Annotation>() to start + 1
    val name = qualified.substringAfterLast('.')
    val line = tokens[start].line
    j = afterName
    if (j >= tokens.size || tokens[j].kind != Kind.SYMBOL || tokens[j].text != "(") {
      return listOf(Annotation(name, emptyMap(), line)) to j
    }
    val values = LinkedHashMap<String, StringBuilder>()
    val nested = mutableListOf<Annotation>()
    var attribute = "value"
    var parens = 0
    while (j < tokens.size) {
      val t = tokens[j]
      if (t.kind == Kind.SYMBOL && t.text == "(") {
        parens++
      } else if (t.kind == Kind.SYMBOL && t.text == ")") {
        parens--
        if (parens == 0) {
          j++
          break
        }
      } else if (t.kind == Kind.SYMBOL && t.text == "@") {
        val (inner, next) = parseAnnotation(tokens, j)
        nested += inner
        j = next
        continue
      } else if (t.kind == Kind.IDENT && t.text == "Tag" && j + 1 < tokens.size && tokens[j + 1].text == "(" && parens == 1) {
        // Kotlin nested annotation without '@'
        val (inner, next) = parseBareAnnotation(tokens, j)
        nested += inner
        j = next
        continue
      } else if (t.kind == Kind.IDENT && j + 1 < tokens.size && tokens[j + 1].text == "=" && tokens[j + 1].kind == Kind.SYMBOL && parens == 1) {
        attribute = t.text
      } else if (t.kind == Kind.STRING && parens == 1) {
        values.getOrPut(attribute) { StringBuilder() }.append(t.text)
      }
      j++
    }
    return (listOf(Annotation(name, values.mapValues { it.value.toString() }, line)) + nested) to j
  }

  /** Kotlin nested annotation without '@' (`Tag("a")` inside `@Tags(...)`), at the name token. */
  private fun parseBareAnnotation(tokens: List<Token>, at: Int): Pair<List<Annotation>, Int> {
    var j = at + 1
    val values = StringBuilder()
    var parens = 0
    while (j < tokens.size) {
      val t = tokens[j]
      if (t.kind == Kind.SYMBOL && t.text == "(") parens++
      else if (t.kind == Kind.SYMBOL && t.text == ")") {
        parens--
        if (parens == 0) {
          j++
          break
        }
      } else if (t.kind == Kind.STRING && parens == 1) values.append(t.text)
      j++
    }
    return listOf(Annotation(tokens[at].text, mapOf("value" to values.toString()), tokens[at].line)) to j
  }

  /** Tokens: identifiers (Kotlin backtick names included), string contents, one-char symbols. */
  private fun lex(text: String): List<Token> {
    val tokens = ArrayList<Token>(text.length / 4)
    var i = 0
    var line = 1
    val n = text.length
    while (i < n) {
      val c = text[i]
      when {
        c == '\n' -> {
          line++
          i++
        }
        c.isWhitespace() -> i++
        c == '/' && i + 1 < n && text[i + 1] == '/' -> {
          while (i < n && text[i] != '\n') i++
        }
        c == '/' && i + 1 < n && text[i + 1] == '*' -> {
          i += 2
          while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
            if (text[i] == '\n') line++
            i++
          }
          i += 2
        }
        c == '"' && text.startsWith("\"\"\"", i) -> {
          val startLine = line
          val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
          val content = text.substring(i + 3, end)
          line += content.count { it == '\n' }
          tokens += Token(Kind.STRING, content.trimIndent(), startLine)
          i = minOf(n, end + 3)
        }
        c == '"' -> {
          val sb = StringBuilder()
          i++
          while (i < n && text[i] != '"' && text[i] != '\n') {
            val ch = text[i]
            if (ch == '\\' && i + 1 < n) {
              sb.append(
                when (val e = text[i + 1]) {
                  'n' -> '\n'
                  't' -> '\t'
                  else -> e
                }
              )
              i += 2
            } else if (ch == '$' && i + 1 < n && text[i + 1] == '{') {
              // Kotlin string template: keep it verbatim, skip nested braces.
              var braces = 0
              while (i < n) {
                val x = text[i]
                sb.append(x)
                if (x == '{') braces++
                if (x == '}' && --braces == 0) break
                if (x == '\n') break
                i++
              }
              i++
            } else {
              sb.append(ch)
              i++
            }
          }
          i++
          tokens += Token(Kind.STRING, sb.toString(), line)
        }
        c == '\'' -> {
          // char literal: 'x', '\n', '\'', 'A'
          i++
          if (i < n && text[i] == '\\') i += 2 else i++
          while (i < n && text[i] != '\'' && text[i] != '\n') i++
          i++
        }
        c == '`' -> {
          val end = text.indexOf('`', i + 1).let { if (it < 0) n else it }
          tokens += Token(Kind.IDENT, text.substring(i + 1, end), line)
          i = end + 1
        }
        c.isLetter() || c == '_' || c == '$' -> {
          val start = i
          while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
          tokens += Token(Kind.IDENT, text.substring(start, i), line)
        }
        c.isDigit() -> {
          while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
        }
        else -> {
          tokens += Token(Kind.SYMBOL, c.toString(), line)
          i++
        }
      }
    }
    return tokens
  }
}

/**
 * Finds Vitest and Playwright tests whose titles carry test IDs (`it('T-UI-021 …')`,
 * `test('T-E2E-040 …')`, `describe('T-UI-001 …')`) and the requirement annotations of the file
 * (`{ type: 'req', description: 'F-U02' }`, 18 §3). Requirements are associated per file: the
 * skeleton does not parse which test uses which annotation object.
 */
object ScriptTestScanner {
  private val title =
    Regex(
      """\b(describe|it|test)(?:\.(?:only|skip|todo|concurrent|fixme|fail|slow|serial|parallel))*\s*\(\s*(['"`])((?:\\.|(?!\2)[^\\])*)\2"""
    )
  private val requirement =
    Regex("""type\s*:\s*(['"])req\1\s*,\s*description\s*:\s*(['"])([^'"]+)\2""")

  data class Scan(val tests: List<SourceTest>, val untitledTests: Int)

  fun scan(file: String, text: String, projectDir: String, suite: String): Scan {
    val reqs = requirement.findAll(text).map { it.groupValues[3].trim() }.distinct().toList()
    val tests = mutableListOf<SourceTest>()
    var withoutId = 0
    for (match in title.findAll(text)) {
      val name = match.groupValues[3]
      val ids = TestIds.find(name)
      if (ids.isEmpty()) {
        if (match.groupValues[1] != "describe") withoutId++
        continue
      }
      val line = 1 + text.substring(0, match.range.first).count { it == '\n' }
      tests +=
        SourceTest(file, line, projectDir, suite, file, null, name, null, false, ids, reqs, emptyList())
    }
    return Scan(tests, withoutId)
  }
}
