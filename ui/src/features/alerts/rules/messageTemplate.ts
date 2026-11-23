// SPDX-License-Identifier: Apache-2.0
//
// Message template rules of the rule editor (docs/16 §6.11, SEC-39, 12 §5.5, ADR-0010 C-226 (6)):
// `{{variable}}` placeholders where the variable is one of the allowed names; any other reference
// (an unknown name, a filter, a dotted path, an expression) is forbidden. The same rule as the
// server's TemplateRenderer, so the editor shows the error while typing and the server's 422
// template_variable_forbidden maps to the same text.

/** Allowed variables (server AlertNotification.VARIABLES: docs/16 §6.11 plus started_at). */
export const TEMPLATE_VARIABLES = [
  'app',
  'env',
  'instance',
  'service',
  'host',
  'metric',
  'value',
  'threshold',
  'severity',
  'duration',
  'started_at',
  'link',
  'runbook',
  'rule',
] as const;

export type TemplateVariable = (typeof TEMPLATE_VARIABLES)[number];

/** Longest template (server TemplateRenderer.MAX_TEMPLATE_CHARS, AlertRuleRequest maxLength). */
export const TEMPLATE_MAX_CHARS = 2048;

const REFERENCE = /\{\{([^{}]*)\}\}/g;
const NAME = /^\s*([a-z_]+)\s*$/;

function variableOf(inner: string): string | null {
  const match = NAME.exec(inner);
  return match === null ? null : (match[1] ?? null);
}

function isAllowed(name: string): name is TemplateVariable {
  return (TEMPLATE_VARIABLES as readonly string[]).includes(name);
}

/** The references of a template that are not allowed variables (`{{…}}` text), in order. */
export function forbiddenReferences(template: string): string[] {
  const out: string[] = [];
  for (const match of template.matchAll(REFERENCE)) {
    const name = variableOf(match[1] ?? '');
    if (name === null || !isAllowed(name)) {
      out.push(match[0]);
    }
  }
  return out;
}

/** An open `{{name` right before the caret: the typed part of the variable name, else null. */
export function openReference(
  text: string,
  caret: number,
): { start: number; partial: string } | null {
  const before = text.slice(0, caret);
  const open = before.lastIndexOf('{{');
  if (open < 0 || before.indexOf('}}', open) >= 0) {
    return null;
  }
  const partial = before.slice(open + 2);
  return /^\s*[a-z_]*$/.test(partial) ? { start: open, partial: partial.trim() } : null;
}

/** Allowed variables that start with `partial`. */
export function suggestVariables(partial: string): TemplateVariable[] {
  return TEMPLATE_VARIABLES.filter((name) => name.startsWith(partial));
}

/**
 * Inserts `{{variable}}` at the caret: completes an open `{{name` before the caret, otherwise
 * inserts the whole placeholder. Returns the new text and caret.
 */
export function insertVariable(
  text: string,
  caret: number,
  variable: TemplateVariable,
): { text: string; caret: number } {
  const open = openReference(text, caret);
  const start = open === null ? caret : open.start;
  const placeholder = `{{${variable}}}`;
  const after = text.slice(caret);
  // An already typed closing "}}" right after the caret is reused.
  const rest = open !== null && after.startsWith('}}') ? after.slice(2) : after;
  const next = text.slice(0, start) + placeholder + rest;
  return { text: next, caret: start + placeholder.length };
}
