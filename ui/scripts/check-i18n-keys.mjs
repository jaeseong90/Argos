// SPDX-License-Identifier: Apache-2.0
//
// i18n catalog check (docs/19 §4·§6.2, docs/15 §12, F-U13, T-UI-006).
//
//   errors   - a key used in code (t('…'), i18nKey="…") or nested with $t(…) is missing from the
//              English source catalog ("en missing")
//            - a translation has a key or namespace the English catalog does not have ("orphan")
//            - a catalog is not valid JSON or has a non-string leaf
//   warnings - a key of the English catalog is not translated yet, or an expected language
//              (--languages) has no directory or lacks a namespace file of the English catalog;
//              errors with --release
//
// Keys follow docs/15 §12: `<ns>:<area>.<element>`. A key without a namespace prefix is looked up
// in the default namespace `common` only; keys of any other namespace must carry their prefix
// (`useTranslation(['common', 'xlog'])` + `t('xlog:summary.title')`, not `useTranslation('xlog')`
// + `t('summary.title')`). An unprefixed key that is not in `common` fails with a message that
// says so.
// Plural keys follow i18next (`key_one`, `key_other`, `key_ordinal_two`, …): each language must
// provide the plural categories of that language (Intl.PluralRules), e.g. Korean only `_other`.
//
// Usage: node scripts/check-i18n-keys.mjs [--locales <dir>] [--src <dir>] [--languages en,ko]
//                                         [--release]
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

export const SOURCE_LANGUAGE = 'en';
export const DEFAULT_NAMESPACE = 'common';
/** UI languages; must equal `supportedLanguages` in src/i18n/resources.ts (checked by a test). */
export const DEFAULT_LANGUAGES = Object.freeze(['en', 'ko']);
const KEY_CONVENTION = `keys outside '${DEFAULT_NAMESPACE}' must be written as '<ns>:<key>' (docs/15 §12)`;
// i18next's types accept a `<ns>:` prefix only when useTranslation gets an array of namespaces.
const KEY_CONVENTION_HINT =
  "declare the namespaces as an array, e.g. useTranslation(['common', 'xlog'])";

