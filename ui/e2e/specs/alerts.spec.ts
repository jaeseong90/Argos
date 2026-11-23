// SPDX-License-Identifier: Apache-2.0
//
// The alert screens on the real stack (WO-38, docs/15 §9.9, docs/05 §2.4 and §2.9, docs/18 T-E2E
// alerts "규칙 → 발화 → 이력 → ACK", T-ACPT-15 and -17 automatic parts): a rule made from the error
// rate template fires on the seeded traffic, is found in the history, acknowledged with a note and
// muted; the channels validate their destinations, keep their secrets masked and test-send to the
// fake receivers of e2e/fixtures/fakeReceivers.ts (never to an outside network); the overview and
// dashboard alert rows, the deep link back after a login and the realtime refresh.
//
// Own rules, mutes and channels carry a run tag and are deleted when the tests end; the seed alert
// (e2e/stack/seed/alerts.ts) is never acknowledged, muted or deleted. Screens are compared with the
// request they sent (captured) and the API asked the same question (alertsChecks.ts). The checklist
// items themselves are required/alerts.yaml (T-E2E-alerts-30 .. 40).
//
// Visual tests (T-E2E-alerts-20 .. 25) run in the pinned Linux image only (e2e:docker); the alert
// data of the ready screens is fixed through page.route, so the baselines do not follow the clock.
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';

import type { Locator, Page, Route, TestInfo } from '@playwright/test';

import { apiDashboard } from '../fixtures/dashboardOracle';
import {
  readReceiverConfig,
  receiverPlan,
  startHttpReceiver,
  startSmtpReceiver,
  type HttpReceiver,
  type SmtpReceiver,
} from '../fixtures/fakeReceivers';
import { shellMasks } from '../fixtures/screenshots';
import { apiAs as loginApi, expect, storageStateOf, test } from '../fixtures/stack';
import { absolute, expectShell, expectUrl, state } from '../fixtures/ui';
import {
  ALERT_COLUMNS,
  ALERTS_CHECKS,
  alertsPath,
  alertsTable,
  alertsWindow,
  alertValueText,
  apiActiveAlerts,
  apiChannels,
  apiEventsPage,
  apiMutes,
  apiPreview,
  apiRuleDetail,
  apiTemplates,
  capture,
  ceilMinute,
  createOwnRule,
  deleteChannelQuietly,
  deleteMuteQuietly,
  deleteRuleQuietly,
  errorDetails,
  expectHistorySettled,
  expectRowsEqualAnswer,
  fillNewChannel,
  floorMinute,
  isoSeconds,
  linkKey,
  makeSeedLike,
  queryOf,
  recordPreviews,
  recordResponses,
  runTag,
  settledPreview,
  squashText,
  tableRowsOf,
  waitForRuleEvent,
  type AlertsCheckContext,
  type ApiAlertEvent,
  type ApiChannel,
  type ApiRule,
} from '../required/alertsChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { manifestAlert, type ManifestAlert, type SeedManifest } from '../stack/manifest';
import { credentialFile } from '../stack/paths';

const ALERTS = { type: 'req', description: 'F-U09' };
const MINUTE = 60_000;

function req(...ids: string[]): { type: string; description: string }[] {
  return [ALERTS, ...ids.map((description) => ({ type: 'req', description }))];
}

