// SPDX-License-Identifier: Apache-2.0
//
// Tokenizer for CodeBlock (docs/15 §6.6, docs/16 §9.3 SafeCode). It turns SQL, stack traces and
// configuration text into typed tokens that CodeBlock renders as React elements, one text node per
// token. No HTML string is ever produced (SEC-60): the concatenated token texts always equal the
// input, which codeTokenizer.test.ts checks for every payload.

export type CodeLanguage = 'sql' | 'stack' | 'config' | 'text';

export type CodeTokenType =
  | 'text'
  | 'keyword'
  | 'string'
  | 'number'
  | 'comment'
  | 'placeholder'
  | 'exception'
  | 'heading'
  | 'frame'
  | 'location'
  | 'key'
  | 'muted';

export interface CodeToken {
  readonly type: CodeTokenType;
  readonly text: string;
}

/** SQL keywords highlighted by CodeBlock (ANSI plus common vendor words). */
const SQL_KEYWORDS = new Set(
  (
    'SELECT FROM WHERE AND OR NOT IN IS NULL LIKE ILIKE BETWEEN EXISTS AS ON JOIN INNER LEFT RIGHT ' +
    'FULL OUTER CROSS NATURAL USING GROUP BY ORDER HAVING LIMIT OFFSET FETCH FIRST NEXT ROWS ONLY ' +
    'UNION ALL DISTINCT INSERT INTO VALUES UPDATE SET DELETE MERGE MATCHED THEN WHEN CASE ELSE END ' +
    'CREATE ALTER DROP TABLE INDEX VIEW SEQUENCE PROCEDURE FUNCTION TRIGGER PRIMARY KEY FOREIGN ' +
    'REFERENCES CONSTRAINT DEFAULT CHECK UNIQUE WITH RECURSIVE RETURNING ASC DESC NULLS FOR ' +
    'LOCK SHARE NOWAIT SKIP LOCKED TOP BEGIN COMMIT ROLLBACK SAVEPOINT TRANSACTION CALL EXEC ' +
    'EXECUTE DECLARE TRUE FALSE COUNT SUM AVG MIN MAX COALESCE CAST OVER PARTITION WINDOW ROWNUM ' +
    'DUAL IF REPLACE IGNORE DUPLICATE LATERAL APPLY PIVOT'
  ).split(' '),
);

function push(tokens: CodeToken[], type: CodeTokenType, text: string): void {
  if (text.length === 0) {
    return;
  }
  const last = tokens[tokens.length - 1];
  if (last?.type === type && (type === 'text' || type === 'muted')) {
    tokens[tokens.length - 1] = { type, text: last.text + text };
    return;
  }
  tokens.push({ type, text });
}

const SQL_WORD = /[A-Za-z_][A-Za-z0-9_$]*/y;
const SQL_NUMBER = /\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/y;
const SQL_WHITESPACE = /\s+/y;
const SQL_NAMED_PLACEHOLDER = /(?::[A-Za-z_][A-Za-z0-9_]*|\$\d+)/y;

function matchAt(pattern: RegExp, text: string, index: number): string | null {
  pattern.lastIndex = index;
  const match = pattern.exec(text);
  return match === null ? null : match[0];
}

