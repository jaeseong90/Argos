// SPDX-License-Identifier: Apache-2.0
//
// Latest environment dump / self diagnosis panels and the self diagnosis hook of the environment
// tab (05 §2.7-3, WO-37 PM questions 2-4): the environment dump (JVM options included) only for
// administrators, values masked by the agent shown exactly as sent (`****(len=N)`), never an
// original; the self diagnosis for operators (viewers get the reason), the newest result the user
// may read (a newer one of another user is mentioned), run/refresh, and the effective
// configuration with sources and module states handed to the environment tab.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'SEC-20' }, { type: 'req', description: 'SEC-21' }, { type: 'req', description: 'SEC-50' }
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../../test/apiStub';
import enCommands from '../../../locales/en/commands.json';
import { resetWrittenUrls } from '../../../state/useUrlState';

import { LatestResultPanel } from './LatestResultPanel';
import { commandView, OTHER, appTree, signIn, textResult } from './testing/fixtures';
import { useLatestSelfDiagnosis } from './useLatestSelfDiagnosis';

const FIND = { timeout: 5000 };
const LIST = 'GET /api/v1/instances/7/commands';
/** What the agent never sends: the original value of a masked key. */
const SENTINEL = 'ARGOS_SEED_SECRET_VALUE';

let stub: ApiStub;

/** Renders `ui` at the instance detail route (fixtures appTree). */
function renderAt(ui: ReactNode, path?: string) {
  const tree = appTree(ui, path);
  return { ...render(tree.element), url: tree.url };
}

