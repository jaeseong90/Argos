// SPDX-License-Identifier: Apache-2.0
//
// A bounded YAML subset for the required-elements files (ui/e2e/required/<screen>.yaml, docs/18
// §13). No YAML library is a dependency of the UI, and these files need little: block mappings and
// sequences by indentation (spaces only), `- key: value` items, plain / 'single' / "double" quoted
// scalars, numbers, true/false/null, flow sequences of scalars ([a, 'b']) and # comments. Anything
// else (anchors, tags, multi-line scalars, flow mappings, tabs) is rejected with its line number.

export type YamlValue =
  string | number | boolean | null | YamlValue[] | { [key: string]: YamlValue };

interface Line {
  readonly no: number;
  indent: number;
  content: string;
}

export class YamlError extends Error {
  constructor(line: number, message: string) {
    super(`line ${String(line)}: ${message}`);
  }
}

/** Removes a trailing comment (a # after whitespace, outside quotes). */
function stripComment(text: string): string {
  let quote: string | null = null;
  for (let i = 0; i < text.length; i += 1) {
    const c = text.charAt(i);
    if (quote !== null) {
      if (c === '\\' && quote === '"') {
        i += 1;
      } else if (c === quote) {
        quote = null;
      }
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === '#' && (i === 0 || /\s/.test(text.charAt(i - 1)))) {
      return text.slice(0, i).trimEnd();
    }
  }
  return text.trimEnd();
}

function lines(source: string): Line[] {
  const out: Line[] = [];
  source.split(/\r?\n/).forEach((raw, index) => {
    const no = index + 1;
    if (/^\s*\t/.test(raw)) {
      throw new YamlError(no, 'tabs are not allowed for indentation');
    }
    const content = stripComment(raw);
    if (content.trim() === '' || content.trim() === '---') {
      return;
    }
    const indent = content.length - content.trimStart().length;
    out.push({ no, indent, content: content.trim() });
  });
  return out;
}

/** The index of the ':' that ends a mapping key, or -1. */
function keyColon(text: string): number {
  let quote: string | null = null;
  for (let i = 0; i < text.length; i += 1) {
    const c = text.charAt(i);
    if (quote !== null) {
      if (c === '\\' && quote === '"') {
        i += 1;
      } else if (c === quote) {
        quote = null;
      }
    } else if ((c === '"' || c === "'") && i === 0) {
      quote = c;
    } else if (c === ':' && (i === text.length - 1 || text.charAt(i + 1) === ' ')) {
      return i;
    }
  }
  return -1;
}

function unquote(text: string, no: number): string {
  if (text.startsWith("'")) {
    if (!text.endsWith("'") || text.length < 2) {
      throw new YamlError(no, `unterminated single-quoted scalar: ${text}`);
    }
    return text.slice(1, -1).replaceAll("''", "'");
  }
  if (text.startsWith('"')) {
    if (!text.endsWith('"') || text.length < 2) {
      throw new YamlError(no, `unterminated double-quoted scalar: ${text}`);
    }
    try {
      return JSON.parse(text) as string;
    } catch {
      throw new YamlError(no, `invalid double-quoted scalar: ${text}`);
    }
  }
  return text;
}

function splitFlow(inner: string, no: number): string[] {
  const items: string[] = [];
  let current = '';
  let quote: string | null = null;
  for (let i = 0; i < inner.length; i += 1) {
    const c = inner.charAt(i);
    if (quote !== null) {
      current += c;
      if (c === '\\' && quote === '"') {
        current += inner.charAt(i + 1);
        i += 1;
      } else if (c === quote) {
        quote = null;
      }
    } else if (c === '"' || c === "'") {
      quote = c;
      current += c;
    } else if (c === ',') {
      items.push(current.trim());
      current = '';
    } else if (c === '[' || c === '{') {
      throw new YamlError(no, 'nested flow collections are not supported');
    } else {
      current += c;
    }
  }
  if (quote !== null) {
    throw new YamlError(no, 'unterminated quote in a flow sequence');
  }
  if (current.trim() !== '') {
    items.push(current.trim());
  }
  return items;
}

