// SPDX-License-Identifier: Apache-2.0
//
// RuleTester cases for argos/untrusted-text (T-SEC-60, SEC-60, docs/15 §6.6). The rule is
// type-aware: the snippets are linted as a .tsx file of this project with the TypeScript project
// service, and two cases use the real generated API types (src/api/generated/schema.ts).
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import { describe, it } from 'vitest';

import untrustedText from './untrusted-text.mjs';

RuleTester.describe = describe;
// The first case starts the TypeScript project service, which takes several seconds.
RuleTester.it = (name, fn) => it(name, fn, 60_000);
RuleTester.itOnly = it.only;

const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));
const FILE = 'src/features/probe/UntrustedProbe.tsx';
const filename = join(UI_ROOT, FILE);

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: {
      ecmaFeatures: { jsx: true },
      projectService: { allowDefaultProject: [FILE], defaultProject: 'tsconfig.app.json' },
      tsconfigRootDir: UI_ROOT,
    },
  },
});

const PRELUDE = `import { createElement, type ReactElement, type ReactNode } from 'react';
import type { Untrusted } from '../../api/untrusted';
declare function SafeText(props: { value: string; maxLines?: number; title?: string }): ReactElement;
declare function SafeLink(props: { href: string; children?: ReactNode }): ReactElement;
declare function CodeBlock(props: { value: string; lang?: string }): ReactElement;
declare function Input(props: { value: string }): ReactElement;
declare function Trans(props: { i18nKey: string; components?: Record<string, ReactElement> }): ReactElement;
declare function t(key: string, options?: Record<string, unknown>): string;
declare function formatNumber(value: number, language: string): string;
declare const name: Untrusted;
declare const url: Untrusted;
declare const maybe: Untrusted | null;
declare const names: Untrusted[];
declare const label: string;
declare const count: number;
void createElement;
`;

/**
 * @param {string} body
 * @returns {{ code: string, filename: string }}
 */
const tsx = (body) => ({ code: `${PRELUDE}${body}\n`, filename });

/** @param {string} element @param {string} attribute */
const attribute = (element, attribute) => ({
  messageId: 'attribute',
  data: { element, attribute },
});
const child = { messageId: 'child' };

