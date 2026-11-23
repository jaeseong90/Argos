// SPDX-License-Identifier: Apache-2.0
//
// Active transactions /apps/:appId/active (docs/15 §9.6, 05 §2.6, F-U04, WO-35): what runs right
// now on every live instance of the application. Top: the summary (running, top band, busiest
// instance, alert rule link), "as of N s ago", pause and the "updates stopped" badge. Then the
// per-instance bars with the four elapsed bands (thresholds per browser), and the list of running
// transactions with the nine required columns, sorted by elapsed (longest first) by default. A
// selected row offers this thread's stack and the instance's thread dump (remote commands, operator
// role; WO-37), the instance detail and the recent completions of the same service (XLog).
//
// Data: `GET /apps/{appId}/active?kind=ALL`, then the `active` topic replaces the snapshot every
// 5 s (useActiveSnapshot). Filters (URL, docs/14 §6): `f.instance`, `f.service` (service key),
// `f.state` and the global kind apply to the list only; the bars and the summary stay app-wide.
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import {
  ACTIVE_MAX_ITEMS,
  ACTIVE_STATES,
  type ActiveInstance,
  type ActiveItem,
} from '../../api/active';
import { errorInfo } from '../../api/errors';
import { untrusted, type Untrusted } from '../../api/untrusted';
import { DataTable, type SortSpec } from '../../components/DataTable';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import type { FilterValueLabel } from '../../components/FilterChipBar';
import { addCellFilter, type CellFilter, type FilterSpec } from '../../components/filters';
import { LoadingState } from '../../components/LoadingState';
import { useFormat } from '../../i18n/format';
import { useRegisterFilterLabels } from '../../routes/filterLabels';
import { useAppId } from '../../routes/navigation';
import { screenById, screenPath } from '../../routes/screens';
import { toUrlFilters } from '../../state/filters';
import { useUiStore } from '../../state/uiStore';
import { carriedQuery, CommonQ, FILTER_MAX, type Filter } from '../../state/urlState';
import { useUrlState } from '../../state/useUrlState';

import { ActiveSummary } from './ActiveSummary';
import { BandLegend } from './bands';
import { activeColumns } from './columns';
import { InstanceBars } from './InstanceBars';
import { activeState, STATE_LABEL_KEYS } from './labels';
import {
  asOfSeconds,
  bandOf,
  filterItems,
  instanceBands,
  itemKey,
  selectedInstances,
  selectedState,
  summarize,
  toggleInstanceFilter,
  withStateFilter,
  type InstanceBands,
  type Thresholds,
} from './model';
import { instanceHref, RowActions } from './RowActions';
import { readThresholds, writeThresholds } from './thresholdPreference';
import { ThresholdSettings } from './ThresholdSettings';
import { useActiveSnapshot } from './useActiveSnapshot';

import './activePage.css';

/** The age above which "as of" turns into a warning (docs/15 §5: 10 s). */
const AS_OF_WARN_S = 10;
const DEFAULT_SORT: SortSpec = { id: 'elapsed_ms', desc: true };
const EMPTY_INSTANCES: readonly ActiveInstance[] = [];
const EMPTY_ITEMS: readonly ActiveItem[] = [];

function urlFilter(field: string, value: string): Filter {
  return { field, op: 'eq', value: untrusted(value) };
}

/** URL filters of component filters (invalid ones dropped, at most FILTER_MAX). */
function toUrlFilterList(specs: readonly FilterSpec[]): Filter[] {
  const out: Filter[] = [];
  for (const spec of specs) {
    out.push(...(toUrlFilters(spec) ?? []));
  }
  return out.slice(0, FILTER_MAX);
}

/** The table sort of the URL (`field:asc|desc`), or the default elapsed descending. */
function tableSort(sort: string | undefined): SortSpec {
  if (sort === undefined) {
    return DEFAULT_SORT;
  }
  const colon = sort.lastIndexOf(':');
  return { id: sort.slice(0, colon), desc: sort.slice(colon + 1) === 'desc' };
}

