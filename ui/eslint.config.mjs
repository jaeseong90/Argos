// SPDX-License-Identifier: Apache-2.0
//
// Lint policy for the Argos UI (docs/19 §4·§5.4, docs/15 §11·§12·§15, SEC-60·61, 03 §3.3).
// Exceptions are granted per file in eslint-allow.json; inline eslint-disable comments are
// not honoured in src/. Project rules (eslint-rules/, each with RuleTester cases, T-SEC-60):
// argos/spdx-header, argos/untrusted-text (type-aware), argos/no-token-storage, argos/no-raw-href,
// argos/no-object-url.
import { createRequire } from 'node:module';

import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import prettier from 'eslint-config-prettier/flat';
import i18next from 'eslint-plugin-i18next';
import { importX } from 'eslint-plugin-import-x';
import jsxA11y from 'eslint-plugin-jsx-a11y-x';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

import argos from './eslint-rules/index.mjs';
import { loadLintExceptionBlocks } from './eslint-rules/lint-exceptions.mjs';

// eslint-plugin-react's 'detect' calls context.getFilename(), which ESLint 10 removed; read the
// installed React version directly instead.
const REACT_VERSION = /** @type {{ version: string }} */ (
  createRequire(import.meta.url)('react/package.json')
).version;

// Every script extension. src/ may only hold .ts/.tsx (scripts/src-extensions.test.mjs); the src
// rules still cover the other extensions so that a stray .js/.jsx/.mjs/.mts file cannot bypass
// them (defence in depth).
const SCRIPT_EXTENSIONS = '{ts,tsx,mts,cts,js,jsx,mjs,cjs}';
const TS_EXTENSIONS = '{ts,tsx,mts,cts}';
const SRC = `src/**/*.${SCRIPT_EXTENSIONS}`;
const TESTS = ['src/**/*.test.{ts,tsx}', 'test/**/*.{ts,tsx}'];
const NODE_FILES = ['*.{js,mjs,ts}', 'scripts/**/*.mjs', 'eslint-rules/**/*.mjs'];

// DOM sinks that turn strings into markup or code (SEC-60, SEC-61, docs/15 §11).
const XSS_MESSAGE =
  'Strings must reach the DOM as React text nodes only. HTML/code sinks are forbidden (SEC-60).';
const XSS_SYNTAX = [
  { selector: 'MemberExpression[property.name=/^(innerHTML|outerHTML)$/]', message: XSS_MESSAGE },
  {
    selector:
      'MemberExpression[computed=true][property.value=/^(innerHTML|outerHTML|insertAdjacentHTML|srcdoc|dangerouslySetInnerHTML)$/]',
    message: XSS_MESSAGE,
  },
  { selector: "CallExpression[callee.property.name='insertAdjacentHTML']", message: XSS_MESSAGE },
  {
    selector: "CallExpression[callee.property.name='createContextualFragment']",
    message: XSS_MESSAGE,
  },
  {
    selector: 'CallExpression[callee.property.name=/^(setHTML|setHTMLUnsafe|parseHTMLUnsafe)$/]',
    message: XSS_MESSAGE,
  },
  { selector: "NewExpression[callee.name='DOMParser']", message: XSS_MESSAGE },
  {
    selector: 'NewExpression[callee.property.name=/^(DOMParser|Function)$/]',
    message: XSS_MESSAGE,
  },
  { selector: "CallExpression[callee.property.name='Function']", message: XSS_MESSAGE },
  { selector: "CallExpression[callee.property.name='parseFromString']", message: XSS_MESSAGE },
  {
    selector:
      "CallExpression[callee.object.name='document'][callee.property.name=/^(write|writeln)$/]",
    message: XSS_MESSAGE,
  },
  { selector: "CallExpression[callee.name='eval']", message: XSS_MESSAGE },
  { selector: "CallExpression[callee.property.name='eval']", message: XSS_MESSAGE },
  { selector: "NewExpression[callee.name='Function']", message: XSS_MESSAGE },
  { selector: "CallExpression[callee.name='Function']", message: XSS_MESSAGE },
  // iframe srcdoc and inline event handler attributes set from strings.
  { selector: 'JSXAttribute[name.name=/^srcdoc$/i]', message: XSS_MESSAGE },
  { selector: "MemberExpression[property.name='srcdoc']", message: XSS_MESSAGE },
  {
    selector:
      "CallExpression[callee.property.name='setAttribute'][arguments.0.value=/^(on|srcdoc$)/i]",
    message: XSS_MESSAGE,
  },
  {
    selector:
      "CallExpression[callee.property.name='setAttributeNS'][arguments.1.value=/^(on|srcdoc$)/i]",
    message: XSS_MESSAGE,
  },
  // dangerouslySetInnerHTML in any form react/no-danger cannot see: props objects that are spread
  // into JSX or passed to createElement, and member access.
  { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: XSS_MESSAGE },
  { selector: "Property[key.name='dangerouslySetInnerHTML']", message: XSS_MESSAGE },
  { selector: "Property[key.value='dangerouslySetInnerHTML']", message: XSS_MESSAGE },
  { selector: "MemberExpression[property.name='dangerouslySetInnerHTML']", message: XSS_MESSAGE },
];

