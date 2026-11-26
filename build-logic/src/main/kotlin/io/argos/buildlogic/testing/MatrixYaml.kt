// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

/**
 * The YAML subset of the files in test/matrix (test/matrix/README.md "File format"), parsed by hand so that
 * build-logic needs no YAML library:
 * - block mappings (`key: value`, `key:` followed by a more indented block) and block sequences
 *   (`- item`, `- key: value` starting a mapping); a sequence may sit at the indentation of its key;
 * - scalars: plain, "double quoted" (escapes `\"` and `\\`) or 'single quoted' (`''`);
 * - flow sequences of scalars on one line: `[a, "b, c", 'd']`, `[]`;
 * - comments: `#` at the start of a line or after a space, outside quotes; blank lines.
 * Everything else (tabs, anchors, aliases, tags, block scalars, flow mappings, multi-line flow
 * sequences, documents) is rejected with the line number, never guessed.
 */
object MatrixYaml {

  sealed class Node {
    abstract val line: Int
  }

  data class Scalar(val value: String, override val line: Int) : Node()

  data class Sequence(val items: List<Node>, override val line: Int) : Node()

  data class Mapping(val entries: LinkedHashMap<String, Node>, val keyLines: Map<String, Int>, override val line: Int) :
    Node()

  class ParseException(message: String) : RuntimeException(message)

  private data class Line(val indent: Int, val text: String, val number: Int)

  private val keyPattern = Regex("""^([A-Za-z0-9_.-]+):(?:\s+(.*))?$""")

  /** Parses [text]; [source] names the file in error messages. An empty document is an empty mapping. */
  fun parse(text: String, source: String): Node {
    val lines = mutableListOf<Line>()
    text.lines().forEachIndexed { index, raw ->
      val number = index + 1
      if (raw.contains('\t')) throw ParseException("$source:$number: tab characters are not allowed (use spaces)")
      val content = stripComment(raw, source, number).trimEnd()
      if (content.isBlank()) return@forEachIndexed
      val trimmed = content.trimStart()
      if (trimmed == "---" || trimmed == "...") {
        throw ParseException("$source:$number: YAML document markers are not supported (one document per file)")
      }
      lines += Line(content.length - trimmed.length, trimmed, number)
    }
    if (lines.isEmpty()) return Mapping(LinkedHashMap(), emptyMap(), 1)
    val parser = Parser(lines, source)
    if (lines[0].indent != 0) throw ParseException("$source:${lines[0].number}: the document must start at column 1")
    val root = parser.block(0, 0)
    if (parser.position < lines.size) {
      val line = lines[parser.position]
      throw ParseException("$source:${line.number}: unexpected indentation or content '${line.text}'")
    }
    return root
  }