const PLURAL_SUFFIX = /_(ordinal_)?(zero|one|two|few|many|other)$/;
const SOURCE_FILE = /\.(?:[cm]?[jt]s|[jt]sx)$/;
const TEST_FILE = /\.test\.(?:[cm]?[jt]s|[jt]sx)$/;
const DECLARATION_FILE = /\.d\.[cm]?ts$/;
// The file selects a namespace other than the default one: useTranslation('xlog'),
// useTranslation(['xlog']), withTranslation('xlog'), getFixedT(…), { ns: … }, <Trans ns="…">.
const NAMESPACE_SELECTION =
  /\b(?:useTranslation|withTranslation)\(\s*(?!['"`]common['"`]\s*[),])[^)\s]|\bgetFixedT\(|[{,]\s*ns\s*:|\bns=/;
// t('key'), t("ns:key"), i18n.t(`key`): the first argument when it is a plain string literal.
const T_CALL = /(?<![\w$])t\(\s*(?:(['"])((?:\\.|(?!\1)[^\\\n])*)\1|`([^`$\\]*)`|(\S))/g;
// <Trans i18nKey="key" />, i18nKey={'key'}
const I18N_KEY_PROP = /\bi18nKey=\{?\s*(?:(['"])((?:\\.|(?!\1)[^\\\n])*)\1|`([^`$\\]*)`|(\S))/g;
// $t(key) or $t(ns:key, { … }) inside a message.
const NESTED_T = /\$t\(\s*([^,)\s]+)/g;

/**
 * @typedef {Map<string, string>} Catalog          flattened key -> message
 * @typedef {Map<string, Catalog>} LanguageCatalogs namespace -> catalog
 * @typedef {{ errors: string[], warnings: string[], stats: { languages: string[], namespaces: number, sourceKeys: number, codeReferences: number, dynamicReferences: number } }} CheckResult
 */

/**
 * Flattens nested message objects into dotted keys.
 *
 * @param {unknown} value
 * @param {string} prefix
 * @param {Catalog} into
 * @param {string[]} errors
 * @param {string} where
 */
export function flattenMessages(value, prefix, into, errors, where) {
  if (typeof value === 'string') {
    into.set(prefix, value);
    return;
  }
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    for (const [key, child] of Object.entries(value)) {
      if (key === '' || key.includes(':')) {
        errors.push(`${where}: invalid key segment ${JSON.stringify(key)} under "${prefix}"`);
        continue;
      }
      flattenMessages(child, prefix === '' ? key : `${prefix}.${key}`, into, errors, where);
    }
    return;
  }
  errors.push(`${where}: "${prefix}" must be a string or an object, got ${describe(value)}`);
}

/**
 * Reads `<localesDir>/<language>/<namespace>.json`.
 *
 * @param {string} localesDir
 * @returns {{ languages: Map<string, LanguageCatalogs>, errors: string[] }}
 */
export function readCatalogs(localesDir) {
  /** @type {Map<string, LanguageCatalogs>} */
  const languages = new Map();
  /** @type {string[]} */
  const errors = [];
  for (const language of listDirectory(localesDir).sort()) {
    const languageDir = join(localesDir, language);
    if (!statSync(languageDir).isDirectory()) {
      continue;
    }
    /** @type {LanguageCatalogs} */
    const namespaces = new Map();
    for (const file of listDirectory(languageDir).sort()) {
      if (!file.endsWith('.json')) {
        continue;
      }
      const where = `${language}/${file}`;
      /** @type {Catalog} */
      const catalog = new Map();
      try {
        const parsed = /** @type {unknown} */ (
          JSON.parse(readFileSync(join(languageDir, file), 'utf8'))
        );
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          errors.push(`${where}: the top level must be a JSON object`);
        } else {
          flattenMessages(parsed, '', catalog, errors, where);
        }
      } catch (error) {
        errors.push(
          `${where}: invalid JSON (${error instanceof Error ? error.message : String(error)})`,
        );
      }
      namespaces.set(file.slice(0, -'.json'.length), catalog);
    }
    languages.set(language, namespaces);
  }
  return { languages, errors };
}

/**
 * Finds translation keys referenced by source code.
 *
 * @param {string} text
 * @returns {{ keys: { key: string, line: number }[], dynamic: number }}
 */
export function extractKeyReferences(text) {
  /** @type {{ key: string, line: number }[]} */
  const keys = [];
  let dynamic = 0;
  for (const pattern of [T_CALL, I18N_KEY_PROP]) {
    for (const match of text.matchAll(pattern)) {
      const literal = match[2] ?? match[3];
      if (literal === undefined) {
        // Not a literal (variable, array, template with ${…}): cannot be checked statically.
        if (match[4] !== ')') {
          dynamic += 1;
        }
        continue;
      }
      keys.push({ key: literal, line: lineOf(text, match.index) });
    }
  }
  return { keys, dynamic };
}

/**
 * @param {{ localesDir: string, srcDir: string, release?: boolean, expectedLanguages?: readonly string[] }} options
 *   expectedLanguages: the languages the UI ships (default: DEFAULT_LANGUAGES)
 * @returns {CheckResult}
 */
export function checkI18n({
  localesDir,
  srcDir,
  release = false,
  expectedLanguages = DEFAULT_LANGUAGES,
}) {
  /** @type {string[]} */
  const errors = [];
  /** @type {string[]} */
  const warnings = [];
  // Translation gaps: warnings while a feature is being built, errors for a release.
  const incomplete = (/** @type {string} */ message) => {
    (release ? errors : warnings).push(message);
  };
  const { languages, errors: readErrors } = readCatalogs(localesDir);
  errors.push(...readErrors);

  if (!expectedLanguages.includes(SOURCE_LANGUAGE)) {
    errors.push(
      `the expected languages (${expectedLanguages.join(',')}) must include the source language ${SOURCE_LANGUAGE}`,
    );
  }
  const source = languages.get(SOURCE_LANGUAGE);
  if (source === undefined || source.size === 0) {
    errors.push(`no source catalogs found in ${join(localesDir, SOURCE_LANGUAGE)}`);
    return result(errors, warnings, languages, new Map(), 0, 0);
  }

  for (const language of expectedLanguages) {
    if (!languages.has(language)) {
      incomplete(
        `${language}/: no catalogs for the expected language ${language} (expected: ${expectedLanguages.join(',')})`,
      );
    }
  }

  // Translations: orphan namespaces/keys are errors, untranslated keys are warnings.
  for (const [language, namespaces] of languages) {
    if (language === SOURCE_LANGUAGE) {
      continue;
    }
    for (const [namespace, catalog] of namespaces) {
      const sourceCatalog = source.get(namespace);
      if (sourceCatalog === undefined) {
        errors.push(
          `${language}/${namespace}.json: orphan namespace (no ${SOURCE_LANGUAGE}/${namespace}.json)`,
        );
        continue;
      }
      for (const key of catalog.keys()) {
        if (!hasKey(sourceCatalog, key)) {
          errors.push(
            `${language}/${namespace}.json: orphan key "${key}" (not in ${SOURCE_LANGUAGE}/${namespace}.json)`,
          );
        }
      }
    }
    for (const [namespace, sourceCatalog] of source) {
      const catalog = namespaces.get(namespace);
      if (catalog === undefined) {
        incomplete(
          `${language}/${namespace}.json: missing namespace file (${SOURCE_LANGUAGE}/${namespace}.json has ${String(sourceCatalog.size)} keys)`,
        );
        continue;
      }
      for (const key of requiredKeys(sourceCatalog, language)) {
        if (!catalog.has(key)) {
          incomplete(`${language}/${namespace}.json: missing translation for "${key}"`);
        }
      }
    }
  }

  // Nested $t(…) references inside any message must exist in the source catalog.
  for (const [language, namespaces] of languages) {
    for (const [namespace, catalog] of namespaces) {
      for (const [key, message] of catalog) {
        for (const match of message.matchAll(NESTED_T)) {
          const reference = /** @type {string} */ (match[1]);
          if (!resolves(source, reference, namespace)) {
            errors.push(
              `${language}/${namespace}.json: "${key}" nests $t(${reference}), which is missing in ${SOURCE_LANGUAGE}`,
            );
          }
        }
      }
    }
  }

  // Keys referenced by code must exist in the source catalog.
  let references = 0;
  let dynamic = 0;
  for (const file of listSourceFiles(srcDir)) {
    const text = readFileSync(file, 'utf8');
    const found = extractKeyReferences(text);
    const selectsNamespace = NAMESPACE_SELECTION.test(text);
    dynamic += found.dynamic;
    for (const { key, line } of found.keys) {
      references += 1;
      if (!resolves(source, key, DEFAULT_NAMESPACE)) {
        const where = `${relative(process.cwd(), file).replaceAll('\\', '/')}:${String(line)}`;
        errors.push(`${where}: ${missingKeyMessage(source, key, selectsNamespace)}`);
      }
    }
  }

  return result(errors, warnings, languages, source, references, dynamic);
}

/**
 * Explains why a key used in code does not resolve. An unprefixed key is looked up in `common`
 * only; when it exists in another namespace, or the file selects another namespace, the cause is
 * almost certainly a missing `<ns>:` prefix.
 *
 * @param {LanguageCatalogs} source
 * @param {string} key
 * @param {boolean} selectsNamespace
 */
function missingKeyMessage(source, key, selectsNamespace) {
  if (!key.includes(':')) {
    const owners = [...source]
      .filter(([namespace, catalog]) => namespace !== DEFAULT_NAMESPACE && hasKey(catalog, key))
      .map(([namespace]) => `"${namespace}:${key}"`);
    if (owners.length > 0) {
      return `key "${key}" is not in ${SOURCE_LANGUAGE}/${DEFAULT_NAMESPACE}.json; ${KEY_CONVENTION}: use ${owners.join(' or ')} and ${KEY_CONVENTION_HINT}`;
    }
    if (selectsNamespace) {
      return `key "${key}" is not in ${SOURCE_LANGUAGE}/${DEFAULT_NAMESPACE}.json and no other ${SOURCE_LANGUAGE} namespace has it; ${KEY_CONVENTION}`;
    }
  }
  return `key "${key}" is missing in the ${SOURCE_LANGUAGE} catalog`;
}

/**
 * @param {string[]} errors
 * @param {string[]} warnings
 * @param {Map<string, LanguageCatalogs>} languages
 * @param {LanguageCatalogs} source
 * @param {number} codeReferences
 * @param {number} dynamicReferences
 * @returns {CheckResult}
 */
function result(errors, warnings, languages, source, codeReferences, dynamicReferences) {
  let sourceKeys = 0;
  for (const catalog of source.values()) {
    sourceKeys += catalog.size;
  }
  return {
    errors,
    warnings,
    stats: {
      languages: [...languages.keys()],
      namespaces: source.size,
      sourceKeys,
      codeReferences,
      dynamicReferences,
    },
  };
}

/**
 * True when `key` exists, either literally or as the base of plural forms.
 *
 * @param {Catalog} catalog
 * @param {string} key
 */
function hasKey(catalog, key) {
  if (catalog.has(key)) {
    return true;
  }
  const plural = PLURAL_SUFFIX.exec(key);
  const base = plural === null ? key : key.slice(0, plural.index);
  for (const candidate of catalog.keys()) {
    const match = PLURAL_SUFFIX.exec(candidate);
    if (match === null || candidate.slice(0, match.index) !== base) {
      continue;
    }
    // A plural form is fine as long as the source has the same plural kind (cardinal/ordinal).
    if (plural === null || Boolean(match[1]) === Boolean(plural[1])) {
      return true;
    }
  }
  return false;
}

/**
 * @param {LanguageCatalogs} source
 * @param {string} reference `ns:key` or `key`
 * @param {string} defaultNamespace
 */
function resolves(source, reference, defaultNamespace) {
  const separator = reference.indexOf(':');
  const namespace = separator === -1 ? defaultNamespace : reference.slice(0, separator);
  const key = separator === -1 ? reference : reference.slice(separator + 1);
  const catalog = source.get(namespace);
  return catalog !== undefined && key !== '' && hasKey(catalog, key);
}

/**
 * The keys a translation into `language` must contain for the given source catalog.
 *
 * @param {Catalog} sourceCatalog
 * @param {string} language
 * @returns {Set<string>}
 */
function requiredKeys(sourceCatalog, language) {
  /** @type {Set<string>} */
  const keys = new Set();
  for (const key of sourceCatalog.keys()) {
    const plural = PLURAL_SUFFIX.exec(key);
    if (plural === null) {
      keys.add(key);
      continue;
    }
    const base = key.slice(0, plural.index);
    const ordinal = Boolean(plural[1]);
    const categories = new Intl.PluralRules(language, {
      type: ordinal ? 'ordinal' : 'cardinal',
    }).resolvedOptions().pluralCategories;
    for (const category of categories) {
      keys.add(`${base}_${ordinal ? 'ordinal_' : ''}${category}`);
    }
  }
  return keys;
}

/**
 * @param {string} dir
 * @returns {string[]}
 */
function listSourceFiles(dir) {
  /** @type {string[]} */
  const files = [];
  for (const entry of listDirectory(dir).sort()) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...listSourceFiles(path));
    } else if (SOURCE_FILE.test(entry) && !TEST_FILE.test(entry) && !DECLARATION_FILE.test(entry)) {
      files.push(path);
    }
  }
  return files;
}

/**
 * @param {string} dir
 * @returns {string[]}
 */
function listDirectory(dir) {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

/**
 * @param {string} text
 * @param {number} index
 */
function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i += 1) {
    if (text.charCodeAt(i) === 10) {
      line += 1;
    }
  }
  return line;
}

/** @param {unknown} value */
function describe(value) {
  if (value === null) {
    return 'null';
  }
  return Array.isArray(value) ? 'array' : typeof value;
}

/**
 * @param {string[]} argv
 * @returns {number} exit code
 */
export function main(argv) {
  const uiRoot = fileURLToPath(new URL('..', import.meta.url));
  const { values } = parseArgs({
    args: argv,
    options: {
      locales: { type: 'string', default: join(uiRoot, 'src', 'locales') },
      src: { type: 'string', default: join(uiRoot, 'src') },
      languages: { type: 'string', default: DEFAULT_LANGUAGES.join(',') },
      release: { type: 'boolean', default: false },
    },
    strict: true,
  });
  const expectedLanguages = values.languages
    .split(',')
    .map((language) => language.trim())
    .filter((language) => language !== '');
  const outcome = checkI18n({
    localesDir: resolve(values.locales),
    srcDir: resolve(values.src),
    release: values.release,
    expectedLanguages,
  });
  for (const warning of outcome.warnings) {
    console.warn(`WARN  ${warning}`);
  }
  for (const error of outcome.errors) {
    console.error(`ERROR ${error}`);
  }
  const { stats } = outcome;
  console.log(
    `check-i18n-keys: languages=${stats.languages.join(',')} expected=${expectedLanguages.join(',')} ` +
      `mode=${values.release ? 'release' : 'pr'} namespaces=${String(stats.namespaces)} ` +
      `sourceKeys=${String(stats.sourceKeys)} codeReferences=${String(stats.codeReferences)} ` +
      `dynamicReferences(unchecked)=${String(stats.dynamicReferences)} ` +
      `warnings=${String(outcome.warnings.length)} errors=${String(outcome.errors.length)}`,
  );
  return outcome.errors.length === 0 ? 0 : 1;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
