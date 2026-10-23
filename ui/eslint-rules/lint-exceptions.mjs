// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';

/**
 * @typedef {object} LintException
 * @property {string[]} files  Repository-relative paths under ui/ (exact files, no globs).
 * @property {string[]} rules  ESLint rule ids switched off for those files.
 * @property {string} reason   Why the exception is safe (reviewed in the PR).
 */

/**
 * Validates the lint exception allowlist (SEC-60: exceptions are per file and carry a reason).
 * Inline `eslint-disable` comments are not honoured in src/, so this file is the only way to
 * relax a rule there.
 *
 * @param {unknown} data parsed eslint-allow.json
 * @returns {LintException[]}
 */
export function parseLintExceptions(data) {
  if (
    typeof data !== 'object' ||
    data === null ||
    !Array.isArray(Reflect.get(data, 'exceptions'))
  ) {
    throw new Error('eslint-allow.json: expected an object with an "exceptions" array');
  }
  const entries = /** @type {unknown[]} */ (Reflect.get(data, 'exceptions'));
  return entries.map((entry, index) => {
    const where = `eslint-allow.json: exceptions[${String(index)}]`;
    if (typeof entry !== 'object' || entry === null) {
      throw new Error(`${where} must be an object`);
    }
    const files = Reflect.get(entry, 'files');
    const rules = Reflect.get(entry, 'rules');
    const reason = Reflect.get(entry, 'reason');
    if (!isNonEmptyStringArray(files)) {
      throw new Error(`${where}.files must be a non-empty array of file paths`);
    }
    for (const file of files) {
      if (/[*?[\]{}!]/.test(file) || file.startsWith('/') || file.includes('..')) {
        throw new Error(
          `${where}.files must list exact relative files, got ${JSON.stringify(file)}`,
        );
      }
    }
    if (!isNonEmptyStringArray(rules)) {
      throw new Error(`${where}.rules must be a non-empty array of rule ids`);
    }
    if (typeof reason !== 'string' || reason.trim().length < 10) {
      throw new Error(`${where}.reason must explain the exception (at least 10 characters)`);
    }
    return { files, rules, reason };
  });
}

/**
 * Turns the allowlist into flat-config blocks that switch the listed rules off.
 *
 * @param {LintException[]} exceptions
 * @returns {import('eslint').Linter.Config[]}
 */
export function toConfigBlocks(exceptions) {
  return exceptions.map((exception, index) => ({
    name: `argos/lint-exception-${String(index)}`,
    files: exception.files,
    rules: Object.fromEntries(exception.rules.map((rule) => [rule, 'off'])),
  }));
}

/**
 * @param {URL} fileUrl
 * @returns {import('eslint').Linter.Config[]}
 */
export function loadLintExceptionBlocks(fileUrl) {
  return toConfigBlocks(parseLintExceptions(JSON.parse(readFileSync(fileUrl, 'utf8'))));
}

/**
 * @param {unknown} value
 * @returns {value is string[]}
 */
function isNonEmptyStringArray(value) {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.every((item) => typeof item === 'string' && item.length > 0)
  );
}