  private fun stripComment(raw: String, source: String, number: Int): String {
    var quote: Char? = null
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      when {
        quote == '"' && c == '\\' -> i++
        quote != null && c == quote -> {
          if (quote == '\'' && i + 1 < raw.length && raw[i + 1] == '\'') i++ else quote = null
        }
        quote == null && (c == '"' || c == '\'') && (i == 0 || raw[i - 1] in " [,:-") -> quote = c
        quote == null && c == '#' && (i == 0 || raw[i - 1] == ' ') -> return raw.substring(0, i)
      }
      i++
    }
    if (quote != null) throw ParseException("$source:$number: unterminated quoted scalar")
    return raw
  }

  private class Parser(val lines: MutableList<Line>, val source: String) {
    var position = 0

    fun error(line: Line, message: String): Nothing = throw ParseException("$source:${line.number}: $message")

    fun isItem(line: Line) = line.text == "-" || line.text.startsWith("- ")

    fun block(index: Int, indent: Int): Node {
      position = index
      val first = lines[index]
      return if (isItem(first)) sequence(indent) else mapping(indent)
    }

    fun mapping(indent: Int): Mapping {
      val entries = LinkedHashMap<String, Node>()
      val keyLines = LinkedHashMap<String, Int>()
      val start = lines[position]
      while (position < lines.size) {
        val line = lines[position]
        if (line.indent < indent) break
        if (line.indent > indent) error(line, "unexpected indentation")
        if (isItem(line)) error(line, "a sequence item where a mapping key was expected")
        val match = keyPattern.matchEntire(line.text) ?: error(line, "expected 'key: value', got '${line.text}'")
        val key = match.groupValues[1]
        if (key in entries) error(line, "duplicate key '$key'")
        val rest = match.groupValues[2]
        position++
        val value: Node =
          if (rest.isNotEmpty()) {
            scalarOrFlow(rest, line)
          } else if (position < lines.size && lines[position].indent > indent) {
            block(position, lines[position].indent)
          } else if (position < lines.size && lines[position].indent == indent && isItem(lines[position])) {
            sequence(indent)
          } else {
            Scalar("", line.number)
          }
        entries[key] = value
        keyLines[key] = line.number
      }
      return Mapping(entries, keyLines, start.number)
    }

    fun sequence(indent: Int): Sequence {
      val items = mutableListOf<Node>()
      val start = lines[position]
      while (position < lines.size) {
        val line = lines[position]
        if (line.indent < indent) break
        if (line.indent > indent) error(line, "unexpected indentation")
        if (!isItem(line)) break
        val content = line.text.removePrefix("-").trimStart()
        if (content.isEmpty()) {
          position++
          if (position >= lines.size || lines[position].indent <= indent) error(line, "empty sequence item")
          items += block(position, lines[position].indent)
        } else if (content.startsWith("- ") || content == "-") {
          error(line, "nested sequences on one line are not supported")
        } else if (keyPattern.matches(content) && !content.startsWith("\"") && !content.startsWith("'")) {
          // "- key: value": a mapping whose keys sit at the column of "key".
          val column = line.indent + (line.text.length - content.length)
          lines[position] = Line(column, content, line.number)
          items += mapping(column)
        } else {
          position++
          items += scalarOrFlow(content, line)
        }
      }
      return Sequence(items, start.number)
    }

    fun scalarOrFlow(text: String, line: Line): Node =
      if (text.startsWith("[")) {
        if (!text.endsWith("]")) error(line, "flow sequences must close on the same line")
        val inner = text.substring(1, text.length - 1)
        Sequence(splitFlow(inner, line).map { Scalar(scalar(it, line), line.number) }, line.number)
      } else {
        Scalar(scalar(text, line), line.number)
      }

    fun splitFlow(inner: String, line: Line): List<String> {
      if (inner.isBlank()) return emptyList()
      val parts = mutableListOf<String>()
      val current = StringBuilder()
      var quote: Char? = null
      var i = 0
      while (i < inner.length) {
        val c = inner[i]
        when {
          quote == '"' && c == '\\' && i + 1 < inner.length -> {
            current.append(c).append(inner[i + 1])
            i++
          }
          quote != null && c == quote -> {
            if (quote == '\'' && i + 1 < inner.length && inner[i + 1] == '\'') {
              current.append("''")
              i++
            } else {
              quote = null
              current.append(c)
            }
          }
          quote == null && (c == '"' || c == '\'') && current.isBlank() -> {
            quote = c
            current.append(c)
          }
          quote == null && (c == '[' || c == ']' || c == '{' || c == '}') ->
            error(line, "nested flow collections are not supported")
          quote == null && c == ',' -> {
            parts += current.toString().trim()
            current.clear()
          }
          else -> current.append(c)
        }
        i++
      }
      parts += current.toString().trim()
      if (parts.any { it.isEmpty() }) error(line, "empty item in a flow sequence")
      return parts
    }

    fun scalar(raw: String, line: Line): String {
      val text = raw.trim()
      if (text.startsWith("\"")) {
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
          val c = text[i]
          if (c == '\\') {
            if (i + 1 >= text.length) error(line, "dangling escape in a quoted scalar")
            when (val next = text[i + 1]) {
              '"', '\\' -> out.append(next)
              else -> error(line, "unsupported escape '\\$next' (only \\\" and \\\\)")
            }
            i += 2
            continue
          }
          if (c == '"') {
            if (i != text.length - 1) error(line, "text after a quoted scalar")
            return out.toString()
          }
          out.append(c)
          i++
        }
        error(line, "unterminated quoted scalar")
      }
      if (text.startsWith("'")) {
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
          val c = text[i]
          if (c == '\'') {
            if (i + 1 < text.length && text[i + 1] == '\'') {
              out.append('\'')
              i += 2
              continue
            }
            if (i != text.length - 1) error(line, "text after a quoted scalar")
            return out.toString()
          }
          out.append(c)
          i++
        }
        error(line, "unterminated quoted scalar")
      }
      if (text.isNotEmpty() && text[0] in "&*!|>{%@`") {
        error(line, "'${text[0]}' (anchors, aliases, tags, block scalars, flow mappings) is not supported; quote the value")
      }
      if (text.startsWith("]")) error(line, "unbalanced ']'")
      return text
    }
  }
}
