// SPDX-License-Identifier: Apache-2.0
//
// Metrics of the real agent on the dashboard (WO-16, docs/10 §11, T-AG-062 stack part): the seed's
// traffic application is shop-boot3 with the agent of the stack (e2e/stack/seed/traffic.ts), so
// its heap, process CPU, Tomcat thread and connection pool series come from the agent's own
// MetricBatch through the real collector and ClickHouse, not from the generator or a fake agent.
// GET /apps/{traffic}/dashboard over the traffic range has values in those series, and the four
// charts draw them (each chart's data-summary = the API series asked the same question).
import {
  apiDashboard,
  dashboardPath,
  openDashboard,
  ORACLE_ROLE,
  shownSummary,
  summaryOf,
  type ChartId,
} from '../fixtures/dashboardOracle';
import { expect, test } from '../fixtures/stack';

function req(...ids: string[]): { type: string; description: string }[] {
  return ids.map((description) => ({ type: 'req', description }));
}

test(
  'T-AG-062 (stack) the real agent fills the dashboard: heap_used, cpu, tomcat_busy_ratio and pool_usage_ratio of the traffic app are not null over the traffic range and their charts are not empty',
  { annotation: req('F-A09', 'F-A10', 'F-U01') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.traffic.app_id;
    const { start: from, end: to } = seed.traffic.range;
    const data = await apiDashboard(api, appId, { from, to });
    const s = data.series;
    const pools = Object.entries(s.pool_usage_ratio);
    const api4 = {
      heap_used: summaryOf(s.heap_used).n,
      cpu: summaryOf(s.cpu).n,
      tomcat_busy_ratio: summaryOf(s.tomcat_busy_ratio).n,
      pool_usage_ratio: pools.reduce((n, [, values]) => n + summaryOf(values).n, 0),
    };
    expect(
      Object.entries(api4).filter(([, n]) => n === 0),
      `series without a value in GET /apps/${String(appId)}/dashboard?from=${from}&to=${to} (buckets with a value: ${JSON.stringify(api4)})`,
    ).toEqual([]);

    await openDashboard(page, dashboardPath(appId, from, to));
    const charts: readonly (readonly [ChartId, string, readonly (number | null)[]])[] = [
      ['heap-gc', 'heap_used', s.heap_used],
      ['cpu', 'cpu', s.cpu],
      ['tomcat', 'tomcat_busy', s.tomcat_busy_ratio],
    ];
    for (const [id, key, values] of charts) {
      await expect
        .poll(async () => (await shownSummary(page, id))[key] ?? null, {
          message: `chart ${id}: drawn ${key} = the API`,
          timeout: 15_000,
        })
        .toEqual(summaryOf(values));
    }
    // The pool chart draws one line per pool; compare the buckets with a value as a multiset.
    await expect
      .poll(
        async () =>
          Object.values(await shownSummary(page, 'pool'))
            .map((v) => v.n)
            .sort((a, b) => a - b),
        { message: 'chart pool: buckets with a value per pool = the API', timeout: 15_000 },
      )
      .toEqual(pools.map(([, values]) => summaryOf(values).n).sort((a, b) => a - b));
    testInfo.annotations.push({
      type: 'agent-metrics',
      description: `traffic app ${String(appId)} ${from}..${to}: buckets with a value ${JSON.stringify(api4)}; pools ${pools.map(([name]) => name).join(', ')}`,
    });
  },
);