// Modules that must be reached through a shared wrapper. `allowedIn` lists the only places
// that may import them directly (docs/19 §5.4, docs/15 §2.2, F-U16).
const RESTRICTED_IMPORTS = [
  {
    regex: '^(antd|@ant-design/[^/]+)(/.*)?$',
    message:
      'Use the shared components in src/components instead of importing Ant Design directly (F-U16).',
    allowedIn: ['src/components/**', 'src/app/**'],
  },
  {
    regex: '^uplot(/.*)?$',
    message: 'Use the TimeSeriesChart wrapper in src/charts instead of importing uPlot directly.',
    allowedIn: ['src/charts/**'],
  },
  {
    regex: '(^|/)api/generated(/.*)?$',
    message:
      'Use the API wrapper in src/api (CSRF, 401 refresh, error envelope), not the generated client.',
    allowedIn: ['src/api/**'],
  },
  {
    regex: '^openapi-fetch(/.*)?$',
    message:
      'Use the API wrapper in src/api (CSRF, 401 refresh, error envelope) instead of creating an openapi-fetch client.',
    allowedIn: ['src/api/**'],
  },
  {
    // zod 4 probes `new Function` unless configured jitless first; the server CSP has no
    // 'unsafe-eval', so a direct import would report a violation on every page (SEC-46).
    regex: '^zod(/.*)?$',
    message: "Import z from src/app/zod (jitless, CSP without 'unsafe-eval'), not from 'zod'.",
    allowedIn: ['src/app/**'],
  },
];

// Network and realtime globals that only one module may use (03 §3.3, DSN-15-10).
const FETCH_MESSAGE = 'Call the server through the generated API client in src/api only (03 §3.3).';
const WS_MESSAGE = 'Use the shared WebSocket manager in src/realtime (DSN-15-10).';
const RESTRICTED_GLOBALS = [
  { name: 'fetch', message: FETCH_MESSAGE, allowedIn: ['src/api/**'] },
  { name: 'XMLHttpRequest', message: FETCH_MESSAGE, allowedIn: ['src/api/**'] },
  { name: 'WebSocket', message: WS_MESSAGE, allowedIn: ['src/realtime/**'] },
  // A second network channel next to the generated client; no module may open one (empty list).
  { name: 'EventSource', message: FETCH_MESSAGE, allowedIn: [] },
];
const GLOBAL_OBJECTS = ['window', 'globalThis', 'self'];