/** A named check of required/alertsChecks.ts (throws when it is not there). */
function alertsCheck(name: string): (ctx: AlertsCheckContext) => Promise<void> {
  const check = ALERTS_CHECKS[name];
  if (check === undefined) {
    throw new Error(`alertsChecks.ts has no check ${name}`);
  }
  return check;
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

/** A screenshot attached to the report as evidence (copied to the PM archive at integration). */
async function evidence(
  page: Page,
  testInfo: TestInfo,
  name: string,
  locator?: Locator,
): Promise<void> {
  const path = testInfo.outputPath(name);
  if (locator === undefined) {
    await page.screenshot({ path, fullPage: true });
  } else {
    await locator.screenshot({ path });
  }
  await testInfo.attach(name, { path, contentType: 'image/png' });
}

/** The history row of an event. */
function eventRow(page: Page, eventId: string): Locator {
  return tableRowsOf(alertsTable(page, 'alerts-events')).and(
    page.locator(`[data-row-key="${eventId}"]`),
  );
}

function pinnedRow(page: Page, eventId: string): Locator {
  return page.locator(`[data-testid="alerts-pinned-row"][data-event-id="${eventId}"]`);
}

/** The active (FIRING or ACKED) item of a rule in GET /overview/alerts, or undefined. */
async function activeOfRule(
  api: Awaited<ReturnType<typeof loginApi>>,
  ruleId: string,
): Promise<ApiAlertEvent | undefined> {
  return (await apiActiveAlerts(api)).find((a) => a.rule.id === ruleId);
}

/** The history of one rule from 30 minutes before `ts` to the next minute (absolute). */
function ruleHistoryPath(ruleId: string, ts: string, extra: Record<string, string> = {}): string {
  return alertsPath({
    from: isoSeconds(floorMinute(Date.parse(ts) - 30 * MINUTE)),
    to: isoSeconds(ceilMinute(Date.now()) + MINUTE),
    rule: ruleId,
    ...extra,
  });
}

async function signIn(page: Page, seed: SeedManifest, role: 'viewer' | 'operator'): Promise<void> {
  await page.getByTestId('login-username').fill(seed.users[role]);
  await page
    .getByTestId('login-password')
    .fill(readFileSync(credentialFile(seed.run_id, role), 'utf8'));
  await page.getByTestId('login-submit').click();
}

/** The seed alert when it is still active (a reused stack resolves it after an hour, C-226 (14)). */
async function activeSeedAlert(
  api: Awaited<ReturnType<typeof loginApi>>,
  alert: ManifestAlert,
): Promise<ApiAlertEvent | undefined> {
  return (await apiActiveAlerts(api)).find((a) => a.event_id === alert.event_id);
}

const SEED_RESOLVED =
  'the seed alert is no longer active (a reused stack resolves it after an hour, ADR-0010 C-226 (14))';

// ------------------------------------------------------------------- rule -> fire -> ACK

test.describe('rule -> fire -> history -> ACK (docs/18 T-E2E alerts)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ role: 'operator' });

  const tag = runTag();
  const ruleName = `E2E template rule ${tag}`;
  const ackNote = `E2E acknowledged ${tag}`;
  let ruleId: string | null = null;
  const mutes: string[] = [];

  test.afterAll(async ({ session, seed }) => {
    const api = await loginApi(session, seed, 'admin');
    for (const id of mutes) {
      await deleteMuteQuietly(api, id);
    }
    if (ruleId !== null) {
      await deleteRuleQuietly(api, ruleId);
    }
  });

  test(
    'T-E2E-alerts-4 a rule from the error rate template, adjusted like the seed rule (changed fields marked), has firings in its 7-day preview, is saved and listed (evidence rules.png, rule-editor.png)',
    { annotation: req('05-2.4-1', '05-2.4-2', '05-2.4-3', 'F-S24', 'F-S25') },
    async ({ page, seed, apiAs }, testInfo) => {
      test.setTimeout(180_000);
      const alert = manifestAlert(seed);
      const api = await apiAs('operator');
      await page.goto(`/alerts/rules?app=${String(alert.app_id)}`);
      await expectShell(page);
      await page.getByTestId('alert-rule-from-template').click();
      await expect(page.getByTestId('alert-rule-template-drawer')).toBeVisible();
      await page.getByTestId('alert-rule-template-error_rate-choose').click();
      await expectUrl(
        page,
        (s) => s.path === '/alerts/rules/new' && s.params.get('template') === 'error_rate',
        'a new rule from the error_rate template',
      );
      await page.getByTestId('alert-rule-field-name').fill(ruleName);
      const previews = await recordPreviews(page);
      try {
        await makeSeedLike(page);
        const dots = page.getByTestId('alert-rule-diff-dot');
        await expect
          .poll(() => dots.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-field'))))
          .toEqual(expect.arrayContaining(['window', 'for', 'min_samples']));
        await settledPreview(page, previews);
        const body = previews.bodies().at(-1);
        const preview = await apiPreview(api, body);
        expect(preview.count, '7-day preview: the seed-like rule fires').toBeGreaterThanOrEqual(1);
        await expect(page.getByTestId('alert-rule-preview-count')).toHaveAttribute(
          'data-value',
          String(preview.count),
        );
        await expect(page.getByTestId('alert-rule-preview-chart')).toBeVisible();
        await evidence(page, testInfo, 'rule-editor.png');
      } finally {
        previews.stop();
      }
      const saved = await capture<{ data: ApiRule }>(page, 'POST', '/alerts/rules', () =>
        page.getByTestId('alert-rule-save').click(),
      );
      ruleId = saved.body.data.rule_id;
      const id = ruleId;
      await expectUrl(page, (s) => s.path === `/alerts/rules/${id}`, 'the saved rule opens');
      const detail = await apiRuleDetail(api, id);
      expect(detail.rule).toMatchObject({
        name: ruleName,
        metric: 'error_rate',
        template_key: 'error_rate',
        window: '1h',
        for: '0s',
        min_samples: 1,
        enabled: true,
      });
      expect(detail.rule.target.app_id).toBe(alert.app_id);
      expect(detail.rule.condition.warn).toBe(0);
      // The rule records the template version it was made from (F-S24); the server's template_diff
      // lists changes only once the template moves past that version (T-SV-033), so a rule saved
      // from the current template has none and the editor keeps marking the fields it compares
      // with the template part itself.
      const template = (await apiTemplates(api)).find((t) => t.key === 'error_rate');
      expect(template, 'the error_rate template').toBeDefined();
      expect(detail.rule.template_version).toBe(template?.version);
      expect(detail.template_diff, 'no template_diff at the current template version').toBeNull();
      await expect
        .poll(() =>
          page
            .getByTestId('alert-rule-diff-dot')
            .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-field'))),
        )
        .toEqual(expect.arrayContaining(['window', 'for', 'min_samples']));
      await page.goto(`/alerts/rules?app=${String(alert.app_id)}`);
      await expectShell(page);
      const row = tableRowsOf(alertsTable(page, 'alert-rules')).and(
        page.locator(`[data-row-key="${id}"]`),
      );
      await expect(row).toBeVisible();
      await expect(row.getByTestId('alert-rule-name')).toHaveText(ruleName);
      await expect(row.getByTestId('alert-rule-enabled')).toHaveAttribute('data-enabled', 'true');
      await evidence(page, testInfo, 'rules.png');
    },
  );

  test(
    'T-E2E-alerts-5 the saved rule fires: pinned and listed FIRING as the API answers, acknowledged with a note -> ACKED with user and note, the badge goes down (evidence history.png)',
    { annotation: req('05-2.9-1', '05-2.9-2', '05-2.9-4', 'F-S26', 'T-ACPT-15') },
    async ({ page, seed, apiAs }, testInfo) => {
      test.setTimeout(240_000);
      if (ruleId === null) {
        throw new Error('T-E2E-alerts-4 saved no rule');
      }
      const id = ruleId;
      const alert = manifestAlert(seed);
      const api = await apiAs('admin');
      const firing = await waitForRuleEvent(api, alert.app_id, id, 'FIRING', 150_000);
      expect(firing.reason, 'reason of the FIRING transition (alert_events.reason)').toBe(
        'condition',
      );
      const asked = await capture<{ data: ApiAlertEvent[] }>(page, 'GET', '/alerts/events', () =>
        page.goto(ruleHistoryPath(id, firing.ts)),
      );
      await expectHistorySettled(page);
      expect(asked.params.get('rule')).toBe(id);
      await expectRowsEqualAnswer(page, api, asked.params, asked.body.data);
      const row = eventRow(page, firing.event_id);
      await expect(row.getByTestId('alerts-to-state')).toHaveAttribute('data-state', 'FIRING');
      await expect(row.getByTestId('alerts-reason')).toHaveAttribute('data-reason', 'condition');
      await expect(row.getByTestId('alert-value')).toHaveAttribute(
        'data-unit',
        firing.rule.unit ?? 'none',
      );
      let active: ApiAlertEvent | undefined;
      await expect
        .poll(
          async () => {
            active = await activeOfRule(api, id);
            return active === undefined
              ? false
              : (await pinnedRow(page, active.event_id).count()) === 1;
          },
          { message: 'the pinned panel shows the active alert of the rule', timeout: 30_000 },
        )
        .toBe(true);
      await expect(pinnedRow(page, active?.event_id ?? '')).toHaveAttribute('data-state', 'FIRING');
      await evidence(page, testInfo, 'history.png');
      // The badge counts FIRING alerts (GET /overview/alerts?state=FIRING).
      const firingCount = async (): Promise<number> =>
        (await apiActiveAlerts(api, { state: 'FIRING' })).length;
      const badge = page.getByTestId('alerts-badge');
      await expect
        .poll(
          async () => Number(await badge.getAttribute('data-count')) === (await firingCount()),
          {
            timeout: 40_000,
          },
        )
        .toBe(true);
      const before = Number(await badge.getAttribute('data-count'));
      expect(before).toBeGreaterThanOrEqual(1);
      // Acknowledge with a note.
      await row.getByTestId('alerts-action-ack').click();
      const modal = page.getByTestId('alerts-ack-modal');
      await expect(modal).toBeVisible();
      await modal.getByTestId('alerts-ack-note').fill(ackNote);
      const acked = await capture<{ data: { state: string; acked_by: string; note: string } }>(
        page,
        'POST',
        `/alerts/events/${firing.event_id}/ack`,
        () => modal.getByTestId('alerts-ack-submit').click(),
      );
      expect(acked.body.data).toMatchObject({
        state: 'ACKED',
        acked_by: seed.users.operator,
        note: ackNote,
      });
      await expect(modal).toBeHidden();
      await expect.poll(async () => (await activeOfRule(api, id))?.state).toBe('ACKED');
      const ackedItem = await activeOfRule(api, id);
      expect(ackedItem?.acked_by).toBe(seed.users.operator);
      expect(ackedItem?.note).toBe(ackNote);
      await expect(pinnedRow(page, ackedItem?.event_id ?? '')).toHaveAttribute(
        'data-state',
        'ACKED',
        {
          timeout: 15_000,
        },
      );
      // The ACKED transition is a row with the user and the note.
      const ackedEvent = await waitForRuleEvent(api, alert.app_id, id, 'ACKED', 30_000);
      expect(ackedEvent.reason).toBe('acked');
      await expect(eventRow(page, ackedEvent.event_id)).toBeVisible({ timeout: 15_000 });
      await expect(
        eventRow(page, ackedEvent.event_id).getByTestId('alerts-reason'),
      ).toHaveAttribute('data-reason', 'acked');
      // The ACK user and note of every row = the answer of the same question (the user and the
      // note of this acknowledgement among them).
      const after = (await apiEventsPage(api, queryOf(asked.params))).data;
      const withAck = after.filter((e) => e.acked_by !== null);
      expect(
        withAck.some((e) => e.acked_by === seed.users.operator && e.note === ackNote),
        'GET /alerts/events: the acknowledgement with its user and note',
      ).toBe(true);
      for (const e of withAck) {
        const cells = eventRow(page, e.event_id);
        await expect(cells.getByTestId('alerts-acked-by')).toHaveText(e.acked_by ?? '');
        if (e.note !== null) {
          await expect(cells.getByTestId('alerts-ack-note-text')).toHaveText(e.note);
        }
      }
      // The badge follows: the rule's alert no longer counts as FIRING.
      await expect
        .poll(
          async () => Number(await badge.getAttribute('data-count')) === (await firingCount()),
          {
            timeout: 40_000,
          },
        )
        .toBe(true);
      expect(
        (await apiActiveAlerts(api, { state: 'FIRING' })).some((a) => a.rule.id === id),
        'the acknowledged alert is not FIRING',
      ).toBe(false);
      testInfo.annotations.push({
        type: 'badge',
        description: `${String(before)} -> ${String(await badge.getAttribute('data-count'))}`,
      });
    },
  );

  test(
    'T-E2E-alerts-6 "mute this target" from the row: GET /alerts/mutes lists it, removed from the list again',
    { annotation: req('05-2.9-4') },
    async ({ page, seed, apiAs }) => {
      if (ruleId === null) {
        throw new Error('T-E2E-alerts-4 saved no rule');
      }
      const id = ruleId;
      const alert = manifestAlert(seed);
      const api = await apiAs('operator');
      const acked = await waitForRuleEvent(api, alert.app_id, id, 'ACKED', 30_000);
      await page.goto(ruleHistoryPath(id, acked.ts));
      await expectHistorySettled(page);
      const row = eventRow(page, acked.event_id);
      await row.getByTestId('alerts-action-mute').click();
      const modal = page.getByTestId('alerts-mute-modal');
      await expect(modal).toBeVisible();
      await expect(modal.getByTestId('alerts-mute-target-rule')).toHaveAttribute('data-value', id);
      await expect(modal.getByTestId('alerts-mute-target-app')).toHaveAttribute(
        'data-value',
        String(alert.app_id),
      );
      await modal.getByTestId('alerts-mute-reason').fill(`E2E mute ${tag}`);
      const created = await capture<{ data: { mute_id: string } }>(
        page,
        'POST',
        '/alerts/mutes',
        () => modal.getByTestId('alerts-mute-submit').click(),
      );
      const muteId = created.body.data.mute_id;
      mutes.push(muteId);
      await expect(modal).toBeHidden();
      const listed = (await apiMutes(api)).find((m) => m.mute_id === muteId);
      expect(listed?.target).toMatchObject({ app: alert.app_id, rule: id });
      expect(listed?.reason).toBe(`E2E mute ${tag}`);
      expect(Date.parse(listed?.to ?? '') - Date.parse(listed?.from ?? '')).toBeLessThanOrEqual(
        30 * 24 * 60 * MINUTE,
      );
      const muteRow = page.locator(`[data-testid="alerts-mute-row"][data-mute-id="${muteId}"]`);
      await expect(muteRow).toBeVisible({ timeout: 15_000 });
      await muteRow.getByTestId('alerts-mute-delete').click();
      await expect(muteRow).toBeHidden({ timeout: 15_000 });
      await expect
        .poll(async () => (await apiMutes(api)).some((m) => m.mute_id === muteId))
        .toBe(false);
    },
  );

  test(
    'T-E2E-alerts-9 the overview row of an acknowledged alert opens the acknowledged history with that alert (ADR-0010 C-226 (8))',
    { annotation: req('05-2.10-4', '05-2.9-6') },
    async ({ page, apiAs }) => {
      if (ruleId === null) {
        throw new Error('T-E2E-alerts-4 saved no rule');
      }
      const id = ruleId;
      const api = await apiAs('operator');
      const item = await activeOfRule(api, id);
      expect(item?.state, 'the rule alert is acknowledged (T-E2E-alerts-5)').toBe('ACKED');
      const eventId = item?.event_id ?? '';
      await page.goto('/overview');
      await expectShell(page);
      const row = page.locator(`[data-testid="overview-firing-row"][data-alert-id="${eventId}"]`);
      await expect(row).toBeVisible({ timeout: 30_000 });
      const link = row.getByTestId('alert-summary-row');
      const href = new URL((await link.getAttribute('href')) ?? '', 'http://x');
      expect(href.pathname).toBe('/alerts');
      expect(href.searchParams.get('state')).toBe('acked');
      expect(href.searchParams.get('alert')).toBe(eventId);
      await link.click();
      await expectUrl(
        page,
        (s) =>
          s.path === '/alerts' &&
          s.params.get('state') === 'acked' &&
          s.params.get('alert') === eventId,
        'the acknowledged alerts with the alert',
      );
      await expectHistorySettled(page);
      await expect(pinnedRow(page, eventId)).toHaveAttribute('data-highlighted', 'true');
    },
  );
});

// ------------------------------------------------------------------------------ channels

test.describe('channels', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-alerts-7 channels: webhook destination validation (422 for 10.0.0.1 and 169.254.169.254), the URL only masked, test sends to the fake receivers with the status shown, e-mail o***@domain, Slack, signing key rotation shown once, operator read-only (evidence channels.png)',
    { annotation: req('05-2.4-5', 'F-S06', 'F-S27', 'SEC-51', 'SEC-52', 'SEC-53', 'SEC-54') },
    async ({ page, seed, apiAs }, testInfo) => {
      test.setTimeout(180_000);
      const api = await apiAs('admin');
      const tag = runTag();
      const plan = receiverPlan(readReceiverConfig(seed.run_id), process.env, process.platform);
      const webhookToken = randomBytes(16).toString('hex');
      const slackToken = randomBytes(16).toString('hex');
      const created: string[] = [];
      const responses = recordResponses(page);
      let http: HttpReceiver | null = null;
      let smtp: SmtpReceiver | null = null;
      try {
        if (plan.webhookHost !== null) {
          http = await startHttpReceiver();
        } else {
          testInfo.annotations.push({
            type: 'webhook-receiver-skip',
            description: plan.webhookSkip ?? '',
          });
        }
        if (plan.smtpPort !== null) {
          try {
            smtp = await startSmtpReceiver({ port: plan.smtpPort });
          } catch (error) {
            testInfo.annotations.push({
              type: 'smtp-receiver-skip',
              description: `port ${String(plan.smtpPort)} could not be bound: ${(error as Error).message}`,
            });
          }
        } else {
          testInfo.annotations.push({
            type: 'smtp-receiver-skip',
            description: plan.smtpSkip ?? '',
          });
        }
        const host = plan.webhookHost ?? '127.0.0.1';
        const hostPart = host.includes(':') ? `[${host}]` : host;
        const port = http?.port ?? 9;
        await page.goto('/alerts/channels');
        await expectShell(page);
        await expect(page.getByTestId('alert-channels')).toHaveAttribute('data-editable', 'true');
        // Blocked destinations: the 422 reason, nothing saved (always runs, no receiver needed).
        for (const url of ['http://10.0.0.1/hook', 'http://169.254.169.254/latest/meta-data/']) {
          const editor = await fillNewChannel(page, {
            type: 'webhook',
            name: `E2E blocked ${tag}`,
            target: url,
            allowHttp: true,
          });
          await editor.getByTestId('alert-channel-save').click();
          const error = editor.getByTestId('alert-channel-destination-error');
          await expect(error).toBeVisible();
          const replay = await api.request('POST', '/alerts/channels', {
            body: { type: 'webhook', name: `E2E blocked ${tag}`, target: url, allow_http: true },
            expect: [422],
          });
          const reason = errorDetails(replay.body).find((d) => d.field === 'target')?.reason ?? '';
          expect(['private', 'link_local']).toContain(reason);
          await expect(error).toHaveAttribute('data-code', reason);
          await page.keyboard.press('Escape');
          await expect(editor).toBeHidden();
        }
        // Webhook: saved, the signing key shown once, the URL only masked.
        const webhookUrl = `http://${hostPart}:${String(port)}/hook/${webhookToken}`;
        let editor = await fillNewChannel(page, {
          type: 'webhook',
          name: `E2E webhook ${tag}`,
          target: webhookUrl,
          allowHttp: true,
        });
        const webhook = await capture<{
          data: { channel: ApiChannel; signing_key: string | null };
        }>(page, 'POST', '/alerts/channels', () =>
          editor.getByTestId('alert-channel-save').click(),
        );
        created.push(webhook.body.data.channel.channel_id);
        const once = page.getByTestId('alert-channel-secret-once');
        await expect(once, 'the generated signing key is shown once').toBeVisible();
        await once.getByTestId('alert-channel-secret-close').click();
        await expect(once).toBeHidden();
        const webhookId = webhook.body.data.channel.channel_id;
        const channelRow = (channelId: string): Locator =>
          tableRowsOf(alertsTable(page, 'alert-channels')).and(
            page.locator(`[data-row-key="${channelId}"]`),
          );
        const masked = (await apiChannels(api)).find(
          (c) => c.channel_id === webhookId,
        )?.target_masked;
        expect(masked).toBe(`http://${hostPart.toLowerCase()}:${String(port)}/****`);
        await expect(channelRow(webhookId).getByTestId('alert-channel-target-masked')).toHaveText(
          masked ?? '',
        );
        // Test send: the server's answer shown; the receiver got a signed post.
        const sendTest = async (
          channelId: string,
        ): Promise<{ delivered: boolean; status_code: number | null; error: string | null }> => {
          const answer = await capture<{
            data: { delivered: boolean; status_code: number | null; error: string | null };
          }>(page, 'POST', `/alerts/channels/${channelId}/test`, () =>
            channelRow(channelId).getByTestId('alert-channel-test').click(),
          );
          const result = page.getByTestId('alert-channel-test-result');
          await expect(result).toHaveAttribute('data-channel-id', channelId);
          await expect(result).toHaveAttribute(
            'data-delivered',
            String(answer.body.data.delivered),
          );
          if (answer.body.data.status_code !== null) {
            await expect(result).toHaveAttribute(
              'data-status-code',
              String(answer.body.data.status_code),
            );
            await expect(result).toContainText(String(answer.body.data.status_code));
          }
          return answer.body.data;
        };
        const webhookTest = await sendTest(webhookId);
        if (http !== null) {
          expect(webhookTest, 'webhook test send to the fake receiver').toMatchObject({
            delivered: true,
            status_code: 200,
          });
          const received = await http.waitForRequest(
            (r) => r.path === `/hook/${webhookToken}`,
            10_000,
          );
          expect(received.method).toBe('POST');
          expect(received.contentType).toContain('application/json');
          expect(received.signature).toMatchObject({ present: true, wellFormed: true, v1Count: 1 });
        }
        // Key rotation: confirmed, the new key shown once, then the previous key signs too.
        await channelRow(webhookId).getByTestId('alert-channel-rotate-key').click();
        await expect(page.getByTestId('alert-channel-confirm')).toBeVisible();
        const rotated = await capture<{ data: { signing_key: string } }>(
          page,
          'POST',
          `/alerts/channels/${webhookId}/signing-key/rotate`,
          () => page.getByTestId('alert-channel-confirm-ok').click(),
        );
        await expect(once).toBeVisible();
        await expect(once.getByTestId('alert-channel-secret-value')).toHaveValue(
          rotated.body.data.signing_key,
        );
        await once.getByTestId('alert-channel-secret-close').click();
        await expect(once).toBeHidden();
        expect(
          (await page.content()).includes(rotated.body.data.signing_key),
          'key gone from the DOM',
        ).toBe(false);
        if (http !== null) {
          await sendTest(webhookId);
          const second = await http.waitForRequest(
            (r) => r.path === `/hook/${webhookToken}` && r.signature.v1Count === 2,
            10_000,
          );
          expect(second.signature.wellFormed, 'old and new key both sign during the rotation').toBe(
            true,
          );
        }
        // E-mail: recipients masked o***@domain; test send to the SMTP receiver.
        const recipient = `ops-${tag}@example.test`;
        editor = await fillNewChannel(page, {
          type: 'email',
          name: `E2E mail ${tag}`,
          target: recipient,
        });
        const mail = await capture<{ data: { channel: ApiChannel } }>(
          page,
          'POST',
          '/alerts/channels',
          () => editor.getByTestId('alert-channel-save').click(),
        );
        const mailId = mail.body.data.channel.channel_id;
        created.push(mailId);
        await expect(editor).toBeHidden();
        expect(mail.body.data.channel.target_masked).toBe('o***@example.test');
        await expect(channelRow(mailId).getByTestId('alert-channel-target-masked')).toHaveText(
          'o***@example.test',
        );
        const mailTest = await sendTest(mailId);
        if (smtp !== null) {
          expect(mailTest).toMatchObject({ delivered: true, status_code: 250 });
          const message = await smtp.waitForMessage(
            (m) => m.recipients.includes(recipient),
            15_000,
          );
          expect(message.from).toBe(readReceiverConfig(seed.run_id)?.smtp_from);
        }
        // Slack: masked, test send without a signature.
        const slackUrl = `http://${hostPart}:${String(port)}/slack/${slackToken}`;
        editor = await fillNewChannel(page, {
          type: 'slack',
          name: `E2E slack ${tag}`,
          target: slackUrl,
          allowHttp: true,
        });
        const slack = await capture<{ data: { channel: ApiChannel } }>(
          page,
          'POST',
          '/alerts/channels',
          () => editor.getByTestId('alert-channel-save').click(),
        );
        const slackId = slack.body.data.channel.channel_id;
        created.push(slackId);
        await expect(editor).toBeHidden();
        await expect(channelRow(slackId).getByTestId('alert-channel-target-masked')).toHaveText(
          `http://${hostPart.toLowerCase()}:${String(port)}/****`,
        );
        const slackTest = await sendTest(slackId);
        if (http !== null) {
          expect(slackTest).toMatchObject({ delivered: true, status_code: 200 });
          const received = await http.waitForRequest(
            (r) => r.path === `/slack/${slackToken}`,
            10_000,
          );
          expect(received.signature.present, 'Slack posts carry no Argos signature').toBe(false);
          expect(JSON.parse(received.body)).toHaveProperty('text');
        }
        await evidence(page, testInfo, 'channels.png');
        // The secrets never came back from the server and are not on the page (SEC-52).
        const content = await page.content();
        for (const secret of [webhookToken, slackToken]) {
          expect(content.includes(secret), 'secret path in the DOM').toBe(false);
        }
        const texts = await responses.texts();
        expect(
          texts.filter((text) => text.includes(webhookToken) || text.includes(slackToken)),
          'secret paths in API responses',
        ).toEqual([]);
        const storage = await page.evaluate(() =>
          JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]),
        );
        expect(storage.includes(webhookToken) || storage.includes(slackToken)).toBe(false);
        // The operator sees the channels read-only.
        const operator = await apiAs('operator');
        await page.context().clearCookies();
        await page.context().addCookies(storageStateOf(operator).cookies);
        await page.goto('/alerts/channels');
        await expectShell(page);
        const root = page.getByTestId('alert-channels');
        await expect(root).toHaveAttribute('data-editable', 'false');
        await expect(page.getByTestId('alert-channels-read-only')).toBeVisible();
        await expect(page.getByTestId('alert-channel-new')).toHaveCount(0);
        await expect(channelRow(webhookId)).toBeVisible();
        for (const control of [
          'alert-channel-edit',
          'alert-channel-delete',
          'alert-channel-rotate-key',
        ]) {
          await expect(root.getByTestId(control), `operator: no ${control}`).toHaveCount(0);
        }
      } finally {
        responses.stop();
        for (const id of created) {
          await deleteChannelQuietly(api, id);
        }
        await http?.close();
        await smtp?.close();
      }
    },
  );
});

