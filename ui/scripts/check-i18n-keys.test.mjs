// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { checkI18n, DEFAULT_LANGUAGES, extractKeyReferences } from './check-i18n-keys.mjs';
import { createFixture } from './test-fixtures.mjs';

const SCRIPT = fileURLToPath(new URL('./check-i18n-keys.mjs', import.meta.url));
const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

/**
 * @param {Record<string, string | object>} files
 * @param {{ release?: boolean, expectedLanguages?: string[] }} [options]
 */
function check(files, options = {}) {
  const fixture = createFixture(files);
  cleanups.push(fixture.cleanup);
  return checkI18n({
    localesDir: join(fixture.root, 'src', 'locales'),
    srcDir: join(fixture.root, 'src'),
    release: options.release ?? false,
    ...(options.expectedLanguages === undefined
      ? {}
      : { expectedLanguages: options.expectedLanguages }),
  });
}

const EN_COMMON = {
  app: { name: 'Argos APM', title: 'Overview' },
  item_one: '{{count}} item',
  item_other: '{{count}} items',
  term: { xlog: 'XLog' },
  help: 'Open $t(term.xlog) and $t(common:app.title, { "x": 1 })',
};
const KO_COMMON = {
  app: { name: 'Argos APM', title: '개요' },
  item_other: '{{count}}개',
  term: { xlog: 'XLog' },
  help: '$t(term.xlog)을 여세요',
};
const APP_SOURCE = [
  '// SPDX-License-Identifier: Apache-2.0',
  'export const a = (t: (k: string, o?: object) => string) => [t(\'app.name\'), t("common:app.title"),',
  "  t('item', { count: 2 }), t(`term.xlog`), t(dynamicKey), t(`app.${'x'}`)];",
  'export const b = <Trans i18nKey="help" />;',
].join('\n');

describe('extractKeyReferences', () => {
  it('finds literal keys with their line numbers and counts dynamic keys', () => {
    const { keys, dynamic } = extractKeyReferences(APP_SOURCE);

    expect(keys).toEqual([
      { key: 'app.name', line: 2 },
      { key: 'common:app.title', line: 2 },
      { key: 'item', line: 3 },
      { key: 'term.xlog', line: 3 },
      { key: 'help', line: 4 },
    ]);
    expect(dynamic).toBe(2);
  });

  it('ignores identifiers that merely end in t(', () => {
    expect(extractKeyReferences("split('x'); set('y'); import('z'); format('w')").keys).toEqual([]);
  });
});