// navigator.sendBeacon sends a POST without X-Argos-CSRF, past the one CSRF wrapper (SEC-43):
// forbidden in every scope, src/api included (member access, computed access, destructuring).
const NETWORK_SYNTAX = [
  { selector: "MemberExpression[property.name='sendBeacon']", message: FETCH_MESSAGE },
  {
    selector: "MemberExpression[computed=true][property.value='sendBeacon']",
    message: FETCH_MESSAGE,
  },
  { selector: "ObjectPattern > Property[key.name='sendBeacon']", message: FETCH_MESSAGE },
  { selector: "ObjectPattern > Property[key.value='sendBeacon']", message: FETCH_MESSAGE },
];

// JSX attributes whose values users read; literal strings there must be i18n keys (docs/15 §12).
const USER_VISIBLE_ATTRIBUTES = [
  'title',
  'placeholder',
  'alt',
  'label',
  'aria-label',
  'aria-description',
  'aria-placeholder',
  'aria-roledescription',
  'aria-valuetext',
  'caption',
  'description',
  'message',
  'tooltip',
  'okText',
  'cancelText',
  'emptyText',
];

/**
 * no-restricted-syntax selectors for `import('…')` of a restricted module; no-restricted-imports
 * only sees static imports. esquery regex literals cannot contain '/', so it is written as \x2F.
 *
 * @param {{ regex: string, message: string }[]} imports
 */
function dynamicImportSyntax(imports) {
  return imports.flatMap(({ regex, message }) => {
    const pattern = `/${regex.replaceAll('/', String.raw`\x2F`)}/`;
    return [
      { selector: `ImportExpression[source.value=${pattern}]`, message },
      { selector: `ImportExpression[source.quasis.0.value.cooked=${pattern}]`, message },
    ];
  });
}

/**
 * Builds the restricted import/global/syntax rules for one scope: the XSS sinks and sendBeacon
 * always apply; of the restricted modules and globals every entry applies except those whose
 * `allowedIn` contains the scope (an empty `allowedIn` applies everywhere).
 *
 * @param {string | null} scope
 */
function restrictionRules(scope) {
  const allowed = (/** @type {{ allowedIn: string[] }} */ entry) =>
    scope !== null && entry.allowedIn.includes(scope);
  const imports = RESTRICTED_IMPORTS.filter((entry) => !allowed(entry)).map(
    ({ regex, message }) => ({ regex, message }),
  );
  const globalsList = RESTRICTED_GLOBALS.filter((entry) => !allowed(entry));
  return {
    'no-restricted-syntax': [
      'error',
      ...XSS_SYNTAX,
      ...NETWORK_SYNTAX,
      ...dynamicImportSyntax(imports),
    ],
    'no-restricted-imports': imports.length > 0 ? ['error', { patterns: imports }] : 'off',
    'no-restricted-globals':
      globalsList.length > 0
        ? ['error', ...globalsList.map(({ name, message }) => ({ name, message }))]
        : 'off',
    'no-restricted-properties':
      globalsList.length > 0
        ? [
            'error',
            ...globalsList.flatMap(({ name, message }) =>
              GLOBAL_OBJECTS.map((object) => ({ object, property: name, message })),
            ),
          ]
        : 'off',
  };
}

const RESTRICTION_SCOPES = [
  ...new Set([...RESTRICTED_IMPORTS, ...RESTRICTED_GLOBALS].flatMap((entry) => entry.allowedIn)),
];

