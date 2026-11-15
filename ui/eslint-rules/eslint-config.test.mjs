// SPDX-License-Identifier: Apache-2.0
//
// Negative tests for the real eslint.config.mjs (SEC-60, T-UI-006, T-BLD-007): each snippet is
// linted as if it were a file at the given path in this project and must be rejected by the
// named rule. This also proves the plugins still work on the installed ESLint major.
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));
const HEADER = '// SPDX-License-Identifier: Apache-2.0\n';

// In-memory files used below. They do not exist on disk, so the TypeScript project service
// cannot find them in tsconfig.app.json; typescript-eslint's default project (with the app
// compiler options) provides their type information instead. Only parser options are
// overridden: the rules under test come from eslint.config.mjs unchanged.
const PROBE_FILES = [
  'src/features/probe/Probe.tsx',
  'src/features/probe/sink.ts',
  'src/features/probe/load.ts',
  'src/features/probe/ws.ts',
  'src/features/probe/a.ts',
  'src/features/probe/a.test.ts',
  'src/api/probeClient.ts',
  'src/api/client.mts',
  'src/api/wrapper.ts',
  'src/api/GeneratedProbe.tsx',
  'src/realtime/probeSocket.ts',
  'src/components/Button.ts',
  'src/components/Icon.ts',
  'src/app/providers.ts',
  'src/charts/TimeSeriesChart.ts',
  // Non-TypeScript extensions: forbidden in src/ (scripts/src-extensions.test.mjs) and still
  // linted with the src rules (defence in depth).
  'src/legacy/banner.js',
  'src/legacy/banner.mjs',
  'src/legacy/banner.cjs',
  'src/legacy/sink.mts',
  'src/legacy/sink.cts',
  'src/legacy/Widget.jsx',
];

/** @type {ESLint} */
let eslint;
// The first lintText call loads the plugins and starts the TypeScript project service, which
// takes several seconds (longer on a cold cache or a busy machine). Do that once here, under an
// explicit hook timeout, so every test below runs against a warm linter. The warm-up result is not
// asserted; the first test checks the same input. The first lint of each further probe path still
// builds a default-project program for it (several seconds, far more on a machine that runs a full
// Gradle build in parallel), so the tests below get a 60 s timeout instead of the default 5 s.
beforeAll(async () => {
  eslint = new ESLint({
    cwd: UI_ROOT,
    overrideConfig: {
      files: ['**/*.{ts,tsx,mts,cts}'],
      languageOptions: {
        parserOptions: {
          projectService: {
            allowDefaultProject: PROBE_FILES,
            defaultProject: 'tsconfig.app.json',
            maximumDefaultProjectFileMatchCount_THIS_WILL_SLOW_DOWN_LINTING: PROBE_FILES.length,
          },
        },
      },
    },
  });
  await eslint.lintText(CLEAN_COMPONENT, {
    filePath: join(UI_ROOT, 'src/features/probe/Probe.tsx'),
  });
}, 120_000);

/**
 * @param {string} code
 * @param {string} file path relative to ui/
 * @returns {Promise<string[]>} rule ids of the reported problems (or `<fatal> message`)
 */
async function ruleIds(code, file = 'src/features/probe/Probe.tsx') {
  if (file.startsWith('src/') && !PROBE_FILES.includes(file)) {
    throw new Error(`add ${file} to PROBE_FILES`);
  }
  // A probe path that exists on disk is also in the project service, and typescript-eslint then
  // refuses it as a default-project file (parsing error): probe names must stay virtual.
  if (existsSync(join(UI_ROOT, file))) {
    throw new Error(`${file} exists on disk; use a virtual probe path`);
  }
  const [result] = await eslint.lintText(code, { filePath: join(UI_ROOT, file) });
  if (result === undefined) {
    throw new Error('no lint result');
  }
  return result.messages.map((message) => message.ruleId ?? `<fatal> ${message.message}`);
}

const CLEAN_COMPONENT = `${HEADER}import { useTranslation } from 'react-i18next';

export function Probe({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <section className="probe" data-testid="probe">
      <h2 title={t('app.name')}>{t('app.tagline')}</h2>
      <span>{value}</span>
      <span>{42}</span>
    </section>
  );
}
`;