/** Index after a quoted run starting at `start` (quote doubled = escaped); end of text if open. */
function quotedEnd(text: string, start: number, quote: string): number {
  let i = start + 1;
  while (i < text.length) {
    if (text[i] === quote) {
      if (text[i + 1] === quote) {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i += 1;
  }
  return text.length;
}

/** Tokenizes SQL: keywords, strings, numbers, comments, bind placeholders, the rest as text. */
export function tokenizeSql(text: string): CodeToken[] {
  const tokens: CodeToken[] = [];
  let i = 0;
  while (i < text.length) {
    const ch = text[i] ?? '';
    const next = text[i + 1] ?? '';
    if (ch === '-' && next === '-') {
      const end = text.indexOf('\n', i);
      const stop = end === -1 ? text.length : end;
      push(tokens, 'comment', text.slice(i, stop));
      i = stop;
      continue;
    }
    if (ch === '/' && next === '*') {
      const end = text.indexOf('*/', i + 2);
      const stop = end === -1 ? text.length : end + 2;
      push(tokens, 'comment', text.slice(i, stop));
      i = stop;
      continue;
    }
    if (ch === "'") {
      const stop = quotedEnd(text, i, "'");
      push(tokens, 'string', text.slice(i, stop));
      i = stop;
      continue;
    }
    if (ch === '"' || ch === '`') {
      const stop = quotedEnd(text, i, ch);
      push(tokens, 'text', text.slice(i, stop));
      i = stop;
      continue;
    }
    if (ch === '?') {
      push(tokens, 'placeholder', ch);
      i += 1;
      continue;
    }
    const placeholder = ch === ':' || ch === '$' ? matchAt(SQL_NAMED_PLACEHOLDER, text, i) : null;
    if (placeholder !== null) {
      push(tokens, 'placeholder', placeholder);
      i += placeholder.length;
      continue;
    }
    const whitespace = matchAt(SQL_WHITESPACE, text, i);
    if (whitespace !== null) {
      push(tokens, 'text', whitespace);
      i += whitespace.length;
      continue;
    }
    const number = /\d/.test(ch) ? matchAt(SQL_NUMBER, text, i) : null;
    if (number !== null) {
      push(tokens, 'number', number);
      i += number.length;
      continue;
    }
    const word = matchAt(SQL_WORD, text, i);
    if (word !== null) {
      push(tokens, SQL_KEYWORDS.has(word.toUpperCase()) ? 'keyword' : 'text', word);
      i += word.length;
      continue;
    }
    push(tokens, 'text', ch);
    i += 1;
  }
  return tokens;
}

const FRAME_LINE = /^(\s*)(at\s+)([^(]*)(\(.*\))?(.*)$/;
const CAUSED_BY = /^(\s*)(Caused by:|Suppressed:)(.*)$/;
const MORE_LINE = /^\s*\.\.\.\s*\d+\s+(?:more|common frames omitted)\s*$/;
const EXCEPTION_LINE = /^(\s*)([\w$.]+(?:Exception|Error|Throwable|Failure)\b[^\n]*)$/;

/** Splits text into lines, keeping each line's terminator on the line ("\r\n", "\n" or "\r"). */
function splitKeepingNewlines(text: string): string[] {
  const lines: string[] = [];
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '\n' || ch === '\r') {
      const end = ch === '\r' && text[i + 1] === '\n' ? i + 2 : i + 1;
      lines.push(text.slice(start, end));
      start = end;
      i = end - 1;
    }
  }
  if (start < text.length) {
    lines.push(text.slice(start));
  }
  return lines;
}

function lineBody(line: string): [string, string] {
  const match = /(\r\n|\n|\r)$/.exec(line);
  return match === null ? [line, ''] : [line.slice(0, match.index), match[0]];
}

/** Tokenizes a Java stack trace: exception lines, "at" frames with locations, "Caused by". */
export function tokenizeStack(text: string): CodeToken[] {
  const tokens: CodeToken[] = [];
  for (const line of splitKeepingNewlines(text)) {
    const [body, newline] = lineBody(line);
    const frame = FRAME_LINE.exec(body);
    const caused = CAUSED_BY.exec(body);
    const exception = EXCEPTION_LINE.exec(body);
    if (frame !== null) {
      push(tokens, 'text', frame[1] ?? '');
      push(tokens, 'muted', frame[2] ?? '');
      push(tokens, 'frame', frame[3] ?? '');
      push(tokens, 'location', frame[4] ?? '');
      push(tokens, 'text', frame[5] ?? '');
    } else if (caused !== null) {
      push(tokens, 'text', caused[1] ?? '');
      push(tokens, 'heading', caused[2] ?? '');
      push(tokens, 'exception', caused[3] ?? '');
    } else if (MORE_LINE.test(body)) {
      push(tokens, 'muted', body);
    } else if (exception !== null) {
      push(tokens, 'text', exception[1] ?? '');
      push(tokens, 'exception', exception[2] ?? '');
    } else {
      push(tokens, 'text', body);
    }
    push(tokens, 'text', newline);
  }
  return tokens;
}

const CONFIG_COMMENT = /^\s*[#;!]/;
const CONFIG_ENTRY = /^(\s*)([^\s=:#;!][^=:]*?)(\s*[=:]\s*)(.*)$/;

/** Tokenizes key=value / key: value configuration text with # comments. */
export function tokenizeConfig(text: string): CodeToken[] {
  const tokens: CodeToken[] = [];
  for (const line of splitKeepingNewlines(text)) {
    const [body, newline] = lineBody(line);
    const entry = CONFIG_ENTRY.exec(body);
    if (CONFIG_COMMENT.test(body)) {
      push(tokens, 'comment', body);
    } else if (entry !== null) {
      push(tokens, 'text', entry[1] ?? '');
      push(tokens, 'key', entry[2] ?? '');
      push(tokens, 'muted', entry[3] ?? '');
      push(tokens, 'string', entry[4] ?? '');
    } else {
      push(tokens, 'text', body);
    }
    push(tokens, 'text', newline);
  }
  return tokens;
}

/** Tokenizes `text` for `language`; `text` is a single plain token. */
export function tokenize(text: string, language: CodeLanguage): CodeToken[] {
  switch (language) {
    case 'sql':
      return tokenizeSql(text);
    case 'stack':
      return tokenizeStack(text);
    case 'config':
      return tokenizeConfig(text);
    case 'text':
      return text.length === 0 ? [] : [{ type: 'text', text }];
  }
}

/**
 * Splits tokens into display lines at "\n" (a "\r\n" or lone "\r" ends a line too); tokens that
 * span lines (block comments, multi-line strings) are cut at the line breaks. Line terminators
 * are not part of any line.
 */
export function toLines(tokens: readonly CodeToken[]): CodeToken[][] {
  const lines: CodeToken[][] = [[]];
  for (const token of tokens) {
    const parts = token.text.split(/\r\n|\n|\r/);
    parts.forEach((part, index) => {
      if (index > 0) {
        lines.push([]);
      }
      if (part.length > 0) {
        lines[lines.length - 1]?.push({ type: token.type, text: part });
      }
    });
  }
  if (lines.length > 1 && lines[lines.length - 1]?.length === 0) {
    lines.pop();
  }
  return lines;
}
