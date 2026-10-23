// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { checkNoExternal, externalReason, parseAllowlist } from './check-no-external.mjs';
import { createFixture } from './test-fixtures.mjs';

const SCRIPT = fileURLToPath(new URL('./check-no-external.mjs', import.meta.url));
const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));
const ALLOWLIST = [
  { url: 'http://www.w3.org/2000/svg', reason: 'XML namespace identifier, never fetched.' },
];

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

const GOOD_INDEX = [
  '<!doctype html>',
  '<html lang="en"><head>',
  '<link rel="icon" href="data:," />',
  '<script type="module" crossorigin src="/assets/index-abc.js"></script>',
  '<link rel="stylesheet" href="/assets/index-abc.css">',
  '</head><body><div id="root"></div></body></html>',
].join('\n');

/**
 * @param {Record<string, string>} distFiles files under dist/
 */
function check(distFiles) {
  const fixture = createFixture(
    Object.fromEntries(
      Object.entries(distFiles).map(([path, content]) => [`dist/${path}`, content]),
    ),
  );
  cleanups.push(fixture.cleanup);
  return checkNoExternal({ distDir: join(fixture.root, 'dist'), allowlist: ALLOWLIST });
}

describe('externalReason', () => {
  it.each([
    ['/assets/a.js', null],
    ['assets/a.js', null],
    ['./a.css', null],
    ['data:,', null],
    ['data:image/svg+xml;base64,AAAA', null],
    ['#fragment', null],
    ['https://cdn.example.com/a.js', '"https:" URL'],
    ['HTTP://cdn.example.com/a.js', '"http:" URL'],
    ['//cdn.example.com/a.js', 'protocol-relative URL'],
    ['  //cdn.example.com/a.js', 'protocol-relative URL'],
    // Concatenated so the fixture itself does not trip no-script-url.
    ['javascript' + ':alert(1)', '"javascript:" URL'],
    ['wss://example.com/socket', '"wss:" URL'],
  ])('%s -> %s', (value, expected) => {
    expect(externalReason(value)).toBe(expected);
  });
});