// ------------------------------------------------------------------- history checklist

test(
  'T-E2E-alerts-8 history: the 10 columns, filters as server parameters with the answer of the same question, statistics of 7 and 30 days, every application from the overview',
  { annotation: req('05-2.9-2', '05-2.9-3', '05-2.9-5', '05-2.9-6') },
  async ({ page, seed, apiAs }, testInfo) => {
    test.setTimeout(180_000);
    const ctx: AlertsCheckContext = { page, seed, testInfo, apiAs };
    await alertsCheck('alertsEventColumns')(ctx);
    await alertsCheck('alertsFilters')(ctx);
    await page.goto('/alerts');
    await alertsCheck('alertsStats')(ctx);
    await page.goto('/overview');
    await alertsCheck('alertsAllAppsFromOverview')(ctx);
    expect(ALERT_COLUMNS).toHaveLength(10);
  },
);

// ---------------------------------------------------------- shared alert row (C-227 (4))

test(
  'T-E2E-alerts-10 at 1366 the overview shows the seed rule name untruncated and the dashboard shows value / threshold with the unit of the rule (ADR-0010 C-227 (4))',
  { annotation: req('05-2.10-4', '05-2.3-5', '05-2.9-2') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs('admin');
    const alert = manifestAlert(seed);
    const active = await activeSeedAlert(api, alert);
    test.skip(active === undefined, SEED_RESOLVED);
    expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
    await page.goto('/overview');
    await expectShell(page);
    const row = page.locator(
      `[data-testid="overview-firing-row"][data-alert-id="${alert.event_id}"]`,
    );
    await expect(row).toBeVisible({ timeout: 30_000 });
    const rule = row.getByTestId('alert-row-rule');
    await expect(rule).toHaveText(alert.rule_name);
    const fit = await rule.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
    testInfo.annotations.push({ type: 'rule-name-width', description: JSON.stringify(fit) });
    expect(fit.scroll, 'the rule name is not cut (scrollWidth <= clientWidth)').toBeLessThanOrEqual(
      fit.client + 1,
    );
    // Dashboard: value / threshold with the unit of rule.unit = the dashboard answer.
    const to = ceilMinute(Date.now());
    const from = to - 60 * MINUTE;
    await page.goto(
      `/apps/${String(alert.app_id)}/dashboard?from=${isoSeconds(from)}&to=${isoSeconds(to)}`,
    );
    await expectShell(page);
    const data = await apiDashboard(api, alert.app_id, {
      from: isoSeconds(from),
      to: isoSeconds(to),
    });
    const item = ((data.firing ?? []) as ApiAlertEvent[]).find(
      (f) => f.event_id === alert.event_id,
    );
    expect(item, 'the dashboard answer lists the seed alert').toBeDefined();
    const value = page
      .locator(`[data-testid="dashboard-firing-row"][data-alert-id="${alert.event_id}"]`)
      .getByTestId('alert-value');
    await expect(value).toHaveAttribute('data-unit', item?.rule.unit ?? 'none');
    expect(item?.rule.unit, 'error rate is a ratio').toBe('ratio');
    expect(squashText(await value.textContent())).toBe(
      squashText(
        alertValueText(item?.value ?? null, item?.threshold ?? null, item?.rule.unit ?? null),
      ),
    );
    expect(await value.textContent()).toContain('%');
  },
);

