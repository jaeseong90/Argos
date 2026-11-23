// SPDX-License-Identifier: Apache-2.0
//
// The instance detail and list in the whole app (docs/15 §9.8, 05 §2.7, WO-37): header = GET
// /instances/{id}, the metrics tab asks the discovered series in chunks of at most 50 and draws
// what the API answered (data-summary = the answer, unreported panels "Not measured"), the URL's
// application is replaced by the instance's own, an unknown instance names the next action, an
// error shows the request id with retry, the environment tab per role (viewer: notices and no
// effective-config request; operator: effective configuration merged with the latest self
// diagnosis; administrator: the environment dump panel), commands and dumps tabs need the operator
// role, and the instance list links to the detail. The commands and dumps parts (lane C) are
// replaced by stand-ins here: this file tests the instance screen around them.
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import type { MetricName } from '../../api/instances';
import { untrusted } from '../../api/untrusted';
import { findInjectedMarkup, xssFired, XSS_PAYLOADS } from '../../components/testing/xssPayloads';

import {
  configEntry,
  grid,
  instanceBody,
  metricName,
  metricsBody,
  RANGE_QUERY,
  FROM,
  series,
} from './testing/fixtures';

await vi.hoisted(async () => {
  (await import('../../charts/testing/uplotDom')).installUplotDom();
});

interface SelfDiagnosisStub {
  report: null | {
    effectiveConfig: { key: string; value: string; source: string | null }[];
    modules: {
      name: string;
      state: string | null;
      errors: number | null;
      reason: string | null;
    }[];
  };
  at: string | null;
  available: boolean;
  loading: boolean;
}

const selfDiagnosis = vi.hoisted(() => ({
  current: {
    report: null as SelfDiagnosisStub['report'],
    at: null as string | null,
    available: false,
    loading: false,
  },
  /** The `load` argument of every call (C-234 (3): false until the user asks). */
  loads: [] as boolean[],
}));

vi.mock('./commands', () => ({
  CommandsTab: (props: { instance: { instance_id: number } }) => (
    <div data-testid="stub-commands-tab" data-instance-id={props.instance.instance_id} />
  ),
  LatestResultPanel: (props: {
    type: string;
    instanceId: number;
    reveal?: { revealed: boolean; onReveal: () => void };
  }) => (
    <div
      data-testid={`stub-latest-${props.type}`}
      data-instance-id={props.instanceId}
      data-revealed={props.reveal === undefined ? undefined : String(props.reveal.revealed)}
    />
  ),
  // Like the real hook: no report (no audited result read) until `load` is true.
  useLatestSelfDiagnosis: (_instanceId: number, load: boolean) => {
    selfDiagnosis.loads.push(load);
    return load ? selfDiagnosis.current : { ...selfDiagnosis.current, report: null, at: null };
  },
}));

vi.mock('./dumps', () => ({
  DumpsTab: (props: { instance: { instance_id: number } }) => (
    <div data-testid="stub-dumps-tab" data-instance-id={props.instance.instance_id} />
  ),
}));

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U08' };
export const REQ_URL = { type: 'req', description: 'F-U14' };
export const REQ_MISSING = { type: 'req', description: 'D-23' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };
const INSTANCE = 'GET /api/v1/instances/7';
const NAMES = 'GET /api/v1/instances/7/metrics/names';
const METRICS = 'GET /api/v1/instances/7/metrics';
const CONFIG = 'GET /api/v1/instances/7/effective-config';

let stub: ApiStub;

const heapUsed = metricName('jvm_memory_used_bytes', { area: 'heap' });
const live = metricName('jvm_threads_live');
const dropped = metricName('argos_agent_dropped_total', { queue: 'tx' }, 'counter');
const moduleErrors = metricName('argos_agent_module_errors_total', { module: 'jdbc' }, 'counter');
/** 60 pool series: more than one metrics call takes (50). */
const pools = Array.from({ length: 60 }, (_, i) =>
  metricName('jvm_memory_pool_used_bytes', { pool: `pool-${String(i)}` }),
);
const TS = grid(FROM, 4, 15 * 60_000);

function valuesOf(name: string): (number | null)[] {
  if (name === heapUsed.name) return [100, null, 300, 400];
  if (name === dropped.name) return [0, 0, 0, 0];
  if (name === moduleErrors.name) return [1, 0, null, 2];
  return [1, 1, 1, 1];
}

