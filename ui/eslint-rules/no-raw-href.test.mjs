// SPDX-License-Identifier: Apache-2.0
//
// RuleTester cases for argos/no-raw-href (T-SEC-60, docs/16 §9.2·§9.3).
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import { describe, it } from 'vitest';

import noRawHref from './no-raw-href.mjs';

RuleTester.describe = describe;
// The typed cases start a TypeScript project service on their first run.
RuleTester.it = (name, fn) => it(name, fn, 60_000);
RuleTester.itOnly = it.only;

const ruleTester = new RuleTester({
  languageOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

/** @param {string} element @param {string} [attribute] */
const raw = (element, attribute = 'href') => ({
  messageId: 'raw',
  data: { element, attribute },
});

ruleTester.run('argos/no-raw-href (T-SEC-60)', noRawHref, {
  valid: [
    '<a href="/apps/3/dashboard">x</a>;',
    '<a href="/">x</a>;',
    '<a href="#summary">x</a>;',
    '<a href="?tab=sql">x</a>;',
    '<a href="./detail">x</a>;',
    '<a href={"/login"}>x</a>;',
    '<a href={`/apps/${appId}/xlog?from=${from}`}>x</a>;',
    '<a href={`#row-${id}`}>x</a>;',
    '<a href={safeHref(url)}>x</a>;',
    '<a href={links.safeHref(url)}>x</a>;',
    '<a href={undefined}>x</a>;',
    '<a href={null}>x</a>;',
    '<a href={open ? safeHref(url) : undefined}>x</a>;',
    "<a href={safeHref(url) ?? '/apps'}>x</a>;",
    '<a href={enabled && safeHref(url)}>x</a>;',
    'const target = safeHref(url); <a href={target}>x</a>;',
    "const HOME = '/overview'; <a href={HOME}>x</a>;",
    '<use xlinkHref="#icon-alert" />;',
    '<a className="link" title={label}>x</a>;',
    // SafeLink validates its href itself (safeHref inside); local components are not links.
    '<SafeLink href={url}>x</SafeLink>;',
    'function Link({ href }) { return null; } <Link href={href} />;',
    "import { Link } from 'react-router'; <Link to={target}>x</Link>;",
    'import { Button } from \'antd\'; <Button href="/admin/apps">x</Button>;',
    "import { Button } from 'antd'; <Button onClick={go}>x</Button>;",
    "import { Breadcrumb } from 'antd'; <Breadcrumb items={[{ title: 'Apps', href: '/apps' }]} />;",
    "import { Typography } from 'antd'; <Typography.Link href={safeHref(url)}>x</Typography.Link>;",
    '<a {...{ href: safeHref(url), title: name }}>x</a>;',
    {
      code: '<a href={sanitizeUrl(url)}>x</a>;',
      options: [{ sanitizers: ['sanitizeUrl'] }],
    },
  ],
  invalid: [
    { code: '<a href={url}>x</a>;', errors: [raw('a')] },
    { code: '<a href={props.href}>x</a>;', errors: [raw('a')] },
    { code: '<a href={row.url}>x</a>;', errors: [raw('a')] },
    { code: '<a href="https://example.com/docs">x</a>;', errors: [raw('a')] },
    { code: '<a href="javascript:window.__xss=1">x</a>;', errors: [raw('a')] },
    { code: '<a href="JaVaScRiPt:alert(1)">x</a>;', errors: [raw('a')] },
    { code: '<a href="data:text/html,<script>x</script>">x</a>;', errors: [raw('a')] },
    { code: '<a href="//evil.example/x">x</a>;', errors: [raw('a')] },
    { code: '<a href="/\\evil.example">x</a>;', errors: [raw('a')] },
    { code: '<a href={`/${path}`}>x</a>;', errors: [raw('a')] },
    { code: '<a href={`${base}/apps`}>x</a>;', errors: [raw('a')] },
    { code: "<a href={'/apps/' + id}>x</a>;", errors: [raw('a')] },
    { code: "let target = '/apps'; <a href={target}>x</a>;", errors: [raw('a')] },
    { code: 'const target = row.url; <a href={target}>x</a>;', errors: [raw('a')] },
    { code: '<a href={open ? safeHref(url) : url}>x</a>;', errors: [raw('a')] },
    { code: '<a href={url ?? safeHref(other)}>x</a>;', errors: [raw('a')] },
    { code: '<a href={encodeURI(url)}>x</a>;', errors: [raw('a')] },
    { code: '<area href={url} alt="" />;', errors: [raw('area')] },
    { code: '<use xlinkHref={url} />;', errors: [raw('use', 'xlinkHref')] },
    { code: '<a {...{ href: url }}>x</a>;', errors: [raw('a')] },
    {
      code: "import { Button } from 'antd'; <Button href={url}>x</Button>;",
      errors: [raw('Button')],
    },
    {
      code: "import { Button as AntButton } from 'antd'; <AntButton href={row.link}>x</AntButton>;",
      errors: [raw('AntButton')],
    },
    {
      code: "import { Typography } from 'antd'; <Typography.Link href={url}>x</Typography.Link>;",
      errors: [raw('Typography.Link')],
    },
    {
      code: "import { Typography } from 'antd'; const { Link } = Typography; <Link href={url}>x</Link>;",
      errors: [raw('Link')],
    },
    {
      code: "import Button from 'antd/es/button'; <Button href={url}>x</Button>;",
      errors: [raw('Button')],
    },
    {
      code: "import { Breadcrumb } from 'antd'; <Breadcrumb items={[{ title: 'x', href: url }]} />;",
      errors: [raw('Breadcrumb')],
    },
    {
      code: "import { Breadcrumb } from 'antd'; <Breadcrumb items={rows.map((r) => ({ title: r.name, href: r.url }))} />;",
      errors: [raw('Breadcrumb')],
    },
  ],
});

const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));
const TYPED_FILE = 'src/features/probe/RawHrefProbe.tsx';

const typedTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: {
      ecmaFeatures: { jsx: true },
      projectService: { allowDefaultProject: [TYPED_FILE], defaultProject: 'tsconfig.app.json' },
      tsconfigRootDir: UI_ROOT,
    },
  },
});

const filename = join(UI_ROOT, TYPED_FILE);

typedTester.run('argos/no-raw-href with type information (T-SEC-60)', noRawHref, {
  valid: [
    {
      filename,
      code: 'declare const props: { title: string; className: string };\nexport const a = <a {...props}>x</a>;\n',
    },
    {
      filename,
      code:
        'declare function safeHref(url: string): string | undefined;\ndeclare const url: string;\n' +
        'export const a = <a href={safeHref(url) as string}>x</a>;\n' +
        'export const b = <a href={safeHref(url)!}>x</a>;\n',
    },
  ],
  invalid: [
    {
      filename,
      code: 'declare const props: { href: string; title: string };\nexport const a = <a {...props}>x</a>;\n',
      errors: [{ messageId: 'spread', data: { element: 'a' } }],
    },
  ],
});
