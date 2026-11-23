// SPDX-License-Identifier: Apache-2.0
//
// Schema of the required-elements files (docs/18 §13, DSN-18-10): ui/e2e/required/<screen>.yaml
// codifies the docs/05 §2 checklist of a screen, one entry per item, and the engine
// (e2e/required/engine.ts) turns every entry into a Playwright test T-E2E-<screen>-<n>.
//
//   screen: common                       file name without .yaml
//   title: ...                           what the file covers
//   source: docs/05 §2.0                 the checklist
//   first_number: 30                     number of the first item's test id (default 1), for a
//                                        screen whose spec already uses T-E2E-<screen>-1.. (alerts)
//   items:
//     - id: 2.0-4                        the docs/05 item id (annotation req:<id>)
//       title: ...                       test title after the id
//       req: [F-U16, SEC-64]             further requirement ids (annotations req:<id>)
//       owner: WO-33                     work order that completes it (default WO-30); an owner
//                                        outside RUNNING_OWNERS makes the test a test.fixme
//                                        naming that WO
//       coverage: component T-UI-004 ... where the item is proven until then (fixme reason)
//       role: viewer                     admin | operator | viewer (default) | anonymous
//       timezone: Asia/Seoul             browser time zone of the item (default UTC)
//       ws: server                       the ws fixture forwards to the real /ws (wsMode 'server');
//                                        default the docs/14 §5 stub
//       route: /apps/{primary_app_id}/xlog?from={range_from}&to={range_to}
//                                        opened first; {name} = a manifest variable
//       steps:                           goto <route> | click <testid> | press <key> |
//         - click: time-range-prev       fill {testid, value}
//       assert:                          visible <testid> | hidden <testid> |
//         - visible: live-resume         urlParam {name, equals (null = absent)} |
//         - check: shortcutsMoveRange    text {testid, key, contains?} | check <custom check>
import { z } from 'zod';

import { parseYaml } from './yaml';

const TestId = z.string().regex(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/, 'data-testid in kebab case');
const Variable = /\{([a-z0-9_.]+)\}/g;

export const Step = z.union([
  z.strictObject({ goto: z.string().startsWith('/') }),
  z.strictObject({ click: TestId }),
  z.strictObject({ press: z.string().min(1).max(32) }),
  z.strictObject({ fill: z.strictObject({ testid: TestId, value: z.string() }) }),
]);
export type Step = z.infer<typeof Step>;

export const Assertion = z.union([
  z.strictObject({ visible: TestId }),
  z.strictObject({ hidden: TestId }),
  z.strictObject({
    urlParam: z.strictObject({ name: z.string().min(1), equals: z.string().nullable() }),
  }),
  z.strictObject({
    text: z.strictObject({
      testid: TestId,
      key: z.string().regex(/^[a-z]+:[A-Za-z0-9_.]+$/),
      contains: z.boolean().optional(),
    }),
  }),
  z.strictObject({ check: z.string().regex(/^[a-z][A-Za-z0-9]*$/) }),
]);
export type Assertion = z.infer<typeof Assertion>;

/**
 * Work orders whose items run as tests. An item of any other owner is a test.fixme naming that
 * owner and its coverage. A screen work order adds itself here when its items become tests
 * (WO-33: the XLog screen and its items of common.yaml; WO-34: the transaction detail and the
 * trace view; WO-32: the dashboard and its items of common.yaml; WO-22: the outage shading and the
 * real WebSocket server on the dashboard, ADR-0010 C-216 (2); WO-31: the overview; WO-36: the
 * statistics screens; WO-25: the firing alert panels of the dashboard and the overview; WO-35:
 * the active transactions screen; WO-38: the alert history, rule and channel screens and the alert
 * transition markers of deep link 4).
 */
export const RUNNING_OWNERS: ReadonlySet<string> = new Set([
  'WO-22',
  'WO-25',
  'WO-30',
  'WO-31',
  'WO-32',
  'WO-33',
  'WO-34',
  'WO-35',
  'WO-36',
  'WO-38',
]);

export const RequiredItem = z
  .strictObject({
    id: z.preprocess(
      (value) => (typeof value === 'number' ? String(value) : value),
      z.string().regex(/^\d+\.\d+-\d+$/, 'docs/05 item id such as 2.0-4'),
    ),
    title: z.string().min(3),
    req: z.array(z.string().regex(/^(F|SEC|D|N|O|S|DSN)-[A-Z0-9-]+$/)).default([]),
    owner: z
      .string()
      .regex(/^WO-\d+$/)
      .default('WO-30'),
    coverage: z.string().optional(),
    role: z.enum(['admin', 'operator', 'viewer', 'anonymous']).default('viewer'),
    /** Browser time zone of the item (IANA name); default UTC (playwright.config.ts). */
    timezone: z
      .string()
      .regex(/^[A-Za-z]+(\/[A-Za-z_]+)*$/)
      .optional(),
    /** The ws fixture mode of the item (e2e/fixtures/stack.ts wsMode); default the stub. */
    ws: z.enum(['stub', 'server']).optional(),
    route: z.string().startsWith('/').optional(),
    steps: z.array(Step).default([]),
    assert: z.array(Assertion).default([]),
  })
  .superRefine((item, ctx) => {
    if (RUNNING_OWNERS.has(item.owner) && (item.route === undefined || item.assert.length === 0)) {
      ctx.addIssue({
        code: 'custom',
        message: `${item.id}: an item owned by ${item.owner} needs a route and at least one assertion`,
      });
    }
    if (!RUNNING_OWNERS.has(item.owner) && item.coverage === undefined) {
      ctx.addIssue({
        code: 'custom',
        message: `${item.id}: an item owned by ${item.owner} must say where it is covered until then (coverage)`,
      });
    }
  });
export type RequiredItem = z.infer<typeof RequiredItem>;

export const RequiredFile = z.strictObject({
  screen: z.string().regex(/^[a-z0-9-]+$/),
  title: z.string(),
  source: z.string(),
  /** Number of the first item's test id (T-E2E-<screen>-<first_number>); default 1. */
  first_number: z.number().int().min(1).max(999).default(1),
  items: z.array(RequiredItem).min(1),
});
export type RequiredFile = z.infer<typeof RequiredFile>;

/** Parses and validates a required-elements file. */
export function parseRequiredFile(source: string, name: string): RequiredFile {
  const parsed = RequiredFile.safeParse(parseYaml(source));
  if (!parsed.success) {
    throw new Error(
      `${name}: ${parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')}`,
    );
  }
  if (`${parsed.data.screen}.yaml` !== name.split(/[\\/]/).pop()) {
    throw new Error(`${name}: screen ${parsed.data.screen} does not match the file name`);
  }
  return parsed.data;
}

/** Names of the variables a template uses. */
export function templateVariables(template: string): string[] {
  return [...template.matchAll(Variable)].map((m) => m[1] ?? '');
}

/** Fills `{name}` from `variables`; throws on an unknown name. */
export function fillTemplate(
  template: string,
  variables: Readonly<Record<string, string>>,
): string {
  return template.replace(Variable, (_, name: string) => {
    const value = variables[name];
    if (value === undefined) {
      throw new Error(`unknown variable {${name}} in ${template}`);
    }
    return value;
  });
}

/** The test title of item `index` (0-based) of a file (numbered from the file's first_number). */
export function testTitle(file: RequiredFile, index: number): string {
  const item = file.items[index];
  if (item === undefined) {
    throw new Error(`no item ${String(index)}`);
  }
  return `T-E2E-${file.screen}-${String(file.first_number + index)} ${item.id} ${item.title}`;
}
