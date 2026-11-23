// SPDX-License-Identifier: Apache-2.0
//
// Columns of the running transactions list (05 §2.6-2, docs/15 §9.6 ActiveTable): the nine
// required columns instance, service, elapsed, current state (with its detail: the SQL summary, the
// called host, the database of a connection wait), thread, user id, IP, kind and start. Every
// collected string renders through SafeText (SEC-60); a user id or IP the instance does not
// collect says so ("Not collected"), apart from a value that is simply absent (the dash, D-23).
// The instance name links to the instance detail (WO-37).
import type { TFunction } from 'i18next';
import { Link } from 'react-router';

import type { ActiveInstance, ActiveItem } from '../../api/active';
import type { Untrusted } from '../../api/untrusted';
import type { DataTableColumn } from '../../components/DataTable';
import { SafeText } from '../../components/SafeText';
import type { Formatter } from '../../i18n/format';

import { activeState, kindLabelKey, STATE_LABEL_KEYS } from './labels';
import { bandOf, type Thresholds } from './model';

/** Test id suffixes of the cells (testid contract `active-cell-<column>-<txId>`). */
export type CellName =
  'instance' | 'service' | 'elapsed' | 'state' | 'thread' | 'user' | 'ip' | 'kind' | 'started';

export function cellTestId(name: CellName, item: ActiveItem): string {
  return `active-cell-${name}-${item.tx_id}`;
}

export interface ColumnContext {
  readonly t: TFunction<['common', 'active']>;
  readonly format: Formatter;
  readonly thresholds: Thresholds;
  readonly instances: ReadonlyMap<number, ActiveInstance>;
  /** The instance detail link of an instance (WO-37); absent: the name is plain text. */
  readonly instanceHref?: (instanceId: number) => string;
}

/** The name of an item's instance (collected), or null when the server has none. */
export function instanceName(
  instances: ReadonlyMap<number, ActiveInstance>,
  instanceId: number,
): Untrusted | null {
  return instances.get(instanceId)?.name ?? null;
}

function NotCollected({ t, testId }: { readonly t: ColumnContext['t']; readonly testId: string }) {
  return (
    <span
      className="active-muted"
      title={t('active:table.notCollectedTitle')}
      data-missing="notCollected"
      data-testid={testId}
    >
      {t('active:table.notCollected')}
    </span>
  );
}

/** The state label and its one-line detail. */
export function StateCell({
  item,
  t,
}: {
  readonly item: ActiveItem;
  readonly t: ColumnContext['t'];
}) {
  const state = activeState(item.state);
  const label = state === null ? t('active:state.unknown') : t(STATE_LABEL_KEYS[state]);
  const showDetail = state === 'SQL' || state === 'HTTP' || state === 'DBC';
  return (
    <span
      className="active-state"
      data-state={state ?? 'UNKNOWN'}
      data-testid={cellTestId('state', item)}
    >
      <span
        className={`active-state__label active-state__label--${(state ?? 'unknown').toLowerCase()}`}
      >
        {label}
      </span>
      {showDetail ? (
        <SafeText
          value={item.state_detail}
          mono={state === 'SQL'}
          className="active-state__detail"
          data-testid={`active-cell-state-detail-${item.tx_id}`}
        />
      ) : null}
    </span>
  );
}