// -------------------------------------------------------------- deep link back (T-ACPT-15)

test.describe('deep links', () => {
  test.use({ role: null });

  test(
    'T-E2E-alerts-11 an anonymous alert link returns to it after the login; the history deep link opens the dashboard at fired ± 15 min, absolute, not live, with the alert banner (T-ACPT-15)',
    { annotation: req('05-2.0-7', '05-2.9-4', 'F-U14', 'SEC-42', 'T-ACPT-15') },
    async ({ page, seed, apiAs }) => {
      const alert = manifestAlert(seed);
      const api = await apiAs('admin');
      await page.goto(alertsPath({ state: 'firing', alert: alert.event_id }));
      await expect(page.getByTestId('login-form')).toBeVisible();
      await expect(page).toHaveURL(/\/login\?/);
      await signIn(page, seed, 'viewer');
      await expectShell(page);
      await expectUrl(
        page,
        (s) =>
          s.path === '/alerts' &&
          s.params.get('alert') === alert.event_id &&
          s.params.get('state') === 'firing',
        'back on the alert link after the login',
      );
      await expectHistorySettled(page);
      if ((await activeSeedAlert(api, alert))?.state === 'FIRING') {
        await expect(pinnedRow(page, alert.event_id)).toHaveAttribute('data-highlighted', 'true');
      }
      // The history row's deep link = the server's deeplink: dashboard, fired ± 15 min, alert=.
      await page.goto(
        alertsPath({ ...alertsWindow(alert), rule: alert.rule_id, alert: alert.event_id }),
      );
      await expectHistorySettled(page);
      const event = (
        await apiEventsPage(api, { ...alertsWindow(alert), rule: alert.rule_id })
      ).data.find((e) => e.event_id === alert.event_id);
      expect(event, 'the seed event in GET /alerts/events').toBeDefined();
      const link = eventRow(page, alert.event_id).getByTestId('alerts-action-deeplink');
      expect(linkKey((await link.getAttribute('href')) ?? '')).toBe(linkKey(event?.deeplink ?? ''));
      await link.click();
      const s = await expectUrl(
        page,
        (x) => x.path === `/apps/${String(alert.app_id)}/dashboard`,
        'the deep link opens the dashboard',
      );
      const fired = Date.parse(event?.ts ?? alert.fired_at);
      const { fromMs, toMs } = absolute(s);
      const firedSecond = Math.floor(fired / 1000) * 1000;
      expect(fromMs).toBe(firedSecond - 15 * MINUTE);
      expect(toMs).toBe(firedSecond + 15 * MINUTE);
      expect(s.live).toBe(false);
      expect(s.params.get('alert')).toBe(alert.event_id);
      const banner = page.getByTestId('alert-banner');
      await expect(banner).toBeVisible();
      await expect(banner.getByTestId('alert-banner-rule')).toContainText(alert.rule_name);
      expect(state(page).params.get('live')).toBeNull();
    },
  );
});