/**
 * The metric names of most tests: two chart series and the agent's own two. Only the chunking test
 * adds the 60 pool series (WITH_POOLS): drawing 62 series in every test made the page tests slow
 * enough to pass the 5 s test limit under a full-suite load (C-235 (9)).
 */
const BASE_NAMES: readonly MetricName[] = [heapUsed, live, dropped, moduleErrors];
const WITH_POOLS: readonly MetricName[] = [...BASE_NAMES, ...pools];

function answer(names: readonly MetricName[] = BASE_NAMES) {
  stub.on(INSTANCE, () => envelope(instanceBody()));
  stub.on('GET /api/v1/apps/1/events', () => envelope([]));
  stub.on(NAMES, () => envelope(names));
  stub.on(METRICS, (request) => {
    const asked = request.query.getAll('names');
    const known = new Map(names.map((n) => [n.name as string, n] as const));
    return envelope(
      metricsBody(
        TS,
        asked.map((name) => series(known.get(name) ?? metricName(name), TS, valuesOf(name))),
      ),
      { source: 'raw' },
    );
  });
  stub.on(CONFIG, () =>
    envelope([
      configEntry('log.level'),
      configEntry('sampling.normal-rate', {
        server_value: untrusted('0.2'),
        server_status: 'applied',
        value: untrusted('0.2'),
        source: 'server',
      }),
    ]),
  );
}