describe('checkI18n (T-UI-006)', () => {
  it('passes for consistent catalogs, plural forms, nested $t and code keys', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': KO_COMMON,
      'src/app/App.tsx': APP_SOURCE,
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.warnings).toEqual([]);
    expect(outcome.stats).toMatchObject({ languages: ['en', 'ko'], codeReferences: 5 });
  });

  it('fails when code uses a key that is missing in en', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': KO_COMMON,
      'src/app/App.tsx': "const x = t('app.missing');\nconst y = t('other:app.name');",
    });

    expect(outcome.errors).toHaveLength(2);
    expect(outcome.errors[0]).toMatch(
      /App\.tsx:1: key "app\.missing" is missing in the en catalog/,
    );
    expect(outcome.errors[1]).toMatch(/App\.tsx:2: key "other:app\.name" is missing/);
  });

  it('does not scan test files for key references', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/app/App.test.tsx': "t('not.a.real.key')",
    });

    expect(outcome.errors).toEqual([]);
  });

  it('fails on a ko key that en does not have (orphan)', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': { ...KO_COMMON, app: { ...KO_COMMON.app, stale: '예전 문구' } },
    });

    expect(outcome.errors).toEqual([
      'ko/common.json: orphan key "app.stale" (not in en/common.json)',
    ]);
  });

  it('fails on a ko namespace that en does not have', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': KO_COMMON,
      'src/locales/ko/xlog.json': { title: 'XLog' },
    });

    expect(outcome.errors).toEqual(['ko/xlog.json: orphan namespace (no en/xlog.json)']);
  });

  it('fails on ordinal/cardinal plural mismatch and on plural forms of a non-plural key', () => {
    const outcome = check({
      'src/locales/en/common.json': { place_ordinal_one: '{{count}}st', label: 'Label' },
      'src/locales/ko/common.json': {
        place_ordinal_other: '{{count}}번째',
        place_other: 'x',
        label_other: 'y',
      },
    });

    expect(outcome.errors).toEqual([
      'ko/common.json: orphan key "place_other" (not in en/common.json)',
      'ko/common.json: orphan key "label_other" (not in en/common.json)',
    ]);
  });

  it('warns about an untranslated key, and fails with --release', () => {
    const files = {
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': { ...KO_COMMON, item_other: undefined },
    };

    const pr = check(files);
    expect(pr.errors).toEqual([]);
    expect(pr.warnings).toEqual(['ko/common.json: missing translation for "item_other"']);

    const release = check(files, { release: true });
    expect(release.errors).toEqual(['ko/common.json: missing translation for "item_other"']);
  });

  it('requires the plural categories of each language', () => {
    const outcome = check({
      'src/locales/en/common.json': { item_one: 'one', item_other: 'other' },
      'src/locales/ko/common.json': { item_one: 'one' },
    });

    expect(outcome.warnings).toEqual(['ko/common.json: missing translation for "item_other"']);
  });

  it('fails when a message nests a key that is missing in en', () => {
    const outcome = check({
      'src/locales/en/common.json': { a: 'see $t(b)' },
      'src/locales/ko/common.json': { a: '$t(common:c) 참조' },
    });

    expect(outcome.errors).toEqual([
      'en/common.json: "a" nests $t(b), which is missing in en',
      'ko/common.json: "a" nests $t(common:c), which is missing in en',
    ]);
  });

  it('fails on invalid JSON, non-string leaves and missing source catalogs', () => {
    const invalid = check({
      'src/locales/en/common.json': '{ "a": ',
      'src/locales/en/other.json': { list: ['x'], n: 1, nil: null },
    });
    expect(invalid.errors).toHaveLength(4);
    expect(invalid.errors[0]).toMatch(/^en\/common\.json: invalid JSON/);
    expect(invalid.errors.slice(1)).toEqual([
      'en/other.json: "list" must be a string or an object, got array',
      'en/other.json: "n" must be a string or an object, got number',
      'en/other.json: "nil" must be a string or an object, got null',
    ]);

    const empty = check({ 'src/locales/ko/common.json': {} });
    expect(empty.errors[0]).toMatch(/^no source catalogs found/);
  });

  it('warns when an expected language has no directory, and fails with --release', () => {
    const files = {
      'src/locales/en/common.json': EN_COMMON,
      'src/app/App.tsx': APP_SOURCE,
    };
    const expected = 'ko/: no catalogs for the expected language ko (expected: en,ko)';

    const pr = check(files);
    expect(pr.errors).toEqual([]);
    expect(pr.warnings).toEqual([expected]);

    const release = check(files, { release: true });
    expect(release.errors).toEqual([expected]);
  });

  it('warns when a translation lacks a namespace file of en, and fails with --release', () => {
    const files = {
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/en/xlog.json': { summary: { title: 'Summary' } },
      'src/locales/ko/common.json': KO_COMMON,
    };
    const expected = 'ko/xlog.json: missing namespace file (en/xlog.json has 1 keys)';

    expect(check(files).warnings).toEqual([expected]);
    expect(check(files, { release: true }).errors).toEqual([expected]);
  });

  it('checks the languages given with expectedLanguages', () => {
    const files = {
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': KO_COMMON,
    };

    expect(check(files, { expectedLanguages: ['en', 'ko', 'ja'], release: true }).errors).toEqual([
      'ja/: no catalogs for the expected language ja (expected: en,ko,ja)',
    ]);
    expect(check(files, { expectedLanguages: ['ko'] }).errors).toEqual([
      'the expected languages (ko) must include the source language en',
    ]);
  });

  it('defaults to the languages of src/i18n/resources.ts', async () => {
    // A non-literal specifier: Vitest transforms the TypeScript module at run time, while
    // `tsc` (tooling project) does not try to resolve a .ts import from a .mjs file.
    const specifier = '../src/i18n/resources.ts';
    const resources = /** @type {{ supportedLanguages: readonly string[] }} */ (
      await import(specifier)
    );

    expect([...DEFAULT_LANGUAGES]).toEqual([...resources.supportedLanguages]);
  });

  it('explains that keys outside common need their namespace prefix (docs/15 §12)', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/en/xlog.json': { summary: { title: 'Summary' } },
      'src/locales/ko/common.json': KO_COMMON,
      'src/locales/ko/xlog.json': { summary: { title: '요약' } },
      'src/features/xlog/Summary.tsx': [
        "const { t } = useTranslation('xlog');",
        "t('summary.title');",
        "t('summary.missing');",
      ].join('\n'),
      'src/features/xlog/Other.tsx': "t('summary.title');",
    });

    const hint = String.raw` and declare the namespaces as an array, e\.g\. useTranslation\(\['common', 'xlog'\]\)$`;
    expect(outcome.errors).toEqual([
      expect.stringMatching(
        new RegExp(
          String.raw`Other\.tsx:1: key "summary\.title" is not in en/common\.json; keys outside 'common' must be written as '<ns>:<key>' \(docs/15 §12\): use "xlog:summary\.title"` +
            hint,
        ),
      ),
      expect.stringMatching(
        new RegExp(
          String.raw`Summary\.tsx:2: key "summary\.title" is not in en/common\.json; keys outside 'common' must be written as '<ns>:<key>' \(docs/15 §12\): use "xlog:summary\.title"` +
            hint,
        ),
      ),
      expect.stringMatching(
        /Summary\.tsx:3: key "summary\.missing" is not in en\/common\.json and no other en namespace has it; keys outside 'common' must be written as '<ns>:<key>' \(docs\/15 §12\)$/,
      ),
    ]);
  });

  it('accepts prefixed keys of other namespaces', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/en/xlog.json': { summary: { title: 'Summary' } },
      'src/locales/ko/common.json': KO_COMMON,
      'src/locales/ko/xlog.json': { summary: { title: '요약' } },
      'src/features/xlog/Summary.tsx': [
        "const { t } = useTranslation(['common', 'xlog']);",
        "t('xlog:summary.title');",
        "t('app.name');",
        "t('common:app.title');",
      ].join('\n'),
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.stats.codeReferences).toBe(3);
  });

  it('keeps the plain message for keys that are missing everywhere in files using common only', () => {
    const outcome = check({
      'src/locales/en/common.json': EN_COMMON,
      'src/locales/ko/common.json': KO_COMMON,
      'src/app/A.tsx': "const { t } = useTranslation('common');\nt('app.nope');",
    });

    expect(outcome.errors).toEqual([
      expect.stringMatching(/A\.tsx:2: key "app\.nope" is missing in the en catalog$/),
    ]);
  });

  it('passes for the real ui catalogs and sources', () => {
    const outcome = checkI18n({
      localesDir: join(UI_ROOT, 'src', 'locales'),
      srcDir: join(UI_ROOT, 'src'),
      release: true,
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.stats.codeReferences).toBeGreaterThan(0);
  });
});