describe('checkNoExternal (O-P03)', () => {
  it('passes for same-origin assets, data: URIs, allowlisted identifiers and skipped notices', () => {
    const outcome = check({
      'index.html': GOOD_INDEX,
      'assets/index-abc.js': 'document.createElementNS("http://www.w3.org/2000/svg","svg");',
      'assets/index-abc.css':
        'body{background:url(/assets/bg.png)} i{background:url("data:image/png;base64,AA==")}',
      'licenses.txt': 'Licensed under http://www.apache.org/licenses/LICENSE-2.0',
      '.vite/license.json': '[{"text":"see https://opensource.org/license/mit"}]',
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.warnings).toEqual([]);
    expect(outcome.scanned).toEqual([
      'dist/assets/index-abc.css',
      'dist/assets/index-abc.js',
      'dist/index.html',
    ]);
    expect(outcome.skipped).toEqual(['dist/.vite/license.json', 'dist/licenses.txt']);
    expect(outcome.allowlisted).toBe(1);
  });

  it('fails on external <script> and <link> in index.html', () => {
    const outcome = check({
      'index.html': [
        '<!doctype html><html><head>',
        '<script src="https://cdn.example.com/react.js"></script>',
        "<link rel='stylesheet' href='//fonts.googleapis.com/css?family=Inter'>",
        '<link rel="preconnect" href=https://fonts.gstatic.com>',
        '</head><body><img srcset="a.png 1x, https://img.example.com/b.png 2x"></body></html>',
      ].join('\n'),
    });

    expect(outcome.errors).toEqual([
      'dist/index.html: <script src> points to another origin ("https:" URL): https://cdn.example.com/react.js',
      'dist/index.html: <link href> points to another origin (protocol-relative URL): //fonts.googleapis.com/css?family=Inter',
      'dist/index.html: <link href> points to another origin ("https:" URL): https://fonts.gstatic.com',
      'dist/index.html: <img srcset> points to another origin ("https:" URL): https://img.example.com/b.png',
    ]);
  });

  it('fails on external url() and @import in stylesheets, <style> blocks and style attributes', () => {
    const outcome = check({
      'index.html': `${GOOD_INDEX}<style>@import '//fonts.example.com/a.css';</style><div style="background:url(//x.example.com/i.png)"></div>`,
      'assets/index-abc.css': '@font-face{src:url(https://fonts.gstatic.com/s/inter.woff2)}',
    });

    expect(outcome.errors).toEqual([
      'dist/assets/index-abc.css: stylesheet loads from another origin ("https:" URL): https://fonts.gstatic.com/s/inter.woff2',
      'dist/index.html: <div style> points to another origin (protocol-relative URL): //x.example.com/i.png',
      'dist/index.html: <style css> points to another origin (protocol-relative URL): //fonts.example.com/a.css',
    ]);
  });

  it('fails on absolute URLs in scripts that are not exactly allowlisted', () => {
    const outcome = check({
      'index.html': GOOD_INDEX,
      'assets/index-abc.js': [
        'fetch("https://telemetry.example.com/collect");',
        'new WebSocket("wss://push.example.com/socket");',
        'const ns = "http://www.w3.org/2000/svg/evil";',
      ].join('\n'),
    });

    expect(outcome.errors).toEqual([
      'dist/assets/index-abc.js: absolute URL is not allowed in the bundle: https://telemetry.example.com/collect',
      'dist/assets/index-abc.js: absolute URL is not allowed in the bundle: wss://push.example.com/socket',
      'dist/assets/index-abc.js: absolute URL is not allowed in the bundle: http://www.w3.org/2000/svg/evil',
    ]);
    expect(outcome.warnings).toEqual([
      'allowlist entry is not used by the bundle (remove it): http://www.w3.org/2000/svg',
    ]);
  });

  it('fails on protocol-relative and escaped URLs in scripts', () => {
    const outcome = check({
      'index.html': GOOD_INDEX,
      'assets/index-abc.js': [
        's.src="//cdn.example.com/x.js";',
        'new Image().src="https:\\/\\/beacon.example.com\\/p.gif";',
        'import("//cdn.example.com/m.js");',
        "const u='\\/\\/img.example.com\\/a.png';",
        'fetch(`//api.example.com:8443`);',
        'const sock="ws:\\/\\/localhost:9000\\/s";',
      ].join('\n'),
    });

    expect(outcome.errors).toEqual([
      'dist/assets/index-abc.js: absolute URL (escaped) is not allowed in the bundle: https://beacon.example.com/p.gif',
      'dist/assets/index-abc.js: absolute URL (escaped) is not allowed in the bundle: ws://localhost:9000/s',
      'dist/assets/index-abc.js: protocol-relative URL is not allowed in the bundle: //cdn.example.com/x.js',
      'dist/assets/index-abc.js: protocol-relative URL is not allowed in the bundle: //cdn.example.com/m.js',
      'dist/assets/index-abc.js: protocol-relative URL is not allowed in the bundle: //img.example.com/a.png',
      'dist/assets/index-abc.js: protocol-relative URL is not allowed in the bundle: //api.example.com:8443',
    ]);
  });

  it('fails on protocol-relative and escaped URLs in stylesheets and inline scripts', () => {
    const outcome = check({
      'index.html': `${GOOD_INDEX}<script>var s=document.createElement('script');s.src='//cdn.example.com/i.js';</script>`,
      'assets/index-abc.css':
        '.a{--logo:"//img.example.com/l.png";--x:"https:\\/\\/x.example.com\\/y"}',
    });

    expect(outcome.errors).toEqual([
      'dist/assets/index-abc.css: absolute URL (escaped) is not allowed in the bundle: https://x.example.com/y',
      'dist/assets/index-abc.css: protocol-relative URL is not allowed in the bundle: //img.example.com/l.png',
      'dist/index.html: protocol-relative URL is not allowed in the bundle: //cdn.example.com/i.js',
    ]);
  });

  it('does not mistake comments, regex sources or bare slashes for protocol-relative URLs', () => {
    const outcome = check({
      'index.html': GOOD_INDEX,
      'assets/index-abc.js': [
        'const a="//";',
        'const b="// not a url";',
        'const c=/^\\/\\//.test(x);',
        'const d="//# sourceURL=x";',
        "const e='/assets/a.js';",
        'const f="a//b.example.com";',
      ].join('\n'),
    });

    expect(outcome.errors).toEqual([]);
  });

  it('matches escaped and protocol-relative URLs against the allowlist exactly, unescaped', () => {
    const fixture = createFixture({
      'dist/index.html': GOOD_INDEX,
      'dist/assets/index-abc.js': [
        'const ns="http:\\/\\/www.w3.org\\/2000\\/svg";',
        'const p="//www.w3.org/1999/xlink";',
        'const q="//www.w3.org/1999/xlink/extra";',
      ].join('\n'),
    });
    cleanups.push(fixture.cleanup);

    const outcome = checkNoExternal({
      distDir: join(fixture.root, 'dist'),
      allowlist: [
        ...ALLOWLIST,
        { url: '//www.w3.org/1999/xlink', reason: 'Protocol-relative identifier, never fetched.' },
      ],
    });

    expect(outcome.errors).toEqual([
      'dist/assets/index-abc.js: protocol-relative URL is not allowed in the bundle: //www.w3.org/1999/xlink/extra',
    ]);
    expect(outcome.warnings).toEqual([]);
    expect(outcome.allowlisted).toBe(2);
  });

  it('fails on external references inside SVG assets', () => {
    const outcome = check({
      'index.html': GOOD_INDEX,
      'assets/logo.svg':
        '<svg xmlns="http://www.w3.org/2000/svg"><image href="https://img.example.com/x.png"/></svg>',
    });

    expect(outcome.errors).toEqual([
      'dist/assets/logo.svg: <image href> points to another origin ("https:" URL): https://img.example.com/x.png',
    ]);
  });

  it('fails when the build output is missing or incomplete', () => {
    const missing = checkNoExternal({ distDir: join(UI_ROOT, 'does-not-exist'), allowlist: [] });
    expect(missing.errors[0]).toMatch(/build output not found/);

    const noIndex = check({ 'assets/a.js': '' });
    expect(noIndex.errors[0]).toMatch(/index\.html not found/);
  });

  it('accepts the source index.html of the ui', () => {
    const fixture = createFixture({ 'dist/index.html': GOOD_INDEX });
    cleanups.push(fixture.cleanup);

    const outcome = checkNoExternal({
      distDir: join(fixture.root, 'dist'),
      indexHtml: join(UI_ROOT, 'index.html'),
      allowlist: [],
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.scanned).toContain('index.html');
  });
});

describe('parseAllowlist', () => {
  it('accepts the committed allowlist', () => {
    const data = JSON.parse(
      readFileSync(join(UI_ROOT, 'scripts', 'external-allowlist.json'), 'utf8'),
    );
    expect(parseAllowlist(data).length).toBeGreaterThan(0);
  });

  it('rejects entries without a reason or with a non-URL', () => {
    expect(() => parseAllowlist({ allowed: [{ url: 'https://a.example' }] })).toThrow(/reason/);
    expect(() =>
      parseAllowlist({ allowed: [{ url: 'a.example', reason: 'long enough reason' }] }),
    ).toThrow(/absolute or protocol-relative URL/);
    expect(() =>
      parseAllowlist({
        allowed: [{ url: 'https:\\/\\/a.example', reason: 'long enough reason' }],
      }),
    ).toThrow(/absolute or protocol-relative URL/);
    expect(
      parseAllowlist({ allowed: [{ url: '//a.example/x', reason: 'long enough reason' }] }),
    ).toEqual([{ url: '//a.example/x', reason: 'long enough reason' }]);
    expect(() => parseAllowlist({})).toThrow(/"allowed" array/);
  });
});

describe('check-no-external CLI', () => {
  // Spawns a node process, which can exceed Vitest's 5 s default on a loaded machine.
  it('exits 1 when the bundle references another origin', () => {
    const fixture = createFixture({
      'dist/index.html': '<script src="https://cdn.example.com/x.js"></script>',
      'index.html': GOOD_INDEX,
      'allow.json': JSON.stringify({ allowed: [] }),
    });
    cleanups.push(fixture.cleanup);

    const run = spawnSync(
      process.execPath,
      [
        SCRIPT,
        '--dist',
        join(fixture.root, 'dist'),
        '--index',
        join(fixture.root, 'index.html'),
        '--allowlist',
        join(fixture.root, 'allow.json'),
      ],
      { encoding: 'utf8' },
    );

    expect(run.status).toBe(1);
    expect(run.stderr).toContain('ERROR dist/index.html: <script src> points to another origin');
  }, 30_000);

  // Spawns a node process, which can exceed Vitest's 5 s default on a loaded machine.
  it('exits 0 for a clean bundle', () => {
    const fixture = createFixture({
      'dist/index.html': GOOD_INDEX,
      'index.html': GOOD_INDEX,
      'allow.json': JSON.stringify({ allowed: [] }),
    });
    cleanups.push(fixture.cleanup);

    const run = spawnSync(
      process.execPath,
      [
        SCRIPT,
        '--dist',
        join(fixture.root, 'dist'),
        '--index',
        join(fixture.root, 'index.html'),
        '--allowlist',
        join(fixture.root, 'allow.json'),
      ],
      { encoding: 'utf8' },
    );

    expect(run.status).toBe(0);
    expect(run.stdout).toContain('errors=0');
  }, 30_000);
});