export default defineConfig([
  // build/ is the Gradle :ui project's directory; the others are Playwright outputs.
  globalIgnores([
    'dist/',
    'coverage/',
    'node_modules/',
    'build/',
    'test-results/',
    'playwright-report/',
    'blob-report/',
    'perf/results/',
  ]),

  {
    name: 'argos/all-files',
    files: [`**/*.${SCRIPT_EXTENSIONS}`],
    extends: [js.configs.recommended],
    plugins: { argos, 'import-x': importX },
    linterOptions: {
      reportUnusedDisableDirectives: 'error',
      reportUnusedInlineConfigs: 'error',
    },
    rules: {
      'argos/spdx-header': 'error',
      'no-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',
      'import-x/first': 'error',
      'import-x/newline-after-import': 'error',
      'import-x/no-duplicates': 'error',
      'import-x/order': [
        'error',
        {
          groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
          'newlines-between': 'always',
          alphabetize: { order: 'asc', caseInsensitive: true },
        },
      ],
      'import-x/no-extraneous-dependencies': ['error', { devDependencies: true }],
    },
  },

  {
    name: 'argos/typescript',
    files: [`**/*.${TS_EXTENSIONS}`],
    extends: [tseslint.configs.strictTypeChecked],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  },

  {
    name: 'argos/node',
    files: NODE_FILES,
    languageOptions: { globals: { ...globals.node } },
  },

  {
    name: 'argos/src',
    files: [SRC],
    extends: [
      react.configs.flat.recommended,
      react.configs.flat['jsx-runtime'],
      reactHooks.configs.flat['recommended-latest'],
      jsxA11y.configs.strict,
    ],
    plugins: { i18next },
    languageOptions: { globals: { ...globals.browser } },
    linterOptions: { noInlineConfig: true },
    settings: { react: { version: REACT_VERSION } },
    rules: {
      // By default react/no-danger only checks DOM elements; '*' extends it to components.
      'react/no-danger': ['error', { customComponentNames: ['*'] }],
      'react/no-danger-with-children': 'error',
      'react/prop-types': 'off',
      'i18next/no-literal-string': [
        'error',
        {
          mode: 'jsx-only',
          'jsx-attributes': { include: USER_VISIBLE_ATTRIBUTES, exclude: ['.*'] },
          'should-validate-template': true,
        },
      ],
      // Production code may only import packages listed in "dependencies", so the license
      // check of the production dependency closure covers everything that is bundled.
      'import-x/no-extraneous-dependencies': [
        'error',
        { devDependencies: false, optionalDependencies: false, peerDependencies: false },
      ],
      // no-restricted-syntax (XSS sinks, dynamic imports), -imports, -globals, -properties.
      ...restrictionRules(null),
      // Browser storage only for argos.ui.* preference keys, no cookie writes (SEC-40, 15 §11).
      'argos/no-token-storage': 'error',
      // Link targets: literal internal paths or safeHref() (16 §9.2·§9.3).
      'argos/no-raw-href': 'error',
      // No blob: object URLs; downloads only through saveFile() of src/api/exportCsv.ts, the one
      // exception in eslint-allow.json (16 §9.3, DSN-15-06).
      'argos/no-object-url': 'error',
    },
  },

  {
    // Type-aware: collected strings (Untrusted brand, gen:api) only through SafeText, SafeLink
    // and CodeBlock (SEC-60, 15 §6.6). Needs the TypeScript parser, so TypeScript files only;
    // src/ holds no other script files (scripts/src-extensions.test.mjs).
    name: 'argos/src-typed',
    files: [`src/**/*.${TS_EXTENSIONS}`],
    rules: {
      'argos/untrusted-text': 'error',
    },
  },

  ...RESTRICTION_SCOPES.map((scope) => ({
    name: `argos/src-scope:${scope}`,
    files: [`${scope}/*.${SCRIPT_EXTENSIONS}`],
    rules: restrictionRules(scope),
  })),

  {
    name: 'argos/tests',
    files: TESTS,
    rules: {
      // Tests assert on rendered text; they do not render user-facing copy themselves.
      'i18next/no-literal-string': 'off',
      // Tests reset and inspect browser storage (localStorage.clear(), T-SEC-40 checks).
      'argos/no-token-storage': 'off',
      // Tests stub URL.createObjectURL to observe downloads.
      'argos/no-object-url': 'off',
      'import-x/no-extraneous-dependencies': ['error', { devDependencies: true }],
    },
  },

  prettier,

  ...loadLintExceptionBlocks(new URL('./eslint-allow.json', import.meta.url)),
]);
