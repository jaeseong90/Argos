// SPDX-License-Identifier: Apache-2.0
//
// "No external resources" check for the UI bundle (O-P03, docs/19 §4·§6.2, docs/15 §11).
// The UI must work in a closed network: fonts, scripts, styles and images all come from the
// server's own origin. This script fails when
//
//   - an HTML file (dist/**/*.html and the source index.html) points a URL attribute
//     (src, href, srcset, action, poster, …) at another origin: any scheme other than `data:`,
//     or a protocol-relative `//host` URL;
//   - a stylesheet (CSS file, <style>, style="") uses url(…) or @import with such a URL;
//   - any file the browser parses as code or markup (html, js, css, svg, map, …) contains an
//     absolute http(s)/ws(s)/ftp URL, also in its JSON/JS-escaped form (`https:\/\/host\/x`),
//     or a string literal that starts with a protocol-relative URL (`"//cdn.example.com/x.js"`),
//     that is not listed in scripts/external-allowlist.json.
//
// The allowlist holds exact URL strings that are never fetched (XML namespace identifiers,
// links printed in library error messages), each with a reason; escaped URLs are compared in
// their unescaped form. Other files in dist (license
// notices, build metadata JSON, fonts, raster images) are not parsed by the browser as
// resources and are listed as skipped.
//
// Usage: node scripts/check-no-external.mjs [--dist <dir>] [--index <file>] [--allowlist <file>]
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const SCANNED_EXTENSIONS = new Set([
  '.html',
  '.htm',
  '.js',
  '.mjs',
  '.cjs',
  '.css',
  '.svg',
  '.map',
  '.webmanifest',
  '.xml',
]);
const HTML_EXTENSIONS = new Set(['.html', '.htm']);
const URL_ATTRIBUTES = new Set([
  'src',
  'href',
  'srcset',
  'imagesrcset',
  'action',
  'formaction',
  'poster',
  'data',
  'xlink:href',
  'background',
  'cite',
  'manifest',
  'ping',
  'longdesc',
  'icon',
  'codebase',
  'archive',
  'lowsrc',
]);
const ALLOWED_SCHEMES = new Set(['data']);
const URL_TOKEN = /\b(?:https?|wss?|ftp):\/\/[^\s"'`<>()\\{}|^,;]*/gi;
// The same with every '/' escaped as '\/' (JSON.stringify output, minified string literals).
const ESCAPED_URL_TOKEN = /\b(?:https?|wss?|ftp):\\\/\\\/(?:\\\/|[^\s"'`<>()\\{}|^,;])*/gi;
// A quote or backtick directly followed by `//host` (optionally escaped as `\/\/host`): a string
// that starts with a protocol-relative URL, which the browser resolves to another origin.
const PROTOCOL_RELATIVE_LITERAL =
  /(?<=["'`])(?:\/\/|\\\/\\\/)(?:localhost|(?:[a-z0-9-]+\.)+[a-z0-9-]+)(?::\d+)?(?:(?:\\\/|[/?#])(?:\\\/|[^\s"'`<>()\\{}|^,;])*)?(?![\w.-])/gi;
const TAG = /<([a-zA-Z][\w:-]*)\b((?:"[^"]*"|'[^']*'|[^'">])*)>/g;
const ATTRIBUTE = /([^\s"'=<>/]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))/g;
const STYLE_BLOCK = /<style\b[^>]*>([\s\S]*?)<\/style>/gi;
const CSS_URL = /url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s]*))\s*\)/gi;
const CSS_IMPORT = /@import\s+(?:"([^"]*)"|'([^']*)')/gi;
/** @type {[RegExp, string][]} patterns applied to the text of every scanned file */
const URL_PATTERNS = [
  [URL_TOKEN, 'absolute URL'],
  [ESCAPED_URL_TOKEN, 'absolute URL (escaped)'],
  [PROTOCOL_RELATIVE_LITERAL, 'protocol-relative URL'],
];

/**
 * @typedef {{ url: string, reason: string }} AllowlistEntry
 * @typedef {{ errors: string[], warnings: string[], scanned: string[], skipped: string[], allowlisted: number }} CheckResult
 */