// --------------------------------------------------------------------------- realtime

test.describe('realtime with the stack /ws', () => {
  test.use({ wsMode: 'server', role: 'operator' });

  test(
    'T-E2E-alerts-12 a live history refreshes the pinned panel on the server alert push of a new firing',
    { annotation: req('05-2.9-1', 'F-U16') },
    async ({ page, seed, apiAs, ws }, testInfo) => {
      test.setTimeout(240_000);
      const alert = manifestAlert(seed);
      const api = await apiAs('operator');
      await page.goto(alertsPath({ from: 'now-1h', live: 1 }));
      await expectHistorySettled(page);
      await ws.waitForSubscription('alert', 15_000);
      const created = Date.now();
      const ruleId = await createOwnRule(api, alert, `E2E push ${runTag()}`);
      try {
        const firing = await waitForRuleEvent(api, alert.app_id, ruleId, 'FIRING', 150_000);
        const push = await ws.waitForServerFrame(
          (f) =>
            f.at >= created &&
            f.text.includes('"alert"') &&
            (f.text.includes(firing.event_id) || f.text.includes(ruleId)),
          30_000,
          'the alert push of the new firing',
        );
        let active: ApiAlertEvent | undefined;
        await expect
          .poll(
            async () => {
              active = await activeOfRule(api, ruleId);
              return active === undefined ? 0 : pinnedRow(page, active.event_id).count();
            },
            { message: 'the pinned panel shows the new firing after the push', timeout: 8_000 },
          )
          .toBe(1);
        testInfo.annotations.push({
          type: 'push-to-row-ms',
          description: String(Date.now() - push.at),
        });
      } finally {
        await deleteRuleQuietly(api, ruleId);
      }
    },
  );
});