beforeEach(() => {
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

const ENV = JSON.stringify({
  env: { DB_PASSWORD: '****(len=23)', LANG: 'C.UTF-8' },
  system_properties: { 'java.version': '21.0.5', 'app.secret': '****(len=8)' },
  jvm_arguments: ['-Xmx2g', '-javaagent:/opt/argos/argos-agent.jar'],
});

/** The agent's real ENV_DUMP form (WO-17 EnvDumpCommand, text/plain, C-234 (1)). */
const ENV_TEXT = [
  '# env (3)',
  'DB_PASSWORD=****(len=23)',
  'JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64',
  'REDIS_URL=redis://:****(len=9)@cache:6379',
  '',
  '# properties (2)',
  'app.secret=****(len=8)',
  'java.version=21.0.5',
  '',
  '# args (2)',
  '-Xmx2g',
  '-Dspring.datasource.password=****(len=11)',
  '',
].join('\n');

const DIAG = JSON.stringify({
  format: 1,
  premain_ms: 640.5,
  counters: { argos_agent_dropped_total: 0 },
  modules: [{ id: 1, name: 'servlet', state: 'ACTIVE', errors: 0 }],
  effective_config: [{ key: 'sampling.normal-rate', value: 0.25, source: 'server' }],
});

describe('environment dump panel (SEC-21, SEC-20)', () => {
  it('viewers and operators get "administrator permission required", JVM options included', async () => {
    for (const role of ['viewer', 'operator'] as const) {
      signIn(role);
      const { unmount } = renderAt(<LatestResultPanel appId={3} instanceId={7} type="env_dump" />);
      expect(await screen.findByTestId('latest-env_dump-forbidden')).toHaveTextContent(
        enCommands.latest.adminOnly,
      );
      unmount();
    }
    expect(stub.calls(LIST)).toHaveLength(0);
  });

  it('an administrator sees masked values as sent and never an original', async () => {
    const dump = commandView({ type: 'env_dump', requested_by: OTHER, requested_by_name: 'root' });
    stub.on(LIST, () => envelope([dump]));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, () =>
      envelope(textResult('env_dump', ENV, dump.command_id)),
    );
    signIn('admin');
    const { container } = renderAt(<LatestResultPanel appId={3} instanceId={7} type="env_dump" />);
    const panel = await screen.findByTestId('latest-env_dump', {}, FIND);
    await within(panel).findByTestId('env-dump-report', {}, FIND);
    expect(panel).toHaveAttribute('data-state', 'ready');
    expect(screen.getByTestId('env-dump-env')).toHaveTextContent('****(len=23)');
    expect(screen.getByTestId('env-dump-system-properties')).toHaveTextContent('****(len=8)');
    expect(screen.getByTestId('env-dump-jvm-arguments')).toHaveTextContent('-Xmx2g');
    expect(container.querySelectorAll('[data-masked="true"]')).toHaveLength(2);
    expect(container.textContent).not.toContain(SENTINEL);
    expect(screen.getByTestId('latest-env_dump-meta')).toHaveTextContent('root');
    expect(screen.getByTestId('command-run-env_dump')).toHaveTextContent(enCommands.latest.refresh);
  });

  it('reads the agent sectioned text into tables, still masked (C-234 (1))', async () => {
    const dump = commandView({ type: 'env_dump' });
    stub.on(LIST, () => envelope([dump]));
    stub.on(`GET /api/v1/commands/${dump.command_id}/result`, () =>
      envelope({
        ...textResult('env_dump', ENV_TEXT, dump.command_id),
        content_type: 'text/plain; charset=utf-8',
      }),
    );
    signIn('admin');
    const { container } = renderAt(<LatestResultPanel appId={3} instanceId={7} type="env_dump" />);
    const panel = await screen.findByTestId('latest-env_dump', {}, FIND);
    await within(panel).findByTestId('env-dump-report', {}, FIND);
    expect(screen.queryByTestId('env-dump-raw')).toBeNull();
    const env = screen.getByTestId('env-dump-env');
    expect(within(env).getAllByRole('row')).toHaveLength(4); // header + 3
    expect(env).toHaveTextContent('/usr/lib/jvm/java-21-openjdk-amd64');
    expect(env).toHaveTextContent('****(len=23)');
    expect(screen.getByTestId('env-dump-system-properties')).toHaveTextContent('****(len=8)');
    const args = within(screen.getByTestId('env-dump-jvm-arguments')).getAllByRole('listitem');
    expect(args).toHaveLength(2);
    expect(args[1]).toHaveAttribute('data-masked', 'true');
    // DB_PASSWORD, REDIS_URL (URL credentials), app.secret, the -D password argument.
    expect(container.querySelectorAll('[data-masked="true"]')).toHaveLength(4);
    expect(container.textContent).not.toContain(SENTINEL);
  });
});

describe('self diagnosis panel and hook', () => {
  it('viewers get the reason; operators see their newest readable report and a note about a newer one', async () => {
    signIn('viewer');
    const first = renderAt(<LatestResultPanel appId={3} instanceId={7} type="self_diagnosis" />);
    expect(await screen.findByTestId('latest-self_diagnosis-forbidden')).toBeInTheDocument();
    first.unmount();

    const theirs = commandView({ type: 'self_diagnosis', requested_by: OTHER });
    const mine = commandView({ type: 'self_diagnosis', requested_at: '2026-11-24T09:00:00.000Z' });
    stub.on(LIST, () => envelope([theirs, mine]));
    stub.on(`GET /api/v1/commands/${mine.command_id}/result`, () =>
      envelope(textResult('self_diagnosis', DIAG, mine.command_id)),
    );
    signIn('operator');
    renderAt(<LatestResultPanel appId={3} instanceId={7} type="self_diagnosis" />);
    const panel = await screen.findByTestId('latest-self_diagnosis', {}, FIND);
    await within(panel).findByTestId('self-diagnosis-report', {}, FIND);
    expect(panel).toHaveAttribute('data-command-id', mine.command_id);
    expect(screen.getByTestId('latest-self_diagnosis-newer')).toBeInTheDocument();
    expect(screen.getByTestId('self-diagnosis-config')).toHaveTextContent('server');
    expect(stub.calls(`GET /api/v1/commands/${theirs.command_id}/result`)).toHaveLength(0);
  });

  it('runs a self diagnosis when there is none', async () => {
    const id = '0a1b2c3d-4e5f-4a6b-8c7d-0000000000ee';
    stub.on(LIST, () => envelope([]));
    stub.on('POST /api/v1/instances/7/commands', () =>
      envelope({ command_id: id, status: 'queued', ttl_s: 60 }, {}, 202),
    );
    stub.on(`GET /api/v1/commands/${id}`, () =>
      envelope(commandView({ command_id: id, type: 'self_diagnosis', status: 'sent' })),
    );
    signIn('operator');
    renderAt(<LatestResultPanel appId={3} instanceId={7} type="self_diagnosis" />);
    expect(await screen.findByTestId('latest-self_diagnosis-empty', {}, FIND)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('command-run-self_diagnosis'));
    fireEvent.click(screen.getByTestId('command-dialog-confirm'));
    await waitFor(() => {
      expect(screen.getByTestId('latest-self_diagnosis-feedback')).toHaveAttribute(
        'data-state',
        'sent',
      );
    }, FIND);
  });

  it('hands the effective configuration with sources and module states to the environment tab', async () => {
    const mine = commandView({ type: 'self_diagnosis', finished_at: '2026-11-24T10:00:03.000Z' });
    const result = `GET /api/v1/commands/${mine.command_id}/result`;
    stub.on(LIST, () => envelope([mine]));
    stub.on(result, () => envelope(textResult('self_diagnosis', DIAG, mine.command_id)));
    signIn('operator');
    function Probe() {
      const [load, setLoad] = useState(false);
      const latest = useLatestSelfDiagnosis(7, load);
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setLoad(true);
            }}
          >
            show
          </button>
          <output
            data-testid="probe"
            data-at={latest.at ?? ''}
            data-available={String(latest.available)}
          >
            {JSON.stringify(latest.report)}
          </output>
        </>
      );
    }
    renderAt(<Probe />);
    // C-234 (3): the result (audited on the server) is not read until asked for.
    await waitFor(() => {
      expect(screen.getByTestId('probe')).toHaveAttribute('data-available', 'true');
    }, FIND);
    expect(screen.getByTestId('probe')).toHaveTextContent('null');
    expect(stub.calls(result)).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'show' }));
    await waitFor(() => {
      expect(screen.getByTestId('probe')).toHaveAttribute('data-at', '2026-11-24T10:00:03.000Z');
    }, FIND);
    expect(JSON.parse(screen.getByTestId('probe').textContent)).toEqual({
      effectiveConfig: [{ key: 'sampling.normal-rate', value: '0.25', source: 'server' }],
      modules: [{ name: 'servlet', state: 'ACTIVE', errors: 0, reason: null }],
    });
    expect(stub.calls(result)).toHaveLength(1);
  });

  it('with reveal, the panel reads the latest self diagnosis only when the user asks (C-234 (3))', async () => {
    const mine = commandView({ type: 'self_diagnosis' });
    const result = `GET /api/v1/commands/${mine.command_id}/result`;
    stub.on(LIST, () => envelope([mine]));
    stub.on(result, () => envelope(textResult('self_diagnosis', DIAG, mine.command_id)));
    signIn('operator');
    function Host() {
      const [revealed, setRevealed] = useState(false);
      return (
        <LatestResultPanel
          appId={3}
          instanceId={7}
          type="self_diagnosis"
          reveal={{
            revealed,
            onReveal: () => {
              setRevealed(true);
            },
          }}
        />
      );
    }
    renderAt(<Host />);
    const panel = await screen.findByTestId('latest-self_diagnosis', {}, FIND);
    await waitFor(() => {
      expect(panel).toHaveAttribute('data-state', 'hidden');
    }, FIND);
    expect(screen.getByTestId('latest-self_diagnosis-show')).toHaveTextContent(
      enCommands.latest.show.self_diagnosis,
    );
    expect(screen.queryByTestId('self-diagnosis-report')).toBeNull();
    expect(stub.calls(result)).toHaveLength(0);
    fireEvent.click(screen.getByTestId('latest-self_diagnosis-show'));
    await within(panel).findByTestId('self-diagnosis-report', {}, FIND);
    expect(panel).toHaveAttribute('data-state', 'ready');
    expect(stub.calls(result)).toHaveLength(1);
  });

  it('the hook gives no report to viewers (no list call)', async () => {
    signIn('viewer');
    function Probe() {
      const latest = useLatestSelfDiagnosis(7, true);
      return <output data-testid="probe">{latest.report === null ? 'none' : 'report'}</output>;
    }
    renderAt(<Probe />);
    expect(await screen.findByTestId('probe')).toHaveTextContent('none');
    expect(stub.calls(LIST)).toHaveLength(0);
  });
});