/**
 * @param {unknown} data parsed external-allowlist.json
 * @returns {AllowlistEntry[]}
 */
export function parseAllowlist(data) {
  const allowed =
    typeof data === 'object' && data !== null ? Reflect.get(data, 'allowed') : undefined;
  if (!Array.isArray(allowed)) {
    throw new Error('external allowlist: expected an object with an "allowed" array');
  }
  return allowed.map((entry, index) => {
    const url = typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'url') : undefined;
    const reason =
      typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'reason') : undefined;
    if (typeof url !== 'string' || !/^(?:(?:https?|wss?|ftp):)?\/\/[^/\\]/i.test(url)) {
      throw new Error(
        `external allowlist: allowed[${String(index)}].url must be an absolute or protocol-relative URL (unescaped)`,
      );
    }
    if (typeof reason !== 'string' || reason.trim().length < 10) {
      throw new Error(
        `external allowlist: allowed[${String(index)}].reason must explain why ${url} is never fetched`,
      );
    }
    return { url, reason };
  });
}

/**
 * Returns why `value` (a URL attribute or CSS url) points at another origin, or null.
 *
 * @param {string} value
 * @returns {string | null}
 */
export function externalReason(value) {
  const trimmed = value.trim();
  if (trimmed.startsWith('//') || trimmed.startsWith('\\\\')) {
    return 'protocol-relative URL';
  }
  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(trimmed);
  if (scheme !== null && !ALLOWED_SCHEMES.has(/** @type {string} */ (scheme[1]).toLowerCase())) {
    return `"${/** @type {string} */ (scheme[1]).toLowerCase()}:" URL`;
  }
  return null;
}

/**
 * @param {string} css
 * @returns {string[]} URLs referenced by url(…) and CSS import rules
 */
function cssReferences(css) {
  /** @type {string[]} */
  const urls = [];
  for (const match of css.matchAll(CSS_URL)) {
    urls.push(match[1] ?? match[2] ?? match[3] ?? '');
  }
  for (const match of css.matchAll(CSS_IMPORT)) {
    urls.push(match[1] ?? match[2] ?? '');
  }
  return urls;
}

/**
 * @param {string} html
 * @returns {{ tag: string, attribute: string, url: string }[]} URL-valued attributes and inline CSS urls
 */
function htmlReferences(html) {
  /** @type {{ tag: string, attribute: string, url: string }[]} */
  const references = [];
  for (const tagMatch of html.matchAll(TAG)) {
    const tag = /** @type {string} */ (tagMatch[1]).toLowerCase();
    for (const attributeMatch of (tagMatch[2] ?? '').matchAll(ATTRIBUTE)) {
      const attribute = /** @type {string} */ (attributeMatch[1]).toLowerCase();
      const value = attributeMatch[2] ?? attributeMatch[3] ?? attributeMatch[4] ?? '';
      if (attribute === 'srcset' || attribute === 'imagesrcset') {
        for (const candidate of value.split(',')) {
          references.push({ tag, attribute, url: candidate.trim().split(/\s+/)[0] ?? '' });
        }
      } else if (attribute === 'style') {
        for (const url of cssReferences(value)) {
          references.push({ tag, attribute, url });
        }
      } else if (URL_ATTRIBUTES.has(attribute)) {
        references.push({ tag, attribute, url: value });
      }
    }
  }
  for (const block of html.matchAll(STYLE_BLOCK)) {
    for (const url of cssReferences(block[1] ?? '')) {
      references.push({ tag: 'style', attribute: 'css', url });
    }
  }
  return references;
}

/**
 * @param {{ distDir: string, indexHtml?: string | null, allowlist: AllowlistEntry[] }} options
 * @returns {CheckResult}
 */