export function activeColumns(context: ColumnContext): DataTableColumn<ActiveItem>[] {
  const { t, format, thresholds, instances, instanceHref } = context;
  return [
    {
      id: 'instance',
      header: t('active:table.instance'),
      value: (item) => instanceName(instances, item.instance_id) ?? `#${String(item.instance_id)}`,
      filterable: true,
      filterField: 'instance',
      filterValue: (item) => String(item.instance_id),
      // The nine columns fit the 1366 px layout without a sideways scroll (about 1,110 px).
      width: 120,
      render: (item) => {
        const name = instanceName(instances, item.instance_id);
        const label =
          name === null ? (
            <span data-testid={cellTestId('instance', item)}>
              {t('active:bars.unnamed', { id: item.instance_id })}
            </span>
          ) : (
            <SafeText value={name} data-testid={cellTestId('instance', item)} />
          );
        if (instanceHref === undefined) {
          return label;
        }
        // The instance detail (WO-37); a click on the link opens it, the rest of the row selects.
        return (
          <Link
            to={instanceHref(item.instance_id)}
            className="active-instance-link"
            title={t('active:table.instanceLink')}
            data-testid={`active-instance-link-${item.tx_id}`}
          >
            {label}
          </Link>
        );
      },
    },
    {
      id: 'service',
      header: t('active:table.service'),
      value: (item) => item.service,
      filterable: true,
      filterField: 'service',
      // The key, never the name (docs/14 §6 f.service=<service key>).
      filterValue: (item) => item.service_id,
      width: 160,
      render: (item) => <SafeText value={item.service} data-testid={cellTestId('service', item)} />,
    },
    {
      id: 'elapsed_ms',
      header: t('active:table.elapsed'),
      value: (item) => item.elapsed_ms,
      kind: 'ms',
      width: 90,
      render: (item) => {
        const band = bandOf(item.elapsed_ms, thresholds);
        return (
          <span
            className={`active-elapsed active-elapsed--band-${String(band)}`}
            data-band={band}
            data-value={item.elapsed_ms}
            data-testid={cellTestId('elapsed', item)}
          >
            <span className="active-elapsed__swatch" aria-hidden="true" />
            {format.duration(item.elapsed_ms)}
          </span>
        );
      },
    },
    {
      id: 'state',
      header: t('active:table.state'),
      value: (item) => item.state,
      filterable: true,
      filterField: 'state',
      filterValue: (item) => item.state,
      width: 230,
      render: (item) => <StateCell item={item} t={t} />,
    },
    {
      id: 'thread',
      header: t('active:table.thread'),
      value: (item) => item.thread,
      width: 140,
      render: (item) => (
        <span className="active-thread" data-testid={cellTestId('thread', item)}>
          <SafeText value={item.thread} mono />
          {item.virtual_thread ? (
            <span
              className="active-thread__virtual"
              title={t('active:table.virtualThreadTitle')}
              data-testid={`active-cell-virtual-${item.tx_id}`}
            >
              {t('active:table.virtualThread')}
            </span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'user_id',
      header: t('active:table.user'),
      value: (item) => item.user_id,
      width: 100,
      render: (item) =>
        item.user_id === null && item.user_id_mode === 'off' ? (
          <NotCollected t={t} testId={cellTestId('user', item)} />
        ) : (
          <SafeText value={item.user_id} data-testid={cellTestId('user', item)} />
        ),
    },
    {
      id: 'remote_ip',
      header: t('active:table.ip'),
      value: (item) => item.remote_ip,
      width: 110,
      render: (item) =>
        item.remote_ip === null && item.ip_mode === 'off' ? (
          <NotCollected t={t} testId={cellTestId('ip', item)} />
        ) : (
          <SafeText value={item.remote_ip} mono data-testid={cellTestId('ip', item)} />
        ),
    },
    {
      id: 'kind',
      header: t('active:table.kind'),
      value: (item) => item.kind,
      width: 70,
      render: (item) => {
        const key = kindLabelKey(item.kind);
        return (
          <span data-kind={item.kind} data-testid={cellTestId('kind', item)}>
            {key === null ? t('active:table.unknownKind') : t(key)}
          </span>
        );
      },
    },
    {
      id: 'started_at',
      header: t('active:table.started'),
      value: (item) => Date.parse(item.started_at),
      width: 90,
      render: (item) => {
        const at = Date.parse(item.started_at);
        return Number.isFinite(at) ? (
          <span
            title={format.dateTimeWithOffset(at)}
            data-value={item.started_at}
            data-testid={cellTestId('started', item)}
          >
            {format.time(at)}
          </span>
        ) : (
          <SafeText value={null} data-testid={cellTestId('started', item)} />
        );
      },
    },
  ];
}