test(
  'T-E2E-alerts-12 a live history marks a silent connection stale and recovers after the reconnect (stub /ws)',
  { annotation: req('05-2.9-1', 'F-U16', 'SEC-44') },
  async ({ page, ws }) => {
    test.setTimeout(90_000);
    let pinnedCalls = 0;
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/v1/overview/alerts') {
        pinnedCalls += 1;
      }
    });
    await page.goto(alertsPath({ from: 'now-1h', live: 1 }));
    await expectHistorySettled(page);
    await ws.waitForSubscription('alert', 15_000);
    const pauseMs = 15_000;
    const pausedAt = Date.now();
    ws.pause(pauseMs);
    await expect(page.getByTestId('ws-stale-badge')).toBeVisible({ timeout: 20_000 });
    // The paused stub ignores every frame, also the subscriptions of a new connection: let the
    // pause run out before the drop so the reconnect's resubscription is answered.
    const rest = pauseMs - (Date.now() - pausedAt);
    if (rest > 0) {
      await new Promise((resolve) => setTimeout(resolve, rest + 200));
    }
    ws.drop();
    await ws.waitForConnections(2, 20_000);
    await ws.waitForSubscription('alert', 15_000);
    const before = pinnedCalls;
    expect(ws.push('alert', { event: { event_id: 'e2e-stub' } })).toBeGreaterThan(0);
    await expect
      .poll(() => pinnedCalls, { message: 'the push refreshes the pinned panel' })
      .toBeGreaterThan(before);
    await expect(page.getByTestId('ws-stale-badge')).toBeHidden({ timeout: 10_000 });
  },
);