ruleTester.run('argos/untrusted-text (T-SEC-60)', untrustedText, {
  valid: [
    tsx('export const a = <SafeText value={name} />;'),
    tsx('export const a = <SafeText value={maybe ?? label} maxLines={2} />;'),
    tsx('export const a = <SafeLink href={url}><SafeText value={url} /></SafeLink>;'),
    tsx('export const a = <CodeBlock value={name} lang="sql" />;'),
    tsx('export const a = <ul>{names.map((n) => <li key={n}><SafeText value={n} /></li>)}</ul>;'),
    tsx('export const a = <span title={label}>{label}{count}</span>;'),
    tsx('export const a = <span>{name.length}</span>;'),
    tsx("export const a = <span>{name === 'GET' ? 'read' : 'write'}</span>;"),
    tsx('export const a = <span>{names.includes(name) ? count : 0}</span>;'),
    tsx("export const a = <span>{names.length > 0 && 'many'}</span>;"),
    tsx('export const a = createElement(SafeText, { value: name });'),
    tsx('export const a = <span {...{ className: label }} />;'),
    // Text built from trusted values, and Untrusted values only where SafeText takes them.
    tsx("export const a = <span title={t('x')}>{t('x')}</span>;"),
    tsx("export const a = <span aria-label={t('x', { count })}>{t('x', { count, label })}</span>;"),
    tsx('export const a = <span>{names.length}{name.length}</span>;'),
    tsx("export const a = <SafeText value={name.split('/')[0] ?? ''} />;"),
    tsx("export const a = <span>{formatNumber(count, label)}{label.split('/')[0]}</span>;"),
    tsx('export const a = <span>{JSON.stringify({ n: count, l: label })}</span>;'),
    tsx('export const a = <Trans i18nKey="x" components={{ v: <SafeText value={name} /> }} />;'),
    // The real generated types: the brand comes from x-argos-untrusted (gen:api).
    tsx(`import type { components } from '../../api/generated/schema';
declare const app: components['schemas']['App'];
export const a = <SafeText value={app.name} />;
export const b = <span>{app.app_id}</span>;
export const c = <SafeText value={app.name.split('/')[0] ?? ''} />;`),
    {
      ...tsx('export const a = <Input value={name} />;'),
      options: [{ allowed: { Input: ['value'] } }],
    },
  ],
  invalid: [
    { ...tsx('export const a = <td>{name}</td>;'), errors: [child] },
    { ...tsx('export const a = <>{names}</>;'), errors: [child] },
    { ...tsx("export const a = <span>{maybe ?? '—'}</span>;"), errors: [child] },
    { ...tsx('export const a = <span>{`${name}!`}</span>;'), errors: [child] },
    { ...tsx("export const a = <span>{'service ' + name}</span>;"), errors: [child] },
    { ...tsx('export const a = <span>{name as string}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{String(name)}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{name.trim()}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{name.slice(0, 10)}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{count > 0 ? name : label}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{[label, name]}</span>;'), errors: [child] },
    { ...tsx('export const a = <SafeLink href={url}>{url}</SafeLink>;'), errors: [child] },
    // Text derived from an Untrusted value through member access and further calls.
    { ...tsx("export const a = <span>{name.split('/')[0]}</span>;"), errors: [child] },
    { ...tsx('export const a = <span>{name[0]}</span>;'), errors: [child] },
    { ...tsx('export const a = <span>{JSON.stringify({ n: name })}</span>;'), errors: [child] },
    {
      ...tsx('export const a = <span>{JSON.stringify({ ...{ n: name }, c: count })}</span>;'),
      errors: [child],
    },
    { ...tsx("export const a = <span>{Array.from(name).join('')}</span>;"), errors: [child] },
    {
      ...tsx("export const a = <span>{names.map((n) => n.trim()).join(', ')}</span>;"),
      errors: [child],
    },
    { ...tsx('export const a = <span>{`a ${`b ${name}`}`}</span>;'), errors: [child] },
    {
      ...tsx("export const a = <span>{`${name.split('/')[0] ?? ''}`.toUpperCase()}</span>;"),
      errors: [child],
    },
    // i18n interpolation of collected strings (documented in src/security/untrusted.md).
    { ...tsx("export const a = <span>{t('x', { name })}</span>;"), errors: [child] },
    {
      ...tsx('export const a = <button type="button" aria-label={t(\'x\', { name })} />;'),
      errors: [attribute('button', 'aria-label')],
    },
    {
      ...tsx('export function A<T extends Untrusted>({ v }: { v: T }) { return <b>{v}</b>; }'),
      errors: [child],
    },
    { ...tsx('export const a = <span title={name} />;'), errors: [attribute('span', 'title')] },
    {
      ...tsx('export const a = <img alt={name} src="/x.svg" />;'),
      errors: [attribute('img', 'alt')],
    },
    {
      ...tsx('export const a = <span data-service={`svc:${name}`} />;'),
      errors: [attribute('span', 'data-service')],
    },
    {
      ...tsx('export const a = <SafeText value={name} title={name} />;'),
      errors: [attribute('SafeText', 'title')],
    },
    {
      ...tsx(
        'export const a = <SafeLink href={label}>{null}</SafeLink>; export const b = <Input value={name} />;',
      ),
      errors: [attribute('Input', 'value')],
    },
    {
      ...tsx('export const a = <span {...{ title: name }} />;'),
      errors: [attribute('span', 'title')],
    },
    {
      ...tsx(
        'declare const props: { title: Untrusted; id: string };\nexport const a = <span {...props} />;',
      ),
      errors: [attribute('span', 'title')],
    },
    {
      ...tsx("export const a = createElement('span', null, name);"),
      errors: [{ messageId: 'createElement' }],
    },
    {
      ...tsx("export const a = createElement('span', { title: name });"),
      errors: [{ messageId: 'createElement' }],
    },
    {
      ...tsx(`import type { components } from '../../api/generated/schema';
declare const app: components['schemas']['App'];
export const a = <td>{app.name}</td>;`),
      errors: [child],
    },
    {
      ...tsx(`import type { components } from '../../api/generated/schema';
declare const tx: components['schemas']['TransactionDetail'];
export const a = <dd title={tx.headers['user-agent']}>{tx.http_method}</dd>;`),
      errors: [attribute('dd', 'title'), child],
    },
  ],
});
