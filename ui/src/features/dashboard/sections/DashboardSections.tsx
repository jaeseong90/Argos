// SPDX-License-Identifier: Apache-2.0
//
// The lower part of the dashboard (docs/15 §9.2 below the charts, 05 §2.3-3..6): instance tiles,
// the instance comparison table, the firing alerts panel and the collection status panel.
//
// Data: the dashboard model of the page (instances with the live per-instance active counts and
// states merged, firing, collection), `GET /apps/{appId}/instances/compare` for the same range
// and kind (heap and CPU of the tiles, the table with the server's outlier flags, ADR-0010 C-177)
// and, only when requests were excluded, `GET /collection-health` for the reasons of the
// "N excluded" tooltip. The table sort is URL state (`sort=`); filters go through the page
// (f.instance, the shell's chips).
import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { Enveloped } from '../../../api/client';
import {
  compareInstances,
  excludedReasons,
  exportCompareCsv,
  type DashboardKind,
  type ExcludedReasons,
  type InstanceComparison,
} from '../../../api/dashboard';
import { errorInfo, isApiError } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import type { SortSpec } from '../../../components/DataTable';
import { ErrorState, type ApiErrorInfo } from '../../../components/ErrorState';
import type { ResolvedRange } from '../../../state/timeRange';
import { DashboardQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { alertHistoryUrl, instanceDetailUrl } from '../data/links';
import type { DashboardModel } from '../data/model';

import { FiringPanel } from './FiringPanel';
import { IngestStatusPanel } from './IngestStatusPanel';
import { InstanceCompareTable } from './InstanceCompareTable';
import { InstanceTiles } from './InstanceTiles';
import { compareSort, compareSortParam, instanceTiles, liveCompareRows } from './sectionsModel';

import './sections.css';

export interface DashboardSectionsProps {
  readonly appId: number;
  readonly model: DashboardModel;
  /** The absolute range of the dashboard request (the comparison asks the same range). */
  readonly range: ResolvedRange;
  readonly kind: DashboardKind;
  /** Realtime applies (live or kiosk on a range that ends now). */
  readonly realtime: boolean;
  /**
   * Adds f.instance=<id> (eq, the dashboard asks again for that instance) or f.instance=!<id>
   * (ne) from the comparison table's value filter.
   */
  readonly onFilterInstance: (instanceId: number, op?: 'eq' | 'ne') => void;
}

type CompareKey = ReturnType<typeof queryKeys.screen>;

export function DashboardSections({
  appId,
  model,
  range,
  kind,
  realtime,
  onFilterInstance,
}: DashboardSectionsProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  const { state, set } = useUrlState(DashboardQ);

  // The comparison of the same range and kind; the previous answer stays while the next loads
  // (same application only).
  const compare = useQuery<
    Enveloped<InstanceComparison[]>,
    Error,
    Enveloped<InstanceComparison[]>,
    CompareKey
  >({
    queryKey: queryKeys.screen('dashboard-compare', { appId, range, kind }),
    queryFn: ({ signal }) =>
      compareInstances({ appId, from: range.from, to: range.to, kind }, signal),
    enabled: appId > 0,
    placeholderData: (previous, query) => (query?.queryKey[1] === appId ? previous : undefined),
  });

  const excluded = model.collection.excluded;
  const reasons = useQuery<ExcludedReasons>({
    queryKey: queryKeys.screen('dashboard-excluded', { appId, range }),
    queryFn: ({ signal }) => excludedReasons({ appId, from: range.from, to: range.to }, signal),
    enabled: appId > 0 && excluded !== null && excluded > 0,
  });

  const compareRows = compare.data?.data ?? null;
  const tiles = useMemo(
    () => instanceTiles(model.instances, compareRows),
    [model.instances, compareRows],
  );
  const rows = useMemo(
    () => liveCompareRows(compareRows ?? [], model.instances, realtime),
    [compareRows, model.instances, realtime],
  );

  const time = state.time;
  const live = state.live;
  const env = state.env;
  const kiosk = state.kiosk;
  const urlKind = state.kind;
  const detailHref = useCallback(
    (instanceId: number) =>
      instanceDetailUrl(appId, instanceId, { time, live, kind: urlKind, env, kiosk }),
    [appId, time, live, urlKind, env, kiosk],
  );
  const historyHref = useCallback(
    (alertId: string | null) => alertHistoryUrl(appId, { time, kind: urlKind, env }, alertId),
    [appId, time, urlKind, env],
  );

  const sort = compareSort(state.sort);
  const onSortChange = useCallback(
    (next: SortSpec | null) => {
      set({ sort: compareSortParam(next) }, { replace: true });
    },
    [set],
  );

  // CSV: the server export of the same question (DSN-15-06); a failure shows the error state.
  const [csvBusy, setCsvBusy] = useState(false);
  const [csvError, setCsvError] = useState<ApiErrorInfo | null>(null);
  const onExportCsv = useCallback(() => {
    setCsvBusy(true);
    setCsvError(null);
    exportCompareCsv({ appId, from: range.from, to: range.to, kind })
      .catch((error: unknown) => {
        setCsvError(isApiError(error) ? errorInfo(error) : { status: 0 });
      })
      .finally(() => {
        setCsvBusy(false);
      });
  }, [appId, range.from, range.to, kind]);

  const comparePending = compareRows === null && !compare.isError;
  return (
    <div
      className="dashboard-sections"
      data-testid="dashboard-sections"
      data-instance-count={model.instances.length}
    >
      <section className="dashboard-instances" aria-labelledby="dashboard-instances-title">
        <h2 id="dashboard-instances-title" className="dashboard-sections__title">
          {t('dashboard:sections.instances')}
        </h2>
        <InstanceTiles tiles={tiles} detailHref={detailHref} metricsPending={comparePending} />
      </section>
      {csvError === null ? null : (
        <ErrorState compact error={csvError} data-testid="dashboard-compare-csv-error" />
      )}
      <InstanceCompareTable
        rows={rows}
        sort={sort}
        onSortChange={onSortChange}
        onInstanceFilter={onFilterInstance}
        onExportCsv={onExportCsv}
        csvBusy={csvBusy}
        loading={comparePending || compare.isFetching}
        error={compare.isError ? errorInfo(compare.error) : null}
        onRetry={() => {
          void compare.refetch();
        }}
      />
      <div className="dashboard-sections__panels">
        <FiringPanel firing={model.firing} historyHref={historyHref} />
        <IngestStatusPanel
          collection={model.collection}
          excludedReasons={reasons.data ?? null}
          excludedReasonsPending={reasons.isPending && reasons.fetchStatus !== 'idle'}
        />
      </div>
    </div>
  );
}