function scalar(text: string, no: number): YamlValue {
  if (/^[&*!|>%@`]/.test(text) || text.startsWith('{')) {
    throw new YamlError(no, `unsupported YAML syntax: ${text}`);
  }
  if (text.startsWith('[')) {
    if (!text.endsWith(']')) {
      throw new YamlError(no, `unterminated flow sequence: ${text}`);
    }
    return splitFlow(text.slice(1, -1), no).map((item) => scalar(item, no));
  }
  if (text.startsWith("'") || text.startsWith('"')) {
    return unquote(text, no);
  }
  if (text === 'true') return true;
  if (text === 'false') return false;
  if (text === 'null' || text === '~') return null;
  if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
  return text;
}

class Parser {
  private index = 0;
  private readonly ls: Line[];

  constructor(ls: Line[]) {
    this.ls = ls;
  }

  parse(): YamlValue {
    if (this.ls.length === 0) {
      return null;
    }
    const first = this.ls[0];
    if (first === undefined || first.indent !== 0) {
      throw new YamlError(first?.no ?? 1, 'the document must start at column 1');
    }
    const value = this.block(0);
    const rest = this.ls[this.index];
    if (rest !== undefined) {
      throw new YamlError(rest.no, 'unexpected indentation');
    }
    return value;
  }

  private peek(): Line | undefined {
    return this.ls[this.index];
  }

  private block(indent: number): YamlValue {
    const line = this.peek();
    if (line === undefined) {
      return null;
    }
    return line.content === '-' || line.content.startsWith('- ')
      ? this.sequence(indent)
      : this.mapping(indent);
  }

  private sequence(indent: number): YamlValue[] {
    const out: YamlValue[] = [];
    for (
      let line = this.peek();
      line?.indent === indent && (line.content === '-' || line.content.startsWith('- '));
      line = this.peek()
    ) {
      const rest = line.content === '-' ? '' : line.content.slice(2).trimStart();
      if (rest === '') {
        this.index += 1;
        const next = this.peek();
        out.push(next !== undefined && next.indent > indent ? this.block(next.indent) : null);
      } else if (keyColon(rest) > 0) {
        // `- key: value`: a mapping whose first line starts where `key` starts.
        line.indent = indent + (line.content.length - rest.length);
        line.content = rest;
        out.push(this.mapping(line.indent));
      } else {
        this.index += 1;
        out.push(scalar(rest, line.no));
      }
    }
    return out;
  }

  private mapping(indent: number): Record<string, YamlValue> {
    const out: Record<string, YamlValue> = {};
    for (
      let line = this.peek();
      line?.indent === indent && !(line.content === '-' || line.content.startsWith('- '));
      line = this.peek()
    ) {
      const colon = keyColon(line.content);
      if (colon <= 0) {
        throw new YamlError(line.no, `expected "key: value": ${line.content}`);
      }
      const key = unquote(line.content.slice(0, colon).trim(), line.no);
      if (Object.prototype.hasOwnProperty.call(out, key)) {
        throw new YamlError(line.no, `duplicate key ${key}`);
      }
      const valueText = line.content.slice(colon + 1).trim();
      this.index += 1;
      if (valueText !== '') {
        out[key] = scalar(valueText, line.no);
        continue;
      }
      const next = this.peek();
      if (next !== undefined && next.indent > indent) {
        out[key] = this.block(next.indent);
      } else if (
        next?.indent === indent &&
        (next.content === '-' || next.content.startsWith('- '))
      ) {
        out[key] = this.sequence(indent);
      } else {
        out[key] = null;
      }
    }
    return out;
  }
}

/** Parses the YAML subset. */
export function parseYaml(source: string): YamlValue {
  return new Parser(lines(source)).parse();
}