export function checkNoExternal({ distDir, indexHtml = null, allowlist }) {
  /** @type {Map<string, string>} */
  const findings = new Map();
  /** @type {string[]} */
  const scanned = [];
  /** @type {string[]} */
  const skipped = [];
  const allowed = new Map(allowlist.map((entry) => [entry.url, entry]));
  /** @type {Set<string>} */
  const usedAllowlist = new Set();
  let allowlisted = 0;

  /**
   * @param {string} file
   * @param {string} url
   * @param {string} message
   */
  const report = (file, url, message) => {
    const key = `${file}\u0000${url}`;
    if (!findings.has(key)) {
      findings.set(key, `${file}: ${message}`);
    }
  };

  /**
   * @param {string} path
   * @param {string} label
   */
  const scanFile = (path, label) => {
    const extension = extname(path).toLowerCase();
    if (!SCANNED_EXTENSIONS.has(extension)) {
      skipped.push(label);
      return;
    }
    scanned.push(label);
    const text = readFileSync(path, 'utf8');
    if (HTML_EXTENSIONS.has(extension) || extension === '.svg') {
      for (const { tag, attribute, url } of htmlReferences(text)) {
        const reason = externalReason(url);
        if (reason !== null) {
          report(label, url, `<${tag} ${attribute}> points to another origin (${reason}): ${url}`);
        }
      }
    }
    if (extension === '.css') {
      for (const url of cssReferences(text)) {
        const reason = externalReason(url);
        if (reason !== null) {
          report(label, url, `stylesheet loads from another origin (${reason}): ${url}`);
        }
      }
    }
    for (const [pattern, kind] of URL_PATTERNS) {
      for (const match of text.matchAll(pattern)) {
        const url = match[0].replaceAll('\\/', '/').replace(/[.!?]+$/, '');
        if (allowed.has(url)) {
          usedAllowlist.add(url);
          allowlisted += 1;
          continue;
        }
        report(label, url, `${kind} is not allowed in the bundle: ${url}`);
      }
    }
  };

  /** @type {string[]} */
  const errors = [];
  if (!existsSync(distDir) || !statSync(distDir).isDirectory()) {
    errors.push(`${distDir}: build output not found (run the build first)`);
  } else if (!existsSync(join(distDir, 'index.html'))) {
    errors.push(`${distDir}: index.html not found (run the build first)`);
  } else {
    for (const file of listFiles(distDir)) {
      scanFile(file, `dist/${relative(distDir, file).replaceAll('\\', '/')}`);
    }
  }
  if (indexHtml !== null) {
    if (existsSync(indexHtml)) {
      scanFile(indexHtml, 'index.html');
    } else {
      errors.push(`${indexHtml}: source index.html not found`);
    }
  }
  errors.push(...findings.values());

  const warnings = allowlist
    .filter((entry) => !usedAllowlist.has(entry.url))
    .map((entry) => `allowlist entry is not used by the bundle (remove it): ${entry.url}`);
  return { errors, warnings, scanned, skipped, allowlisted };
}

/**
 * @param {string} dir
 * @returns {string[]}
 */
function listFiles(dir) {
  /** @type {string[]} */
  const files = [];
  for (const entry of readdirSync(dir).sort()) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...listFiles(path));
    } else {
      files.push(path);
    }
  }
  return files;
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
      dist: { type: 'string', default: join(uiRoot, 'dist') },
      index: { type: 'string', default: join(uiRoot, 'index.html') },
      allowlist: { type: 'string', default: join(uiRoot, 'scripts', 'external-allowlist.json') },
    },
    strict: true,
  });
  const allowlist = parseAllowlist(JSON.parse(readFileSync(resolve(values.allowlist), 'utf8')));
  const outcome = checkNoExternal({
    distDir: resolve(values.dist),
    indexHtml: resolve(values.index),
    allowlist,
  });
  for (const warning of outcome.warnings) {
    console.warn(`WARN  ${warning}`);
  }
  for (const error of outcome.errors) {
    console.error(`ERROR ${error}`);
  }
  console.log(
    `check-no-external: scanned=${String(outcome.scanned.length)} ` +
      `skipped=${String(outcome.skipped.length)}${outcome.skipped.length > 0 ? ` (${outcome.skipped.join(', ')})` : ''} ` +
      `allowlisted=${String(outcome.allowlisted)} errors=${String(outcome.errors.length)}`,
  );
  return outcome.errors.length === 0 ? 0 : 1;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
