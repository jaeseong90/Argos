// SPDX-License-Identifier: Apache-2.0
//
// Visually hidden alternative of the XLog chart (docs/15 §13 "차트 대체 표", §6.1 "≤ 100행", WO-04
// C-12, docs/poc/xlog-ui.md 8): points per time bucket and status, for screen reader users. While
// realtime data streams in it is refreshed at a fixed pace, at most once per second (a throttle,
// not a debounce: continuous 100 ms batches still refresh it every second), and a batch alone never
// re-renders it.
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useXLogFormat } from '../../../xlog/useXLogFormat';
import type { XLogBuffer } from '../../../xlog/XLogBuffer';

import { bucketCounts } from './bucketCounts';

/** The table follows realtime data at most this often. */
export const ALT_TABLE_REFRESH_MS = 1_000;

export interface XLogAltTableProps {
  readonly buffer: XLogBuffer;
  readonly from: number;
  readonly to: number;
}

/**
 * The buffer's version, taken at most once per `intervalMs` (fixed-interval throttle): a change
 * after a quiet interval shows at once, changes within the interval show once at its end. The
 * buffer subscription only marks a timer; it never renders by itself.
 */
export function useThrottledBufferVersion(buffer: XLogBuffer, intervalMs: number): number {
  const [shown, setShown] = useState(() => buffer.version);
  const shownVersion = useRef(shown);
  const lastShownAt = useRef(Number.NEGATIVE_INFINITY);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const show = (): void => {
      timer = null;
      lastShownAt.current = Date.now();
      shownVersion.current = buffer.version;
      setShown(buffer.version);
    };
    const onChange = (): void => {
      if (timer !== null) {
        return;
      }
      const wait = lastShownAt.current + intervalMs - Date.now();
      if (wait <= 0) {
        show();
      } else {
        timer = setTimeout(show, wait);
      }
    };
    // A change between the render and this subscription is not lost.
    if (buffer.version !== shownVersion.current) {
      onChange();
    }
    const unsubscribe = buffer.subscribe(onChange);
    return () => {
      unsubscribe();
      if (timer !== null) {
        clearTimeout(timer);
      }
    };
  }, [buffer, intervalMs]);
  return shown;
}

export function XLogAltTable({ buffer, from, to }: XLogAltTableProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const version = useThrottledBufferVersion(buffer, ALT_TABLE_REFRESH_MS);
  // Realtime moves the range every second: the rows follow at the same pace.
  const rows = useMemo(
    () => (version < 0 ? [] : bucketCounts(buffer, from, to)),
    [buffer, version, from, to],
  );
  return (
    <table className="visually-hidden" data-testid="xlog-alt-table">
      <caption>{t('xlog:a11y.table.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">{t('xlog:a11y.table.time')}</th>
          <th scope="col">{t('xlog:status.normal')}</th>
          <th scope="col">{t('xlog:status.slow')}</th>
          <th scope="col">{t('xlog:status.error')}</th>
          <th scope="col">{t('xlog:a11y.table.max')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.from} data-testid="xlog-alt-table-row">
            <th scope="row">
              {t('xlog:a11y.table.range', {
                from: format.axisTime(row.from),
                to: format.axisTime(row.to),
              })}
            </th>
            <td>{format.count(row.normal)}</td>
            <td>{format.count(row.slow)}</td>
            <td>{format.count(row.error)}</td>
            <td>
              {row.normal + row.slow + row.error === 0
                ? t('xlog:value.none')
                : format.duration(row.maxElapsed)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