describe('check-i18n-keys CLI', () => {
  // Spawns node processes, which can exceed Vitest's 5 s default on a loaded machine.
  it('exits 1 on errors and 0 when clean', () => {
    const bad = createFixture({
      'locales/en/common.json': { a: 'A' },
      'locales/ko/common.json': { a: 'A', b: 'B' },
      'src/A.tsx': "t('a')",
    });
    cleanups.push(bad.cleanup);
    const args = ['--locales', join(bad.root, 'locales'), '--src', join(bad.root, 'src')];

    const failed = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
    expect(failed.status).toBe(1);
    expect(failed.stderr).toContain('ERROR ko/common.json: orphan key "b"');

    const passed = spawnSync(process.execPath, [SCRIPT], { encoding: 'utf8', cwd: UI_ROOT });
    expect(passed.status).toBe(0);
    expect(passed.stdout).toContain('expected=en,ko');
    expect(passed.stdout).toContain('errors=0');
  }, 30_000);

  // Spawns node processes, which can exceed Vitest's 5 s default on a loaded machine.
  it('without a ko directory: warns in PR mode (exit 0), fails with --release (exit 1)', () => {
    const fixture = createFixture({
      'locales/en/common.json': { a: 'A' },
      'src/A.tsx': "t('a')",
    });
    cleanups.push(fixture.cleanup);
    const args = ['--locales', join(fixture.root, 'locales'), '--src', join(fixture.root, 'src')];

    const pr = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
    expect(pr.status).toBe(0);
    expect(pr.stderr).toContain('WARN  ko/: no catalogs for the expected language ko');

    const release = spawnSync(process.execPath, [SCRIPT, ...args, '--release'], {
      encoding: 'utf8',
    });
    expect(release.status).toBe(1);
    expect(release.stderr).toContain('ERROR ko/: no catalogs for the expected language ko');

    const enOnly = spawnSync(
      process.execPath,
      [SCRIPT, ...args, '--release', '--languages', 'en'],
      { encoding: 'utf8' },
    );
    expect(enOnly.status).toBe(0);
  }, 30_000);
});
