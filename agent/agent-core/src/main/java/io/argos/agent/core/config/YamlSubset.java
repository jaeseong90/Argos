/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The own, bounded YAML subset parser of {@code argos-agent.yml} (10 §15, WO-10; no YAML library in
 * the agent, 10 §10.1). It reads exactly what the configuration needs and rejects the rest:
 *
 * <ul>
 *   <li>block mappings ({@code key: value}, nested by indentation with spaces); keys may be plain,
 *       single- or double-quoted, and plain keys may be dotted ({@code server.tls: false});
 *   <li>block sequences of scalars ({@code - item}, also at the indentation of their key) and of
 *       mappings whose values are scalars ({@code - pattern: ...}, {@code
 *       instrumentation.custom-methods});
 *   <li>single-line flow sequences of scalars ({@code [a, "b", 'c']}, 17 §6.2 example);
 *   <li>plain, single-quoted ({@code ''} escape) and double-quoted scalars ({@code \\ \" \/ \n \t
 *       \r \0 \xXX \\uXXXX}); {@code ~}, {@code null} and an empty value are "no value";
 *   <li>comments ({@code #} at the start of a line or after whitespace) and one leading {@code
 *       ---}.
 * </ul>
 *
 * <p>Outside the subset (one {@link YamlException} with the line number, the caller ignores the
 * whole file): tabs in indentation, flow mappings, block scalars ({@code |}, {@code >}), anchors,
 * aliases, tags, directives, complex keys, multi-line scalars, several documents, nested sequences,
 * duplicate keys, invalid UTF-8 and control characters. Caps (03 §2 input caps): {@value
 * #MAX_FILE_BYTES} bytes, nesting depth {@value #MAX_DEPTH}, {@value #MAX_ENTRIES} entries in the
 * whole document (mapping keys plus block and flow sequence items), {@value #MAX_SCALAR_CHARS}
 * characters per key or scalar.
 *
 * <p>Error messages carry the line number and the reason, never text of the file: a line may hold
 * the token (SEC-06).
 */
final class YamlSubset {
  /** Largest accepted file. */
  static final int MAX_FILE_BYTES = 256 * 1024;

  /** Deepest accepted nesting (mappings and sequences of mappings). */
  static final int MAX_DEPTH = 8;

  /** Most accepted entries: every mapping key and every sequence item counts. */
  static final int MAX_ENTRIES = 2000;

  /** Longest accepted key or scalar, in characters. */
  static final int MAX_SCALAR_CHARS = 8 * 1024;

  /** Kind of a flattened entry. */
  enum Kind {
    /** One scalar value, or no value ({@link Entry#scalar()} null). */
    SCALAR,
    /** A sequence (block or flow) of scalars. */
    LIST,
    /** A block sequence of mappings with scalar values. */
    MAP_LIST
  }

  /** One leaf of the document, its key path joined with {@code .}. */
  static final class Entry {
    private final String key;
    private final Kind kind;
    private final String scalar;
    private final List<String> items;
    private final List<Map<String, String>> maps;
    private final int line;

    private Entry(
        String key,
        Kind kind,
        String scalar,
        List<String> items,
        List<Map<String, String>> maps,
        int line) {
      this.key = key;
      this.kind = kind;
      this.scalar = scalar;
      this.items = items;
      this.maps = maps;
      this.line = line;
    }

    String key() {
      return key;
    }

    Kind kind() {
      return kind;
    }

    /** The value of a {@link Kind#SCALAR} entry, null for "no value". */
    String scalar() {
      return scalar;
    }

    /** The items of a {@link Kind#LIST} entry (null items are "no value"). */
    List<String> items() {
      return items;
    }

    /** The mappings of a {@link Kind#MAP_LIST} entry (field to scalar, null for no value). */
    List<Map<String, String>> maps() {
      return maps;
    }

    /** 1-based line of the key. */
    int line() {
      return line;
    }
  }

  /** The file is outside the subset or over a cap; {@link #line()} 0 means the whole file. */
  static final class YamlException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int line;

    YamlException(int line, String reason) {
      super(reason);
      this.line = line;
    }

    int line() {
      return line;
    }
  }

  private YamlSubset() {}

  /** Parses {@code bytes} (UTF-8, optional BOM). */
  static List<Entry> parse(byte[] bytes) throws YamlException {
    if (bytes.length > MAX_FILE_BYTES) {
      throw new YamlException(0, "the file is larger than " + MAX_FILE_BYTES + " bytes");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new YamlException(0, "the file is not valid UTF-8");
    }
    return parse(text);
  }

  /** Parses {@code text}. */
  static List<Entry> parse(String text) throws YamlException {
    if (text.length() > MAX_FILE_BYTES) {
      throw new YamlException(0, "the file is larger than " + MAX_FILE_BYTES + " bytes");
    }
    Parser parser = new Parser(lines(text));
    MapNode root = parser.parseDocument();
    List<Entry> entries = new ArrayList<Entry>();
    Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
    flatten("", root, entries, seen);
    return entries;
  }

  // --- lines ------------------------------------------------------------------------------------

  /** A content line: indentation, text after it (comments still in), 1-based number. */
  private static final class Line {
    int indent;
    String text;
    final int number;

    Line(int indent, String text, int number) {
      this.indent = indent;
      this.text = text;
      this.number = number;
    }
  }

  private static List<Line> lines(String text) throws YamlException {
    List<Line> lines = new ArrayList<Line>();
    int start = text.length() > 0 && text.charAt(0) == '﻿' ? 1 : 0;
    int number = 0;
    boolean content = false;
    while (start <= text.length()) {
      int end = start;
      while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
        end++;
      }
      number++;
      String line = text.substring(start, end);
      if (end < text.length() && text.charAt(end) == '\r' && end + 1 < text.length()) {
        if (text.charAt(end + 1) == '\n') {
          end++;
        }
      }
      start = end + 1;
      checkCharacters(line, number);
      int indent = 0;
      while (indent < line.length() && line.charAt(indent) == ' ') {
        indent++;
      }
      String rest = line.substring(indent);
      if (rest.startsWith("\t")) {
        throw new YamlException(number, "tab in indentation (use spaces)");
      }
      String trimmed = rest.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (indent == 0 && isDocumentMarker(trimmed, "---")) {
        if (content) {
          throw new YamlException(number, "several documents are not supported");
        }
        content = true;
        continue;
      }
      if (indent == 0 && isDocumentMarker(trimmed, "...")) {
        throw new YamlException(number, "document end markers are not supported");
      }
      if (indent == 0 && trimmed.startsWith("%")) {
        throw new YamlException(number, "directives are not supported");
      }
      content = true;
      lines.add(new Line(indent, rest, number));
    }
    return lines;
  }

  private static boolean isDocumentMarker(String trimmed, String marker) {
    if (!trimmed.startsWith(marker)) {
      return false;
    }
    String after = trimmed.substring(marker.length()).trim();
    return after.isEmpty() || after.startsWith("#");
  }

  private static void checkCharacters(String line, int number) throws YamlException {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if ((c < 0x20 && c != '\t') || c == 0x7F || (c >= 0x80 && c < 0xA0) || c == '￾') {
        throw new YamlException(number, "control character");
      }
    }
  }

  // --- nodes ------------------------------------------------------------------------------------

  private abstract static class Node {
    final int line;

    Node(int line) {
      this.line = line;
    }
  }

  private static final class MapNode extends Node {
    final Map<String, Node> entries = new LinkedHashMap<String, Node>();
    final Map<String, Integer> lines = new LinkedHashMap<String, Integer>();

    MapNode(int line) {
      super(line);
    }
  }

  private static final class SeqNode extends Node {
    final List<Node> items = new ArrayList<Node>();

    SeqNode(int line) {
      super(line);
    }
  }

  private static final class ScalarNode extends Node {
    /** Null for "no value". */
    final String value;

    ScalarNode(String value, int line) {
      super(line);
      this.value = value;
    }
  }

  // --- parser -----------------------------------------------------------------------------------

  private static final class Parser {
    private final List<Line> lines;
    private int position;
    private int entries;

    Parser(List<Line> lines) {
      this.lines = lines;
    }

    MapNode parseDocument() throws YamlException {
      if (lines.isEmpty()) {
        return new MapNode(0);
      }
      Line first = lines.get(0);
      if (isSequenceItem(first.text)) {
        throw new YamlException(first.number, "the top level must be a mapping");
      }
      MapNode root = parseMap(first.indent, 1);
      if (position < lines.size()) {
        Line line = lines.get(position);
        throw new YamlException(
            line.number,
            line.indent < first.indent ? "indentation below the top level" : "unexpected text");
      }
      return root;
    }

    private Line current() {
      return position < lines.size() ? lines.get(position) : null;
    }

    private void count(int number) throws YamlException {
      if (++entries > MAX_ENTRIES) {
        throw new YamlException(number, "more than " + MAX_ENTRIES + " entries");
      }
    }

    private static void depth(int depth, int number) throws YamlException {
      if (depth > MAX_DEPTH) {
        throw new YamlException(number, "nesting deeper than " + MAX_DEPTH + " levels");
      }
    }

    private MapNode parseMap(int indent, int depth) throws YamlException {
      Line start = current();
      depth(depth, start.number);
      MapNode map = new MapNode(start.number);
      while (true) {
        Line line = current();
        if (line == null || line.indent < indent) {
          return map;
        }
        if (line.indent > indent) {
          throw new YamlException(line.number, "unexpected indentation");
        }
        if (isSequenceItem(line.text)) {
          return map; // an indentless sequence item of the parent key, or an error there
        }
        count(line.number);
        int[] end = new int[1];
        String key = parseKey(line.text, line.number, end);
        if (map.entries.containsKey(key)) {
          throw new YamlException(line.number, "duplicate key '" + printable(key) + "'");
        }
        String rest = line.text.substring(end[0]);
        position++;
        Node value;
        if (isBlank(rest)) {
          Line next = current();
          if (next != null && next.indent > indent) {
            value =
                isSequenceItem(next.text)
                    ? parseSequence(next.indent, depth + 1)
                    : parseMap(next.indent, depth + 1);
          } else if (next != null && next.indent == indent && isSequenceItem(next.text)) {
            value = parseSequence(indent, depth + 1);
          } else {
            value = new ScalarNode(null, line.number);
          }
        } else {
          value = parseInlineValue(rest, line.number, this);
        }
        map.entries.put(key, value);
        map.lines.put(key, line.number);
      }
    }

    private SeqNode parseSequence(int indent, int depth) throws YamlException {
      Line start = current();
      depth(depth, start.number);
      SeqNode seq = new SeqNode(start.number);
      while (true) {
        Line line = current();
        if (line == null || line.indent < indent) {
          return seq;
        }
        if (line.indent > indent) {
          throw new YamlException(line.number, "unexpected indentation");
        }
        if (!isSequenceItem(line.text)) {
          return seq; // the next key of the enclosing mapping (indentless sequence)
        }
        count(line.number);
        int offset = 1;
        while (offset < line.text.length() && line.text.charAt(offset) == ' ') {
          offset++;
        }
        String rest = line.text.substring(offset);
        if (isBlank(rest)) {
          position++;
          Line next = current();
          if (next == null || next.indent <= indent) {
            throw new YamlException(line.number, "empty sequence item");
          }
          if (isSequenceItem(next.text)) {
            throw new YamlException(next.number, "nested sequences are not supported");
          }
          seq.items.add(parseMap(next.indent, depth + 1));
        } else if (isSequenceItem(rest)) {
          throw new YamlException(line.number, "nested sequences are not supported");
        } else if (isMappingEntry(rest, line.number)) {
          // "- key: value": a mapping whose keys start at the column of "key"
          line.indent = line.indent + offset;
          line.text = rest;
          seq.items.add(parseMap(line.indent, depth + 1));
        } else {
          position++;
          Node value = parseInlineValue(rest, line.number, this);
          if (value instanceof SeqNode) {
            throw new YamlException(line.number, "nested sequences are not supported");
          }
          seq.items.add(value);
        }
      }
    }
  }

  private static boolean isSequenceItem(String text) {
    return text.equals("-") || text.startsWith("- ") || text.startsWith("-\t");
  }

  private static boolean isBlank(String rest) {
    String trimmed = rest.trim();
    return trimmed.isEmpty() || trimmed.startsWith("#");
  }

  /** True when {@code text} starts with a key followed by {@code :} and a space or the end. */
  private static boolean isMappingEntry(String text, int number) throws YamlException {
    char first = text.charAt(0);
    if (first == '"' || first == '\'') {
      int[] end = new int[1];
      scanQuoted(text, 0, number, end);
      int i = end[0];
      while (i < text.length() && text.charAt(i) == ' ') {
        i++;
      }
      return i < text.length() && text.charAt(i) == ':';
    }
    if (first == '[' || first == '{') {
      return false;
    }
    return plainKeyEnd(text) >= 0;
  }

  /**
   * Index of the {@code :} that ends a plain key: followed by whitespace or the end, before any
   * comment; -1 when there is none.
   */
  private static int plainKeyEnd(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '#' && i > 0 && isSpace(text.charAt(i - 1))) {
        return -1;
      }
      if (c == ':' && (i + 1 == text.length() || isSpace(text.charAt(i + 1)))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t';
  }

  /** The key at the start of {@code text}; {@code end[0]} is set to the index after its colon. */
  private static String parseKey(String text, int number, int[] end) throws YamlException {
    char first = text.charAt(0);
    String key;
    int colon;
    if (first == '"' || first == '\'') {
      key = scanQuoted(text, 0, number, end);
      int i = end[0];
      while (i < text.length() && text.charAt(i) == ' ') {
        i++;
      }
      if (i >= text.length() || text.charAt(i) != ':') {
        throw new YamlException(number, "expected ':' after a quoted key");
      }
      colon = i;
    } else {
      if ("-?:,[]{}#&*!|>%@`".indexOf(first) >= 0) {
        throw new YamlException(number, "unsupported syntax (expected 'key: value')");
      }
      colon = plainKeyEnd(text);
      if (colon < 0) {
        throw new YamlException(number, "expected 'key: value'");
      }
      key = text.substring(0, colon).trim();
    }
    if (colon + 1 < text.length() && !isSpace(text.charAt(colon + 1))) {
      throw new YamlException(number, "expected a space after ':'");
    }
    if (key.isEmpty()) {
      throw new YamlException(number, "empty key");
    }
    if (key.length() > MAX_SCALAR_CHARS) {
      throw new YamlException(number, "key longer than " + MAX_SCALAR_CHARS + " characters");
    }
    end[0] = colon + 1;
    return key;
  }

  /** A value after {@code key:} or {@code - }: a scalar or a single-line flow sequence. */
  private static Node parseInlineValue(String rest, int number, Parser parser)
      throws YamlException {
    String text = rest.trim();
    char first = text.charAt(0);
    switch (first) {
      case '"':
      case '\'':
        {
          int[] end = new int[1];
          String value = scanQuoted(text, 0, number, end);
          requireEnd(text, end[0], number);
          return new ScalarNode(value, number);
        }
      case '[':
        return parseFlowSequence(text, number, parser);
      case '{':
        throw new YamlException(number, "flow mappings are not supported");
      case '|':
      case '>':
        throw new YamlException(number, "block scalars are not supported");
      case '&':
        throw new YamlException(number, "anchors are not supported");
      case '*':
        throw new YamlException(number, "aliases are not supported");
      case '!':
        throw new YamlException(number, "tags are not supported");
      case '%':
      case '@':
      case '`':
        throw new YamlException(number, "reserved indicator at the start of a value");
      default:
        break;
    }
    if (isSequenceItem(text)) {
      throw new YamlException(number, "a sequence cannot follow 'key:' on the same line");
    }
    if (text.startsWith("? ") || text.equals("?")) {
      throw new YamlException(number, "complex keys are not supported");
    }
    String value = plainScalar(text, number);
    return new ScalarNode(isNull(value) ? null : value, number);
  }

  /** A plain scalar up to a comment; {@code ": "} inside it is an error (it would be a mapping). */
  private static String plainScalar(String text, int number) throws YamlException {
    int end = text.length();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '#' && i > 0 && isSpace(text.charAt(i - 1))) {
        end = i;
        break;
      }
      if (c == ':' && (i + 1 == text.length() || isSpace(text.charAt(i + 1)))) {
        throw new YamlException(number, "':' followed by a space inside a value (quote the value)");
      }
    }
    String value = text.substring(0, end).trim();
    if (value.length() > MAX_SCALAR_CHARS) {
      throw new YamlException(number, "value longer than " + MAX_SCALAR_CHARS + " characters");
    }
    return value;
  }

  /** YAML 1.2 core schema nulls: empty, {@code ~}, {@code null}, {@code Null}, {@code NULL}. */
  private static boolean isNull(String value) {
    return value.isEmpty()
        || value.equals("~")
        || value.equals("null")
        || value.equals("Null")
        || value.equals("NULL");
  }

  private static void requireEnd(String text, int index, int number) throws YamlException {
    if (!isBlank(text.substring(index))) {
      throw new YamlException(number, "unexpected text after a quoted value");
    }
    if (index < text.length() && text.charAt(index) == '#') {
      throw new YamlException(number, "a comment needs a space before '#'");
    }
  }

  /**
   * {@code [a, "b", 'c']} on one line; items are scalars. Every item counts against the {@value
   * #MAX_ENTRIES} entries of the whole document ({@code parser}), like a block sequence item.
   */
  private static SeqNode parseFlowSequence(String text, int number, Parser parser)
      throws YamlException {
    SeqNode seq = new SeqNode(number);
    int i = 1;
    boolean expectItem = true;
    while (true) {
      while (i < text.length() && isSpace(text.charAt(i))) {
        i++;
      }
      if (i >= text.length()) {
        throw new YamlException(number, "flow sequence not closed on its line");
      }
      char c = text.charAt(i);
      if (c == ']') {
        if (expectItem && !seq.items.isEmpty()) {
          throw new YamlException(number, "empty item in a flow sequence");
        }
        requireEnd(text, i + 1, number);
        return seq;
      }
      if (!expectItem) {
        if (c != ',') {
          throw new YamlException(number, "expected ',' or ']' in a flow sequence");
        }
        i++;
        expectItem = true;
        continue;
      }
      if (c == ',') {
        throw new YamlException(number, "empty item in a flow sequence");
      }
      if (c == '[' || c == '{') {
        throw new YamlException(number, "nested flow collections are not supported");
      }
      if ("&*!|>%@`".indexOf(c) >= 0) {
        throw new YamlException(number, "unsupported syntax in a flow sequence");
      }
      String value;
      if (c == '"' || c == '\'') {
        int[] end = new int[1];
        value = scanQuoted(text, i, number, end);
        i = end[0];
      } else {
        int start = i;
        while (i < text.length() && text.charAt(i) != ',' && text.charAt(i) != ']') {
          char p = text.charAt(i);
          if (p == ':' && i + 1 < text.length() && isSpace(text.charAt(i + 1))) {
            throw new YamlException(number, "flow mappings are not supported");
          }
          if (p == '#' && isSpace(text.charAt(i - 1))) {
            throw new YamlException(number, "flow sequence not closed on its line");
          }
          if (p == '[' || p == '{' || p == '}') {
            throw new YamlException(number, "nested flow collections are not supported");
          }
          i++;
        }
        value = text.substring(start, i).trim();
        if (value.length() > MAX_SCALAR_CHARS) {
          throw new YamlException(number, "value longer than " + MAX_SCALAR_CHARS + " characters");
        }
        if (isNull(value)) {
          value = null;
        }
      }
      parser.count(number); // one cap for the document: keys, block and flow sequence items
      seq.items.add(new ScalarNode(value, number));
      expectItem = false;
    }
  }

  /**
   * The quoted scalar that starts at {@code text[start]}; {@code end[0]} is set to the index after
   * the closing quote. The scalar must close on its line.
   */
  private static String scanQuoted(String text, int start, int number, int[] end)
      throws YamlException {
    char quote = text.charAt(start);
    StringBuilder out = new StringBuilder();
    int i = start + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (out.length() > MAX_SCALAR_CHARS) {
        throw new YamlException(number, "value longer than " + MAX_SCALAR_CHARS + " characters");
      }
      if (quote == '\'') {
        if (c == '\'') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
            out.append('\'');
            i += 2;
            continue;
          }
          end[0] = i + 1;
          return out.toString();
        }
        out.append(c);
        i++;
        continue;
      }
      if (c == '"') {
        end[0] = i + 1;
        return out.toString();
      }
      if (c != '\\') {
        out.append(c);
        i++;
        continue;
      }
      if (i + 1 >= text.length()) {
        throw new YamlException(number, "quoted value not closed on its line");
      }
      char escape = text.charAt(i + 1);
      i += 2;
      switch (escape) {
        case '\\':
          out.append('\\');
          break;
        case '"':
          out.append('"');
          break;
        case '/':
          out.append('/');
          break;
        case ' ':
          out.append(' ');
          break;
        case 'n':
          out.append('\n');
          break;
        case 't':
          out.append('\t');
          break;
        case 'r':
          out.append('\r');
          break;
        case '0':
          out.append('\0');
          break;
        case 'x':
          out.append(hex(text, i, 2, number));
          i += 2;
          break;
        case 'u':
          out.append(hex(text, i, 4, number));
          i += 4;
          break;
        default:
          throw new YamlException(number, "unsupported escape in a double-quoted value");
      }
    }
    throw new YamlException(number, "quoted value not closed on its line");
  }

  private static char hex(String text, int start, int digits, int number) throws YamlException {
    if (start + digits > text.length()) {
      throw new YamlException(number, "unsupported escape in a double-quoted value");
    }
    int value = 0;
    for (int i = start; i < start + digits; i++) {
      int digit = Character.digit(text.charAt(i), 16);
      if (digit < 0) {
        throw new YamlException(number, "unsupported escape in a double-quoted value");
      }
      value = value * 16 + digit;
    }
    return (char) value;
  }

  // --- flattening -------------------------------------------------------------------------------

  private static void flatten(
      String prefix, MapNode map, List<Entry> out, Map<String, Integer> seen) throws YamlException {
    for (Map.Entry<String, Node> e : map.entries.entrySet()) {
      String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
      int line = map.lines.get(e.getKey());
      Node node = e.getValue();
      if (node instanceof MapNode) {
        flatten(key, (MapNode) node, out, seen);
        continue;
      }
      if (seen.containsKey(key)) {
        throw new YamlException(line, "key '" + printable(key) + "' is defined twice");
      }
      seen.put(key, line);
      if (node instanceof ScalarNode) {
        out.add(new Entry(key, Kind.SCALAR, ((ScalarNode) node).value, null, null, line));
      } else {
        out.add(sequenceEntry(key, (SeqNode) node, line));
      }
    }
  }

  private static Entry sequenceEntry(String key, SeqNode seq, int line) throws YamlException {
    boolean scalars = true;
    boolean maps = true;
    for (Node item : seq.items) {
      scalars &= item instanceof ScalarNode;
      maps &= item instanceof MapNode;
    }
    if (seq.items.isEmpty() || scalars) {
      List<String> items = new ArrayList<String>(seq.items.size());
      for (Node item : seq.items) {
        items.add(((ScalarNode) item).value);
      }
      return new Entry(key, Kind.LIST, null, Collections.unmodifiableList(items), null, line);
    }
    if (!maps) {
      int itemLine = line;
      for (Node item : seq.items) {
        if (item.getClass() != seq.items.get(0).getClass()) {
          itemLine = item.line;
          break;
        }
      }
      throw new YamlException(itemLine, "a sequence mixes values and mappings");
    }
    List<Map<String, String>> list = new ArrayList<Map<String, String>>(seq.items.size());
    for (Node item : seq.items) {
      Map<String, String> fields = new LinkedHashMap<String, String>();
      for (Map.Entry<String, Node> field : ((MapNode) item).entries.entrySet()) {
        if (!(field.getValue() instanceof ScalarNode)) {
          throw new YamlException(
              ((MapNode) item).lines.get(field.getKey()),
              "a mapping inside a sequence may only hold values");
        }
        fields.put(field.getKey(), ((ScalarNode) field.getValue()).value);
      }
      list.add(Collections.unmodifiableMap(fields));
    }
    return new Entry(key, Kind.MAP_LIST, null, null, Collections.unmodifiableList(list), line);
  }

  /**
   * A key for an error message: {@code argos_} runs masked before the cut (a key that holds a token
   * never shows part of it, SEC-06), control characters replaced, at most 64 characters.
   */
  static String printable(String key) {
    return TokenHolder.quoteValue(key, 64);
  }
}