async function open(path: string, roles: string[] = ['viewer']) {
  signedIn(stub, { roles });
  const app = renderApp(path);
  const page = await screen.findByTestId('instance-page', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
  selfDiagnosis.current = { report: null, at: null, available: false, loading: false };
  selfDiagnosis.loads = [];
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('instance detail (05 §2.7)', () => {
  it('shows the header of GET /instances/{id} and the metrics tab by default', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}`);
    expect(screen.getByTestId('instance-name')).toHaveTextContent('order-api-1');
    expect(screen.getByTestId('instance-meta-pid')).toHaveAttribute('data-value', '4242');
    expect(screen.getByTestId('instance-tab-metrics')).toHaveAttribute('aria-selected', 'true');
    const metrics = await screen.findByTestId('instance-metrics', {}, FIND);
    await waitFor(() => {
      expect(metrics).toHaveAttribute('data-state', 'ready');
    }, FIND);
  });

  it('asks the chart series in chunks of at most 50 and shows what the API answered', async () => {
    answer(WITH_POOLS);
    await open(`/apps/1/instances/7?${RANGE_QUERY}`);
    await waitFor(() => {
      expect(screen.getByTestId('instance-metrics')).toHaveAttribute('data-state', 'ready');
    }, FIND);
    const calls = stub.calls(METRICS);
    const chartCalls = calls.filter((c) => !c.query.getAll('names').includes(dropped.name));
    expect(chartCalls.length).toBeGreaterThanOrEqual(2);
    for (const call of calls) {
      expect(call.query.getAll('names').length).toBeLessThanOrEqual(50);
    }
    const asked = new Set(chartCalls.flatMap((c) => c.query.getAll('names')));
    expect(asked).toEqual(new Set([heapUsed.name, live.name, ...pools.map((p) => p.name)]));
    const heap = screen.getByTestId('instance-chart-heap-values');
    expect(JSON.parse(heap.getAttribute('data-summary') ?? '{}')).toEqual({
      [heapUsed.name]: { n: 3, sum: 800 },
    });
    const poolPanel = screen.getByTestId('instance-panel-memory-pools');
    expect(poolPanel).toHaveAttribute('data-series-count', '60');
    // Not reported: "Not measured", no chart.
    const fd = screen.getByTestId('instance-panel-fd');
    expect(fd).toHaveAttribute('data-measured', 'false');
    expect(within(fd).getByTestId('instance-chart-fd-note')).toHaveTextContent('Not measured');
    expect(within(fd).queryByTestId('instance-chart-fd')).toBeNull();
  });

  it('fetches the window 7 days earlier for the comparison baseline', async () => {
    answer([heapUsed, live]);
    const user = userEvent.setup();
    const rendered = await open(`/apps/1/instances/7?${RANGE_QUERY}`);
    await waitFor(() => {
      expect(screen.getByTestId('instance-metrics')).toHaveAttribute('data-state', 'ready');
    }, FIND);
    await user.click(screen.getByTestId('instance-baseline-toggle'));
    const week = 7 * 86_400_000;
    await waitFor(() => {
      expect(
        stub
          .calls(METRICS)
          .some((c) => c.query.get('from') === new Date(FROM - week).toISOString()),
      ).toBe(true);
    }, FIND);
    expect(screen.getByTestId('instance-baseline-toggle')).toHaveAttribute('aria-pressed', 'true');
    expect(rendered.router.state.location.search).toContain('baseline=1');
  });

  it('restores the comparison baseline from a deep link with baseline=1 (05 §2.0)', async () => {
    answer([heapUsed, live]);
    const user = userEvent.setup();
    const rendered = await open(`/apps/1/instances/7?${RANGE_QUERY}&baseline=1`);
    expect(screen.getByTestId('instance-baseline-toggle')).toHaveAttribute('aria-pressed', 'true');
    const week = 7 * 86_400_000;
    await waitFor(() => {
      expect(
        stub
          .calls(METRICS)
          .some((c) => c.query.get('from') === new Date(FROM - week).toISOString()),
      ).toBe(true);
    }, FIND);
    // The baseline series of the reported panels are drawn.
    await waitFor(() => {
      expect(screen.getByTestId('instance-panel-heap')).toHaveAttribute(
        'data-baseline-series',
        '1',
      );
    }, FIND);
    expect(screen.getByTestId('instance-panel-threads')).toHaveAttribute(
      'data-baseline-series',
      '1',
    );
    // Toggling off removes the key and the baseline series.
    await user.click(screen.getByTestId('instance-baseline-toggle'));
    await waitFor(() => {
      expect(rendered.router.state.location.search).not.toContain('baseline=');
      expect(screen.getByTestId('instance-panel-heap')).toHaveAttribute(
        'data-baseline-series',
        '0',
      );
    }, FIND);
    expect(screen.getByTestId('instance-baseline-toggle')).toHaveAttribute('aria-pressed', 'false');
  });

  it('says so when the instance reported no metrics, with the widen action', async () => {
    answer([]);
    await open(`/apps/1/instances/7?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('instance-metrics-empty', {}, FIND);
    expect(empty).toHaveTextContent('This instance reported no metrics.');
    expect(stub.calls(METRICS)).toHaveLength(0);
  });

  it('F-U14 replaces the URL application with the instance own, keeping the query', async () => {
    answer();
    const app = await open(`/apps/2/instances/7?${RANGE_QUERY}&tab=env`);
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/instances/7');
    }, FIND);
    expect(app.router.state.location.search).toContain('tab=env');
    expect(app.router.state.historyAction).toBe('REPLACE');
  });

  it('names the next action for an unknown instance', async () => {
    signedIn(stub);
    stub.on(INSTANCE, () => errorBody(404, 'not_found'));
    const user = userEvent.setup();
    const app = renderApp(`/apps/1/instances/7?${RANGE_QUERY}`);
    const empty = await screen.findByTestId('instance-not-found', {}, FIND);
    expect(empty).toHaveTextContent('This instance does not exist.');
    expect(screen.getByTestId('instance-page')).toHaveAttribute('data-state', 'empty');
    await user.click(screen.getByTestId('instance-not-found-list'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/instances');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${new Date(FROM).toISOString()}`);
  });

  it('shows a server error with its request id and retries', async () => {
    signedIn(stub);
    let fail = true;
    stub.on(INSTANCE, () =>
      fail ? errorBody(500, 'internal', { requestId: 'req-500' }) : envelope(instanceBody()),
    );
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    stub.on(NAMES, () => envelope([]));
    const user = userEvent.setup();
    renderApp(`/apps/1/instances/7?${RANGE_QUERY}`);
    const error = await screen.findByTestId('instance-error', {}, FIND);
    expect(error).toHaveTextContent('req-500');
    fail = false;
    await user.click(within(error).getByRole('button', { name: /retry/i }));
    await waitFor(() => {
      expect(screen.getByTestId('instance-page')).toHaveAttribute('data-state', 'ready');
    }, FIND);
  });

  it('switches tabs through the URL and with the arrow keys', async () => {
    answer();
    const user = userEvent.setup();
    const app = await open(`/apps/1/instances/7?${RANGE_QUERY}`, ['operator']);
    await user.click(screen.getByTestId('instance-tab-env'));
    await waitFor(() => {
      expect(app.router.state.location.search).toContain('tab=env');
    }, FIND);
    expect(await screen.findByTestId('instance-tabpanel-env', {}, FIND)).toBeInTheDocument();
    screen.getByTestId('instance-tab-env').focus();
    await user.keyboard('{ArrowRight}');
    await waitFor(() => {
      expect(app.router.state.location.search).toContain('tab=commands');
    }, FIND);
    expect(await screen.findByTestId('stub-commands-tab', {}, FIND)).toHaveAttribute(
      'data-instance-id',
      '7',
    );
    // Two route transitions with lazy tab panels: the 5 s default is too short under a full-suite
    // load (the assertions keep their own 5 s waits).
  }, 20_000);

  it('shows the operator notice instead of commands and dumps for a viewer', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=commands`, ['viewer']);
    expect(screen.getByTestId('instance-operator-only')).toBeInTheDocument();
    expect(screen.queryByTestId('stub-commands-tab')).toBeNull();
  });

  it('shows the dumps tab to an operator', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=dumps`, ['operator']);
    expect(await screen.findByTestId('stub-dumps-tab', {}, FIND)).toBeInTheDocument();
  });
});