// ------------------------------------------------------------------------------ visual

/** Fixed alert data of the ready screens (the baselines must not follow the clock). */
function visualData(seed: SeedManifest): {
  events: ApiAlertEvent[];
  active: ApiAlertEvent[];
  channels: ApiChannel[];
} {
  const app = seed.apps.find((a) => a.app_id === seed.primary_app_id);
  const base = {
    app: { id: seed.primary_app_id, name: app?.name ?? 'app' },
    env: app?.env ?? 'prod',
    deliveries: [],
    acked_by: null,
    acked_at: null,
    note: null,
    deeplink: `/apps/${String(seed.primary_app_id)}/dashboard?from=2030-01-01T09:45:00Z&to=2030-01-01T10:15:00Z&alert=00000000-0000-4000-8000-000000000001`,
  };
  const rule = {
    id: '00000000-0000-4000-8000-0000000000aa',
    name: 'Error rate',
    metric: 'error_rate',
    unit: 'ratio',
  };
  const latency = {
    id: '00000000-0000-4000-8000-0000000000bb',
    name: 'Response time p95',
    metric: 'response_time_p95',
    unit: 'ms',
  };
  const events: ApiAlertEvent[] = [
    {
      ...base,
      event_id: '00000000-0000-4000-8000-000000000003',
      ts: '2030-01-01T10:20:00Z',
      state: 'ACKED',
      from_state: 'FIRING',
      reason: 'acked',
      severity: 'warn',
      rule,
      target: { type: 'app', id: null, name: app?.name ?? 'app' },
      value: 0.071,
      threshold: 0.05,
      duration_s: 300,
      acked_by: 'operator',
      acked_at: '2030-01-01T10:20:00Z',
      note: 'Looking into the payment errors',
    },
    {
      ...base,
      event_id: '00000000-0000-4000-8000-000000000002',
      ts: '2030-01-01T10:15:00Z',
      state: 'FIRING',
      from_state: 'PENDING',
      reason: 'condition',
      severity: 'critical',
      rule: latency,
      target: { type: 'instance', id: '12', name: 'order-api-1' },
      value: 2350,
      threshold: 2000,
      duration_s: 0,
      deliveries: [
        {
          channel_id: '00000000-0000-4000-8000-0000000000c1',
          status: 'failed',
          attempts: 3,
          last_error_code: 'timeout',
        },
      ],
    },
    {
      ...base,
      event_id: '00000000-0000-4000-8000-000000000001',
      ts: '2030-01-01T10:00:00Z',
      state: 'RESOLVED',
      from_state: 'FIRING',
      reason: null,
      severity: 'warn',
      rule,
      target: { type: 'app', id: null, name: app?.name ?? 'app' },
      value: 0.012,
      threshold: 0.05,
      duration_s: 900,
    },
  ];
  const channels: ApiChannel[] = [
    {
      channel_id: '00000000-0000-4000-8000-0000000000c1',
      type: 'webhook',
      name: 'Ops webhook',
      target_masked: 'https://hooks.example.test/****',
      enabled: true,
      status: 'failing',
      last_success_at: '2030-01-01T09:00:00Z',
      last_failure_at: '2030-01-01T10:15:05Z',
      consecutive_failures: 3,
    },
    {
      channel_id: '00000000-0000-4000-8000-0000000000c2',
      type: 'email',
      name: 'On-call mail',
      target_masked: 'o***@example.test',
      enabled: true,
      status: 'ok',
      last_success_at: '2030-01-01T10:20:01Z',
      last_failure_at: null,
      consecutive_failures: 0,
    },
  ];
  const channelExtra = {
    signing_enabled: true,
    allow_http: false,
    created_at: '2030-01-01T08:00:00Z',
    updated_at: '2030-01-01T08:00:00Z',
  };
  return {
    events,
    active: events.filter((e) => e.state === 'FIRING' || e.state === 'ACKED'),
    channels: channels.map((c) => ({ ...c, ...channelExtra })),
  };
}

async function fulfill(
  route: Route,
  data: unknown,
  meta: Record<string, unknown> = {},
): Promise<void> {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ data, meta }),
  });
}