export function ActivePage() {
  const { t } = useTranslation(['common', 'active']);
  const format = useFormat();
  const navigate = useNavigate();
  const appId = useAppId();
  const { state, set } = useUrlState(CommonQ);
  const filters = state.filters;
  const kind = state.kind;
  const snapshot = useActiveSnapshot(appId);
  const { shown, paused, setPaused, now, staleSeconds, polling, query } = snapshot;

  const [thresholds, setThresholds] = useState<Thresholds>(readThresholds);
  const applyThresholds = useCallback((next: Thresholds) => {
    setThresholds(next);
    writeThresholds(next);
  }, []);

  const data = shown?.data;
  const instances = data?.per_instance ?? EMPTY_INSTANCES;
  const items = data?.items ?? EMPTY_ITEMS;
  const instanceMap = useMemo(
    () => new Map(instances.map((instance) => [instance.instance_id, instance] as const)),
    [instances],
  );
  const bands = useMemo(() => {
    const out = new Map<number, InstanceBands>();
    for (const instance of instances) {
      out.set(instance.instance_id, instanceBands(instance, items, thresholds));
    }
    return out;
  }, [instances, items, thresholds]);
  const summary = useMemo(() => summarize([...bands.values()]), [bands]);
  const bandTotals = useMemo(() => {
    const totals = [0, 0, 0, 0];
    for (const entry of bands.values()) {
      entry.bands.forEach((value, index) => {
        totals[index] = (totals[index] ?? 0) + value;
      });
    }
    return totals;
  }, [bands]);

  // The global kind filter offers the kinds of this snapshot too (C-225 (7)): a running BATCH
  // transaction makes Batch selectable although the app recorded no finished batch job yet.
  const setSnapshotKinds = useUiStore((ui) => ui.setSnapshotKinds);
  const kindsKey = useMemo(
    () => [...new Set(items.map((item) => item.kind))].sort().join(','),
    [items],
  );
  useEffect(() => {
    setSnapshotKinds(
      appId === null ? null : { appId, kinds: kindsKey === '' ? [] : kindsKey.split(',') },
    );
  }, [appId, kindsKey, setSnapshotKinds]);
  useEffect(
    () => () => {
      setSnapshotKinds(null);
    },
    [setSnapshotKinds],
  );

  const filtered = useMemo(() => filterItems(items, filters, kind), [items, filters, kind]);
  const rows = filtered.items;

  // Chip names: instance ids and service keys are keys (docs/14 §6), states are codes.
  const serviceNames = useMemo(() => {
    const names = new Map<string, Untrusted>();
    for (const item of items) {
      if (item.service_id !== null && item.service !== null) {
        names.set(item.service_id, item.service);
      }
    }
    return names;
  }, [items]);
  const valueLabel = useCallback<FilterValueLabel>(
    (filter) => {
      if (filter.field === 'instance') {
        return instanceMap.get(Number(filter.value))?.name ?? null;
      }
      if (filter.field === 'service') {
        return serviceNames.get(filter.value) ?? null;
      }
      if (filter.field === 'state') {
        const code = activeState(filter.value);
        return code === null ? null : untrusted(t(STATE_LABEL_KEYS[code]));
      }
      return null;
    },
    [instanceMap, serviceNames, t],
  );
  useRegisterFilterLabels(valueLabel);

  const writeFilters = useCallback(
    (next: readonly Filter[]) => {
      set({ filters: [...next].slice(0, FILTER_MAX) }, { replace: false });
    },
    [set],
  );
  const toggleInstance = useCallback(
    (instanceId: number) => {
      writeFilters(
        toggleInstanceFilter(filters, instanceId, (value) => urlFilter('instance', value)),
      );
    },
    [filters, writeFilters],
  );
  const chooseState = (next: string | null): void => {
    writeFilters(withStateFilter(filters, next, (value) => urlFilter('state', value)));
  };
  const onCellFilter = (filter: CellFilter): void => {
    writeFilters(toUrlFilterList(addCellFilter(filters, filter)));
  };
  const onFiltersChange = (next: FilterSpec[]): void => {
    writeFilters(toUrlFilterList(next));
  };
  const clearFilters =
    filters.length > 0
      ? () => {
          set({ filters: [] }, { replace: false });
        }
      : undefined;
  // Running transactions of other kinds are hidden by the global kind (default HTTP).
  const otherKinds = kind !== 'ALL' && items.some((item) => item.kind !== kind);

  const sort = tableSort(state.sort);
  const onSortChange = (next: SortSpec | null): void => {
    set({ sort: next === null ? undefined : `${next.id}:${next.desc ? 'desc' : 'asc'}` });
  };

  // Selection: kept by key across snapshots; the last seen row stays while it is gone.
  const [selection, setSelection] = useState<{ key: string; item: ActiveItem } | null>(null);
  const selectedNow = useMemo(
    () => (selection === null ? null : (items.find((i) => itemKey(i) === selection.key) ?? null)),
    [items, selection],
  );
  const selectedItem = selectedNow ?? selection?.item ?? null;

  // Instance names link to the instance detail (WO-37), keeping the carried keys (docs/15 §5).
  const { time, live, kind: carriedKind, env, kiosk } = state;
  const instanceLink = useMemo(
    () =>
      appId === null
        ? undefined
        : (instanceId: number) =>
            instanceHref(appId, instanceId, { time, live, kind: carriedKind, env, kiosk }),
    [appId, time, live, carriedKind, env, kiosk],
  );

  const columns = useMemo(
    () =>
      activeColumns({
        t,
        format,
        thresholds,
        instances: instanceMap,
        ...(instanceLink === undefined ? {} : { instanceHref: instanceLink }),
      }),
    [t, format, thresholds, instanceMap, instanceLink],
  );

  const currentState = selectedState(filters);
  const instanceSelection = selectedInstances(filters);

  // As of / pause / stale.
  const asOf = shown === null ? null : asOfSeconds(shown, now);
  const asOfText =
    asOf === null
      ? t('active:asOf.none')
      : paused
        ? t('active:asOf.paused', { seconds: asOf })
        : t('active:asOf.label', { seconds: asOf });

  const refetch = query.refetch;
  let body: ReactNode;
  let pageState: 'loading' | 'error' | 'empty' | 'ready';
  if (appId === null) {
    pageState = 'empty';
    body = null;
  } else if (shown === null) {
    pageState = query.isError ? 'error' : 'loading';
    body = query.isError ? (
      <ErrorState
        error={errorInfo(query.error)}
        onRetry={() => {
          void refetch();
        }}
        data-testid="active-error"
      />
    ) : (
      <LoadingState variant="table" rows={8} slowHint="wait" data-testid="active-loading" />
    );
  } else if (instances.length === 0) {
    pageState = 'empty';
    body = (
      <EmptyState
        title={t('active:page.empty.noInstance.title')}
        description={t('active:page.empty.noInstance.description')}
        actions={[
          {
            label: t('active:page.empty.noInstance.instances'),
            onClick: () => {
              const path = screenPath(screenById('instances'), appId);
              const carried = carriedQuery(state);
              void navigate(carried === '' ? path : `${path}?${carried}`);
            },
            'data-testid': 'active-empty-instances',
          },
        ]}
        data-testid="active-empty"
      />
    );
  } else {
    pageState = 'ready';
    const idle = items.length === 0 && summary.total === 0;
    body = (
      <>
        <section className="active-page__bars" aria-label={t('active:bars.label')}>
          <div className="active-page__bars-head">
            <BandLegend thresholds={thresholds} totals={bandTotals} />
            <ThresholdSettings thresholds={thresholds} onApply={applyThresholds} />
          </div>
          <InstanceBars
            instances={instances}
            bands={bands}
            thresholds={thresholds}
            selected={instanceSelection}
            onToggle={toggleInstance}
            {...(instanceLink === undefined ? {} : { instanceHref: instanceLink })}
          />
        </section>
        <div
          className="segmented active-page__states"
          role="group"
          aria-label={t('active:state.filterLabel')}
          data-testid="active-state-filter"
        >
          {[null, ...ACTIVE_STATES].map((code) => {
            const checked = currentState === code;
            return (
              <button
                key={code ?? 'all'}
                type="button"
                className={`segmented__option${checked ? ' segmented__option--checked' : ''}`}
                aria-pressed={checked}
                onClick={() => {
                  chooseState(code);
                }}
                data-testid={`active-state-filter-${code ?? 'all'}`}
              >
                {code === null ? t('active:state.all') : t(STATE_LABEL_KEYS[code])}
              </button>
            );
          })}
        </div>
        {filtered.ignored.length > 0 ? (
          <p className="active-page__notice" data-testid="active-ignored-filters">
            {t('active:page.ignoredFilters', {
              fields: [...new Set(filtered.ignored.map((f) => f.field))].join(', '),
            })}
          </p>
        ) : null}
        {selectedItem === null ? null : (
          <RowActions
            appId={appId}
            item={selectedItem}
            running={selectedNow !== null}
            current={state}
            onClear={() => {
              setSelection(null);
            }}
          />
        )}
        {idle ? (
          <EmptyState
            compact
            title={t('active:page.empty.idle.title')}
            description={t('active:page.empty.idle.description')}
            data-testid="active-empty"
          />
        ) : (
          <div data-testid="active-table">
            <DataTable<ActiveItem>
              tableId="active"
              caption={t('active:table.caption')}
              columns={columns}
              rows={rows}
              rowKey={itemKey}
              sort={sort}
              onSortChange={onSortChange}
              dataComplete
              filters={filters}
              onFiltersChange={onFiltersChange}
              onCellFilter={onCellFilter}
              onRowOpen={(item) => {
                setSelection({ key: itemKey(item), item });
              }}
              empty={{
                title: t('active:page.empty.filtered.title'),
                description: t('active:page.empty.filtered.description'),
                onClearFilters: clearFilters,
                actions: otherKinds
                  ? [
                      {
                        label: t('active:page.empty.filtered.allKinds'),
                        onClick: () => {
                          set({ kind: 'ALL' }, { replace: false });
                        },
                        'data-testid': 'active-empty-all-kinds',
                      },
                    ]
                  : [],
              }}
              rowAttributes={(item) => ({
                'data-testid': `active-row-${item.tx_id}`,
                'data-elapsed-band': String(bandOf(item.elapsed_ms, thresholds)),
                'data-selected': selection?.key === itemKey(item) ? 'true' : undefined,
              })}
            />
          </div>
        )}
      </>
    );
  }

  // The server lists at most 2,000 items (meta.truncated on REST; a push has no meta).
  const listTruncated = items.length >= ACTIVE_MAX_ITEMS;

  return (
    <section
      className="active-page"
      data-testid="active-page"
      data-state={pageState}
      data-paused={String(paused)}
      data-source={shown?.source}
      data-snapshot-at={data?.snapshot_at ?? undefined}
      aria-labelledby="active-title"
    >
      <h1 id="active-title" className="visually-hidden">
        {t('active:page.title')}
      </h1>
      <header className="active-page__head">
        {appId === null || shown === null ? null : (
          <ActiveSummary
            appId={appId}
            summary={summary}
            instances={instanceMap}
            thresholds={thresholds}
            current={state}
          />
        )}
        <div className="active-page__live">
          <span
            className={`active-asof${asOf !== null && asOf > AS_OF_WARN_S ? ' active-asof--warn' : ''}`}
            title={t('active:asOf.title')}
            data-seconds={asOf ?? undefined}
            data-testid="active-asof"
          >
            {asOfText}
          </span>
          <button
            type="button"
            className="control-button"
            aria-pressed={paused}
            disabled={shown === null}
            title={t('active:pause.hint')}
            onClick={() => {
              setPaused(!paused);
            }}
            data-testid="active-pause"
          >
            {paused ? t('active:pause.resume') : t('active:pause.pause')}
          </button>
          {staleSeconds === null ? null : (
            <span
              className="active-stale"
              role="status"
              title={t('active:stale.title')}
              data-seconds={staleSeconds}
              data-testid="active-stale-badge"
            >
              {t('active:stale.badge', { seconds: staleSeconds })}
            </span>
          )}
          {polling ? (
            <span className="active-muted" data-testid="active-polling">
              {t('active:asOf.polling')}
            </span>
          ) : null}
        </div>
      </header>
      {listTruncated ? (
        <p className="active-page__notice" data-testid="active-truncated">
          {t('active:page.truncatedList', { count: items.length })}
        </p>
      ) : null}
      {body}
    </section>
  );
}