describe('eslint.config.mjs', { timeout: 60_000 }, () => {
  it('accepts a clean component', async () => {
    expect(await ruleIds(CLEAN_COMPONENT)).toEqual([]);
  });

  it('rejects dangerouslySetInnerHTML (react/no-danger)', async () => {
    const code = `${HEADER}export function Probe({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}
`;
    expect(await ruleIds(code)).toContain('react/no-danger');
  });

  it('ignores inline eslint-disable comments in src/ (exceptions go through eslint-allow.json)', async () => {
    const code = `${HEADER}export function Probe({ html }: { html: string }) {
  // eslint-disable-next-line react/no-danger
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}
`;
    expect(await ruleIds(code)).toContain('react/no-danger');
  });

  it('rejects dangerouslySetInnerHTML on a custom component (react/no-danger with customComponentNames)', async () => {
    const code = `${HEADER}import type { ComponentProps } from 'react';

function Box(props: ComponentProps<'div'>) {
  return <div {...props} />;
}

export function Probe({ html }: { html: string }) {
  return <Box dangerouslySetInnerHTML={{ __html: html }} />;
}
`;
    const ids = await ruleIds(code);
    expect(ids).toContain('react/no-danger');
    expect(ids).toContain('no-restricted-syntax');
  });

  it.each([
    [
      'a props object spread into a DOM element',
      `const props = { dangerouslySetInnerHTML: { __html: html } };
  return <div {...props} />;`,
    ],
    [
      'a quoted key in a spread props object',
      `const props = { 'dangerouslySetInnerHTML': { __html: html } };
  return <div {...props} />;`,
    ],
    [
      'createElement props',
      "return createElement('div', { dangerouslySetInnerHTML: { __html: html } });",
    ],
    [
      'member assignment',
      `const props: Record<string, unknown> = {};
  props.dangerouslySetInnerHTML = { __html: html };
  return createElement('div', props);`,
    ],
    [
      'computed member assignment',
      `const props: Record<string, unknown> = {};
  props['dangerouslySetInnerHTML'] = { __html: html };
  return createElement('div', props);`,
    ],
  ])(
    'rejects dangerouslySetInnerHTML passed through %s (no-restricted-syntax)',
    async (_name, body) => {
      const code = `${HEADER}import { createElement } from 'react';

export function Probe({ html }: { html: string }) {
  ${body}
}
`;
      expect(await ruleIds(code)).toContain('no-restricted-syntax');
    },
  );

  it('ignores inline eslint-disable comments for no-restricted-syntax too', async () => {
    const code = `${HEADER}export function Probe({ html }: { html: string }) {
  // eslint-disable-next-line no-restricted-syntax
  const props = { dangerouslySetInnerHTML: { __html: html } };
  return <div {...props} />;
}
`;
    expect(await ruleIds(code)).toContain('no-restricted-syntax');
  });

  it.each([
    ['JSX text', '<p>Hello world</p>'],
    ['title attribute', "<p title='Hello title'>{null}</p>"],
    ['aria-label attribute', '<button type="button" aria-label="Close">{null}</button>'],
    ['placeholder attribute', '<input placeholder="Search" />'],
    ['string in an expression container', "<p>{'Hello'}</p>"],
    ['template literal', '<p>{`Hello ${String(1)}`}</p>'],
  ])('rejects a literal user-facing string: %s (i18next/no-literal-string)', async (_name, jsx) => {
    const code = `${HEADER}export function Probe() {
  return ${jsx};
}
`;
    expect(await ruleIds(code)).toContain('i18next/no-literal-string');
  });

  it.each([
    ['innerHTML assignment', 'document.body.innerHTML = value;'],
    ['outerHTML assignment', 'document.body.outerHTML = value;'],
    ['computed innerHTML', "document.body['innerHTML'] = value;"],
    ['insertAdjacentHTML', "document.body.insertAdjacentHTML('beforeend', value);"],
    ['createContextualFragment', 'document.createRange().createContextualFragment(value);'],
    ['document.write', 'document.write(value);'],
    ['eval', 'eval(value);'],
    ['window.eval', 'window.eval(value);'],
    ['new Function', "new Function('a', value);"],
    ['Function()', "Function('a', value);"],
    ['new window.Function', "new window.Function('a', value);"],
    ['window.Function()', "window.Function('a', value);"],
    ['new DOMParser', "new DOMParser().parseFromString(value, 'text/html');"],
    ['new window.DOMParser', "new window.DOMParser().parseFromString(value, 'text/html');"],
    ['parseFromString', "parser.parseFromString(value, 'text/html');"],
    ['setHTMLUnsafe', 'document.body.setHTMLUnsafe(value);'],
    ['Document.parseHTMLUnsafe', 'Document.parseHTMLUnsafe(value);'],
    ['srcdoc assignment', "document.createElement('iframe').srcdoc = value;"],
    ['computed srcdoc assignment', "document.createElement('iframe')['srcdoc'] = value;"],
    ['setAttribute srcdoc', "document.body.setAttribute('srcdoc', value);"],
    ['setAttribute on* handler', "document.body.setAttribute('onclick', value);"],
    ['setAttribute on* handler in upper case', "document.body.setAttribute('ONLOAD', value);"],
    ['setAttributeNS on* handler', "document.body.setAttributeNS(null, 'onerror', value);"],
  ])('rejects the DOM/code sink %s (no-restricted-syntax)', async (_name, statement) => {
    const code = `${HEADER}export function sink(value: string, parser: DOMParser): void {
  ${statement}
}
`;
    expect(await ruleIds(code, 'src/features/probe/sink.ts')).toContain('no-restricted-syntax');
  });

  it('accepts setAttribute for attributes that are not HTML/code sinks', async () => {
    const code = `${HEADER}export function sink(value: string): void {
  document.body.setAttribute('title', value);
  document.body.setAttribute('data-open', value);
  document.body.setAttribute('aria-label', value);
}
`;
    expect(await ruleIds(code, 'src/features/probe/sink.ts')).not.toContain('no-restricted-syntax');
  });

  it.each([
    ['srcDoc', '<iframe srcDoc={html} title={html} />'],
    ['srcdoc', '<iframe srcdoc={html} title={html} />'],
  ])('rejects %s on iframes (no-restricted-syntax)', async (_name, jsx) => {
    const code = `${HEADER}export function Probe({ html }: { html: string }) {
  return ${jsx};
}
`;
    expect(await ruleIds(code)).toContain('no-restricted-syntax');
  });

  it('rejects direct fetch outside src/api and allows it inside', async () => {
    const code = `${HEADER}export async function load(): Promise<Response> {
  await window.fetch('/api/v1/a');
  return fetch('/api/v1/b');
}
`;
    const outside = await ruleIds(code, 'src/features/probe/load.ts');
    expect(outside).toContain('no-restricted-globals');
    expect(outside).toContain('no-restricted-properties');

    expect(await ruleIds(code, 'src/api/probeClient.ts')).toEqual([]);
  });

  it('rejects WebSocket outside src/realtime', async () => {
    const code = `${HEADER}export const socket = new WebSocket('/ws');
`;
    expect(await ruleIds(code, 'src/features/probe/ws.ts')).toContain('no-restricted-globals');
    expect(await ruleIds(code, 'src/realtime/probeSocket.ts')).toEqual([]);
    expect(await ruleIds(code, 'src/api/probeClient.ts')).toContain('no-restricted-globals');
  });

  it('rejects window.WebSocket outside src/realtime (no-restricted-properties)', async () => {
    const code = `${HEADER}export const socket = new window.WebSocket('/ws');
`;
    expect(await ruleIds(code, 'src/features/probe/ws.ts')).toContain('no-restricted-properties');
    expect(await ruleIds(code, 'src/realtime/probeSocket.ts')).toEqual([]);
  });

  it('rejects XMLHttpRequest outside src/api and allows it inside', async () => {
    const code = `${HEADER}export const a = new XMLHttpRequest();
export const b = new window.XMLHttpRequest();
`;
    const outside = await ruleIds(code, 'src/features/probe/load.ts');
    expect(outside).toContain('no-restricted-globals');
    expect(outside).toContain('no-restricted-properties');
    expect(await ruleIds(code, 'src/realtime/probeSocket.ts')).toContain('no-restricted-globals');
    expect(await ruleIds(code, 'src/api/probeClient.ts')).toEqual([]);
  });

  it.each([
    ['navigator.sendBeacon', "navigator.sendBeacon('/api/v1/apps', 'x');", 'no-restricted-syntax'],
    [
      'window.navigator.sendBeacon',
      "window.navigator.sendBeacon('/api/v1/apps', 'x');",
      'no-restricted-syntax',
    ],
    [
      "navigator['sendBeacon']",
      "navigator['sendBeacon']('/api/v1/apps', 'x');",
      'no-restricted-syntax',
    ],
    [
      'const { sendBeacon } = navigator',
      "const { sendBeacon } = navigator;\n  sendBeacon.call(navigator, '/api/v1/apps', 'x');",
      'no-restricted-syntax',
    ],
    ['new EventSource', "void new EventSource('/x');", 'no-restricted-globals'],
    ['new window.EventSource', "void new window.EventSource('/x');", 'no-restricted-properties'],
  ])(
    'rejects %s in every scope, src/api included (SEC-43: no network channel past the CSRF wrapper)',
    async (_name, statement, rule) => {
      const code = `${HEADER}export function send(): void {
  ${statement}
}
`;
      for (const file of [
        'src/features/probe/load.ts',
        'src/api/probeClient.ts',
        'src/realtime/probeSocket.ts',
      ]) {
        expect(await ruleIds(code, file), file).toContain(rule);
      }
    },
  );

  it('applies argos/no-token-storage to src/ but not to tests', async () => {
    const code = `${HEADER}export function save(token: string): void {
  localStorage.setItem('token', token);
  document.cookie = 'argos_at=' + token;
}
`;
    const ids = await ruleIds(code, 'src/features/probe/a.ts');
    expect(ids.filter((id) => id === 'argos/no-token-storage')).toHaveLength(2);
    expect(await ruleIds(code, 'src/features/probe/a.test.ts')).not.toContain(
      'argos/no-token-storage',
    );
    const preference = `${HEADER}export function save(language: string): void {
  localStorage.setItem('argos.ui.language', language);
}
`;
    expect(await ruleIds(preference, 'src/features/probe/a.ts')).toEqual([]);
  });

  it('applies argos/no-raw-href to src/', async () => {
    const code = `${HEADER}export function Probe({ url }: { url: string }) {
  return <a href={url}>{null}</a>;
}
`;
    expect(await ruleIds(code)).toContain('argos/no-raw-href');
  });

  it('applies argos/no-object-url to src/ (also src/api) but not to tests', async () => {
    const code = `${HEADER}export function link(blob: Blob): string {
  return URL.createObjectURL(blob);
}
`;
    expect(await ruleIds(code, 'src/features/probe/a.ts')).toContain('argos/no-object-url');
    // Only src/api/exportCsv.ts is exempt (eslint-allow.json), not the rest of src/api.
    expect(await ruleIds(code, 'src/api/wrapper.ts')).toContain('argos/no-object-url');
    expect(await ruleIds(code, 'src/features/probe/a.test.ts')).not.toContain(
      'argos/no-object-url',
    );
  });

  it('applies argos/untrusted-text to src/', async () => {
    const code = `${HEADER}import type { Untrusted } from '../../api/untrusted';

export function Probe({ service }: { service: Untrusted }) {
  return <td>{service}</td>;
}
`;
    expect(await ruleIds(code)).toContain('argos/untrusted-text');
  });

  it('brands the generated API types for argos/untrusted-text (x-argos-untrusted)', async () => {
    const code = `${HEADER}import type { components } from './generated/schema';

export function Probe({ app }: { app: components['schemas']['App'] }) {
  return <td>{app.name}</td>;
}
`;
    expect(await ruleIds(code, 'src/api/GeneratedProbe.tsx')).toEqual(['argos/untrusted-text']);
    const plain = `${HEADER}import type { components } from './generated/schema';

export function Probe({ app }: { app: components['schemas']['App'] }) {
  return <td>{app.app_id}</td>;
}
`;
    expect(await ruleIds(plain, 'src/api/GeneratedProbe.tsx')).toEqual([]);
  });

  it.each([
    ["import { Button } from 'antd';", 'src/features/probe/a.ts', 'src/components/Button.ts'],
    ["import 'antd/dist/reset.css';", 'src/features/probe/a.ts', 'src/app/providers.ts'],
    [
      "import { SearchOutlined } from '@ant-design/icons';",
      'src/features/probe/a.ts',
      'src/components/Icon.ts',
    ],
    ["import uPlot from 'uplot';", 'src/features/probe/a.ts', 'src/charts/TimeSeriesChart.ts'],
    [
      "import { client } from '../../api/generated/client';",
      'src/features/probe/a.ts',
      'src/api/wrapper.ts',
    ],
    ["import createClient from 'openapi-fetch';", 'src/features/probe/a.ts', 'src/api/wrapper.ts'],
    [
      "import createClient from 'openapi-fetch';",
      'src/components/Button.ts',
      'src/api/probeClient.ts',
    ],
    ["import { z } from 'zod';", 'src/features/probe/a.ts', 'src/app/providers.ts'],
    ["import { z } from 'zod/v4';", 'src/api/wrapper.ts', 'src/app/providers.ts'],
  ])(
    'restricts %s to its wrapper module (no-restricted-imports)',
    async (statement, outsideFile, insideFile) => {
      const code = `${HEADER}${statement}\n`;
      expect(await ruleIds(code, outsideFile)).toContain('no-restricted-imports');
      expect(await ruleIds(code, insideFile)).not.toContain('no-restricted-imports');
    },
  );

  it.each([
    ["import('antd')", 'src/features/probe/a.ts', 'src/components/Button.ts'],
    ["import('antd/es/button')", 'src/features/probe/a.ts', 'src/app/providers.ts'],
    ['import(`@ant-design/icons`)', 'src/features/probe/a.ts', 'src/components/Icon.ts'],
    ["import('uplot')", 'src/features/probe/a.ts', 'src/charts/TimeSeriesChart.ts'],
    ["import('../../api/generated/client')", 'src/features/probe/a.ts', 'src/api/wrapper.ts'],
    ["import('openapi-fetch')", 'src/features/probe/a.ts', 'src/api/wrapper.ts'],
    ["import('zod')", 'src/features/probe/a.ts', 'src/app/providers.ts'],
  ])(
    'restricts the dynamic %s to its wrapper module (no-restricted-syntax)',
    async (expression, outsideFile, insideFile) => {
      const code = `${HEADER}export function load(): Promise<unknown> {
  return ${expression};
}
`;
      expect(await ruleIds(code, outsideFile)).toContain('no-restricted-syntax');
      expect(await ruleIds(code, insideFile)).not.toContain('no-restricted-syntax');
    },
  );

  it('keeps the XSS sinks forbidden inside the wrapper modules', async () => {
    const code = `${HEADER}export function sink(value: string): void {
  document.body.innerHTML = value;
}
`;
    for (const file of ['src/components/Button.ts', 'src/charts/TimeSeriesChart.ts']) {
      expect(await ruleIds(code, file)).toContain('no-restricted-syntax');
    }
  });

  it('accepts dynamic imports of unrestricted modules', async () => {
    const code = `${HEADER}export function load(): Promise<unknown> {
  return import('./Probe');
}
`;
    expect(await ruleIds(code, 'src/features/probe/a.ts')).not.toContain('no-restricted-syntax');
  });

  it.each([
    'src/legacy/banner.js',
    'src/legacy/banner.mjs',
    'src/legacy/banner.cjs',
    'src/legacy/sink.mts',
    'src/legacy/sink.cts',
  ])('applies the XSS and fetch rules to %s (non-TypeScript extensions in src/)', async (file) => {
    const code = `${HEADER}document.body.innerHTML = String(location.hash);
document.body.insertAdjacentHTML('beforeend', String(location.hash));
void fetch('/api/v1/a');
`;
    const ids = await ruleIds(code, file);
    expect(ids).toContain('no-restricted-syntax');
    expect(ids).toContain('no-restricted-globals');
  });

  it('applies react/no-danger to .jsx components in src/', async () => {
    const code = `${HEADER}export function Widget({ html }) {
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}
`;
    const ids = await ruleIds(code, 'src/legacy/Widget.jsx');
    expect(ids).toContain('react/no-danger');
    expect(ids).toContain('no-restricted-syntax');
  });

  it('applies the wrapper-module scopes to other extensions too', async () => {
    const code = `${HEADER}export function load(): Promise<Response> {
  return fetch('/api/v1/a');
}
`;
    expect(await ruleIds(code, 'src/api/client.mts')).not.toContain('no-restricted-globals');
  });

  it('rejects devDependencies in production code but allows them in tests', async () => {
    const code = `${HEADER}import { describe } from 'vitest';

export const d = describe;
`;
    expect(await ruleIds(code, 'src/features/probe/a.ts')).toContain(
      'import-x/no-extraneous-dependencies',
    );
    expect(await ruleIds(code, 'src/features/probe/a.test.ts')).not.toContain(
      'import-x/no-extraneous-dependencies',
    );
  });

  it('rejects files without the SPDX header (argos/spdx-header)', async () => {
    expect(await ruleIds(CLEAN_COMPONENT.slice(HEADER.length))).toContain('argos/spdx-header');
    expect(await ruleIds('export const a = 1;\n', 'scripts/probe.mjs')).toContain(
      'argos/spdx-header',
    );
  });

  it('enforces accessibility rules (jsx-a11y-x)', async () => {
    const code = `${HEADER}export function Probe({ src }: { src: string }) {
  return <img src={src} />;
}
`;
    expect(await ruleIds(code)).toContain('jsx-a11y-x/alt-text');
  });
});