/** Routes the alert APIs of the history and channel screens to fixed data. */
async function fixAlertData(
  page: Page,
  seed: SeedManifest,
  events?: ApiAlertEvent[],
): Promise<void> {
  const data = visualData(seed);
  await page.route('**/api/v1/alerts/events?**', (route) =>
    fulfill(route, events ?? data.events, { next_cursor: null }),
  );
  await page.route('**/api/v1/overview/alerts**', (route) => fulfill(route, data.active));
  await page.route('**/api/v1/alerts/channels', (route) =>
    route.request().method() === 'GET' ? fulfill(route, data.channels) : route.continue(),
  );
  await page.route('**/api/v1/alerts/mutes', (route) =>
    route.request().method() === 'GET' ? fulfill(route, []) : route.continue(),
  );
}

/** The fixed range of the visual history (the fixed events are inside it). */
const VISUAL_RANGE = { from: '2030-01-01T09:30:00Z', to: '2030-01-01T10:30:00Z' };

test.describe('visual', () => {
  test.use({ role: 'operator' });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: 'baselines are made in the pinned Linux image only (corepack pnpm e2e:docker)',
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /** Per-run values: the range label, elapsed times of the pinned rows and request ids. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      // "since <relative time>" of the pinned rows follows the clock.
      page.locator('.alerts-pinned__since'),
      page.locator('[data-testid="alerts-pinned"] time'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-alerts-20 visual: the alert history while its data loads',
    { annotation: req('05-2.0-8', 'F-U16') },
    async ({ page, seed }) => {
      // Pinned alerts and channels fixed; the events (registered last, so first) held.
      await fixAlertData(page, seed);
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/alerts/events?**', async (route) => {
        await held;
        await route.continue().catch(() => undefined);
      });
      await page.goto(alertsPath(VISUAL_RANGE));
      await expectShell(page);
      await expect(page.getByTestId('alerts-history')).toHaveAttribute('data-state', 'loading');
      const loading = page.getByTestId('alerts-history-loading');
      await expect(loading).toBeVisible();
      await expect(loading).toHaveAttribute('data-slow', 'true', { timeout: 10_000 });
      await expect(page).toHaveScreenshot('alerts-history-loading.png', { mask: masks(page) });
      release();
    },
  );

  test(
    'T-E2E-alerts-21 visual: an alert history range without events names the next action',
    { annotation: req('05-2.0-8', 'F-U16') },
    async ({ page, seed }) => {
      await fixAlertData(page, seed, []);
      await page.goto(alertsPath(VISUAL_RANGE));
      await expectHistorySettled(page);
      await expect(page.getByTestId('alerts-history-empty')).toBeVisible();
      await expect(page).toHaveScreenshot('alerts-history-empty.png', {
        mask: masks(page),
        fullPage: true,
      });
    },
  );

  test(
    'T-E2E-alerts-22 visual: a failed alert history answer shows the error with the request id and retry',
    { annotation: req('05-2.0-8', 'F-U16') },
    async ({ page, seed }) => {
      await fixAlertData(page, seed);
      // The docs/14 §2.6 error envelope of a 400 answer: a 4xx, which the stack page hooks annotate
      // (a 5xx "Failed to load resource" console line fails every test, e2e/fixtures/fixtures.ts).
      await page.route('**/api/v1/alerts/events?**', (route) =>
        route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'invalid_request',
              message: 'Invalid request.',
              request_id: 'e2e-visual-1',
            },
          }),
        }),
      );
      await page.goto(alertsPath(VISUAL_RANGE));
      await expectShell(page);
      await expect(page.getByTestId('alerts-history')).toHaveAttribute('data-state', 'error', {
        timeout: 30_000,
      });
      await expect(page.getByTestId('alerts-history-error')).toBeVisible();
      await expect(page).toHaveScreenshot('alerts-history-error.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-alerts-23 visual: the alert history with pinned alerts, channel status and the 10 columns',
    { annotation: req('05-2.9-1', '05-2.9-2', 'F-U16') },
    async ({ page, seed }) => {
      await fixAlertData(page, seed);
      await page.goto(alertsPath(VISUAL_RANGE));
      await expectHistorySettled(page);
      await expect(tableRowsOf(alertsTable(page, 'alerts-events'))).toHaveCount(3);
      await expect(page).toHaveScreenshot('alerts-history.png', {
        mask: masks(page),
        fullPage: true,
      });
    },
  );

  test(
    'T-E2E-alerts-24 visual: the rule editor from the error rate template with its preview',
    { annotation: req('05-2.4-1', '05-2.4-2', 'F-U16') },
    async ({ page, seed }) => {
      await fixAlertData(page, seed);
      const step = 300;
      const start = Date.parse('2030-01-01T00:00:00Z');
      await page.route('**/api/v1/alerts/rules/preview', (route) =>
        fulfill(route, {
          count: 2,
          max_duration_s: 1800,
          step_s: step,
          firings: [
            { ts_start: '2030-01-01T02:00:00Z', ts_end: '2030-01-01T02:30:00Z', peak_value: 0.09 },
            { ts_start: '2030-01-01T05:00:00Z', ts_end: '2030-01-01T05:15:00Z', peak_value: 0.07 },
          ],
          series: {
            ts: Array.from({ length: 96 }, (_, i) =>
              new Date(start + i * step * 1000).toISOString(),
            ),
            value: Array.from({ length: 96 }, (_, i) =>
              Number((0.02 + 0.01 * Math.sin(i / 6)).toFixed(4)),
            ),
          },
        }),
      );
      await page.goto(`/alerts/rules/new?app=${String(seed.primary_app_id)}&template=error_rate`);
      await expectShell(page);
      await expect(page.getByTestId('alert-rule-editor')).toHaveAttribute('data-state', 'ready');
      await expect(page.getByTestId('alert-rule-preview')).toHaveAttribute('data-state', 'ready', {
        timeout: 15_000,
      });
      await expect(page).toHaveScreenshot('alerts-rule-editor.png', {
        mask: masks(page),
        fullPage: true,
      });
    },
  );

  test(
    'T-E2E-alerts-25 visual: the notification channels with masked targets and their status',
    { annotation: req('05-2.4-5', 'F-U16') },
    async ({ page, seed }) => {
      await fixAlertData(page, seed);
      await page.goto('/alerts/channels');
      await expectShell(page);
      await expect(tableRowsOf(alertsTable(page, 'alert-channels'))).toHaveCount(2);
      await expect(page).toHaveScreenshot('alerts-channels.png', { mask: masks(page) });
    },
  );
});