describe('environment tab per role (05 §2.7-3)', () => {
  it('viewer: notices for the environment dump, the effective configuration and the full self diagnosis; public summary', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=env`, ['viewer']);
    expect(screen.getByTestId('instance-env-dump-denied')).toHaveTextContent('administrator');
    expect(screen.getByTestId('instance-config-denied')).toHaveTextContent('operator');
    expect(screen.getByTestId('instance-diagnosis-denied')).toBeInTheDocument();
    expect(screen.queryByTestId('stub-latest-env_dump')).toBeNull();
    expect(screen.queryByTestId('stub-latest-self_diagnosis')).toBeNull();
    expect(stub.calls(CONFIG)).toHaveLength(0);
    await waitFor(() => {
      expect(screen.getByTestId('instance-diagnosis-dropped')).toHaveAttribute('data-value', '0');
    }, FIND);
    // Remote-allowed keys of the instance.
    const allow = screen.getByTestId('instance-remote-allow');
    expect(allow).toHaveAttribute('data-count', '2');
    expect(allow).toHaveTextContent('sampling.*');
    // Module errors over the range from argos_agent_module_errors_total.
    const modules = screen.getByTestId('instance-modules-table');
    await waitFor(() => {
      expect(within(modules).getByText('jdbc')).toBeInTheDocument();
    }, FIND);
  });

  it('operator: the server effective configuration with the "source unknown" notice', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=env`, ['operator']);
    const table = await screen.findByTestId('instance-config-table', {}, FIND);
    expect(screen.getByTestId('instance-config-source-unknown')).toBeInTheDocument();
    expect(screen.getByTestId('instance-config-run-self-diagnosis')).toBeInTheDocument();
    expect(within(table).getByText('sampling.normal-rate')).toBeInTheDocument();
    expect(within(table).getByText('Server push')).toBeInTheDocument();
    expect(screen.getByTestId('stub-latest-self_diagnosis')).toBeInTheDocument();
    expect(screen.getByTestId('instance-env-dump-denied')).toBeInTheDocument();
  });

  it('operator: values and sources of the latest self diagnosis replace the unknown ones', async () => {
    selfDiagnosis.current = {
      report: {
        effectiveConfig: [
          { key: 'log.level', value: 'DEBUG', source: 'file' },
          { key: 'server.address', value: 'collector:9100', source: 'env' },
          { key: 'sampling.profile.normal-rate', value: '0.3', source: 'remote' },
        ],
        modules: [
          { name: 'servlet', state: 'ENABLED', errors: 0, reason: null },
          {
            name: 'okhttp',
            state: 'DISABLED_BY_REMOTE',
            errors: 0,
            reason: 'instrumentation.disabled (remote configuration)',
          },
        ],
      },
      at: '2026-11-20T11:58:00.000Z',
      available: true,
      loading: false,
    };
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=env`, ['operator']);
    await screen.findByTestId('instance-config-table', {}, FIND);
    // C-234 (3): opening the tab reads no self diagnosis result (audited); the user asks for it.
    expect(selfDiagnosis.loads.every((load) => !load)).toBe(true);
    expect(screen.getByTestId('instance-config-source-unknown')).toBeInTheDocument();
    expect(screen.getByTestId('stub-latest-self_diagnosis')).toHaveAttribute(
      'data-revealed',
      'false',
    );
    await userEvent.setup().click(screen.getByTestId('instance-config-show-self-diagnosis'));
    const table = screen.getByTestId('instance-config-table');
    await waitFor(() => {
      expect(screen.queryByTestId('instance-config-source-unknown')).toBeNull();
    }, FIND);
    expect(screen.getByTestId('stub-latest-self_diagnosis')).toHaveAttribute(
      'data-revealed',
      'true',
    );
    expect(screen.getByTestId('instance-config-reported-at')).toBeInTheDocument();
    const row = table.querySelector('[data-row-key="log.level"]') as HTMLElement;
    expect(row).toHaveTextContent('DEBUG');
    expect(row).toHaveTextContent('File');
    expect(row).toHaveAttribute('data-origin', 'agent');
    expect(table.querySelector('[data-row-key="server.address"]')).toHaveTextContent(
      'Environment variable',
    );
    expect(table.querySelector('[data-row-key="sampling.profile.normal-rate"]')).toHaveTextContent(
      'Server push',
    );
    const modules = screen.getByTestId('instance-modules-table');
    expect(within(modules).getByText('servlet')).toBeInTheDocument();
    expect(modules.querySelector('[data-row-key="servlet"]')).toHaveAttribute(
      'data-module-state',
      'active',
    );
    // 05 §2.7-3 module status: the agent's DISABLED_BY_* state is "disabled", with its reason.
    const okhttp = modules.querySelector('[data-row-key="okhttp"]') as HTMLElement;
    expect(okhttp).toHaveAttribute('data-module-state', 'disabled');
    expect(okhttp).toHaveTextContent('instrumentation.disabled (remote configuration)');
  });

  it('administrator: the environment dump panel', async () => {
    answer();
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=env`, ['admin']);
    expect(screen.getByTestId('stub-latest-env_dump')).toHaveAttribute('data-instance-id', '7');
    expect(screen.queryByTestId('instance-env-dump-denied')).toBeNull();
  });

  it('T-UI-005 renders collected configuration keys, values and allow patterns as text', async () => {
    const payload = XSS_PAYLOADS[0] ?? '';
    selfDiagnosis.current = {
      report: {
        effectiveConfig: XSS_PAYLOADS.map((value, i) => ({
          key: `k.${String(i)}`,
          value,
          source: value,
        })),
        modules: [{ name: payload, state: payload, errors: 1, reason: payload }],
      },
      at: '2026-11-20T11:58:00.000Z',
      available: true,
      loading: false,
    };
    answer();
    stub.on(INSTANCE, () =>
      envelope(instanceBody({ remote_config_allow: XSS_PAYLOADS.map((p) => untrusted(p)) })),
    );
    await open(`/apps/1/instances/7?${RANGE_QUERY}&tab=env`, ['operator']);
    await screen.findByTestId('instance-config-table', {}, FIND);
    await userEvent.setup().click(screen.getByTestId('instance-config-show-self-diagnosis'));
    await waitFor(() => {
      expect(screen.getByTestId('instance-config-reported-at')).toBeInTheDocument();
    }, FIND);
    expect(findInjectedMarkup(screen.getByTestId('instance-page'))).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('instance list', () => {
  it('lists the instances with state and versions and opens the detail', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/apps/1/instances', () =>
      envelope([
        instanceBody(),
        instanceBody({
          instance_id: 8,
          name: untrusted('order-api-2'),
          state: 'DOWN',
          compat: 'old',
          ip_mode: null,
        }),
      ]),
    );
    answer();
    const user = userEvent.setup();
    const app = renderApp(`/apps/1/instances?${RANGE_QUERY}`);
    const page = await screen.findByTestId('instances-page', {}, FIND);
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'ready');
    }, FIND);
    const down = page.querySelector('[data-row-key="8"]') as HTMLElement;
    expect(down).toHaveTextContent('order-api-2');
    expect(down).toHaveTextContent('Old');
    expect(within(down).getByTestId('status-badge')).toHaveAttribute('data-status', 'DOWN');
    await user.click(screen.getByTestId('instances-link-7'));
    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/apps/1/instances/7');
    }, FIND);
    expect(app.router.state.location.search).toContain(`from=${new Date(FROM).toISOString()}`);
  });

  it('names the next step when no instance has connected', async () => {
    signedIn(stub);
    stub.on('GET /api/v1/apps/1/instances', () => envelope([]));
    renderApp('/apps/1/instances');
    const page = await screen.findByTestId('instances-page', {}, FIND);
    await waitFor(() => {
      expect(page).toHaveAttribute('data-state', 'empty');
    }, FIND);
    expect(page).toHaveTextContent('No instance of this application has connected yet.');
  });
});
