// SPDX-License-Identifier: Apache-2.0
//
// DOM side of the XLog event markers (docs/15 §6.1, §7.2): the overlay canvas draws the line and
// the glyph of each marker group; this layer puts a focusable hit target over each glyph in the
// top margin, with a tooltip that lists the group's events (type, title, version, time). Titles
// and versions are collected strings and render through SafeText only (SEC-60). The `alert=`
// event (docs/15 §4.3 item 4) is marked with data-emphasized and named in the label.
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { MarkerGroup, MarkerType } from '../charts/chartModel';
import { SafeText } from '../components/SafeText';

import { PLOT_MARGIN } from './renderer';
import { useXLogFormat } from './useXLogFormat';

/** Hit target size, CSS px (the glyph is about 8-10 px; WCAG 2.5.8 spacing via grouping). */
const TARGET_PX = 16;
/** Events listed in one tooltip; the rest are counted. */
export const TOOLTIP_MAX_EVENTS = 10;

export interface XLogMarkerLayerProps {
  readonly groups: readonly MarkerGroup[];
}

function useMarkerTypeLabel(): (type: MarkerType) => string {
  const { t } = useTranslation(['common', 'xlog']);
  return (type) => {
    switch (type) {
      case 'deploy':
        return t('xlog:chart.markers.type.deploy');
      case 'start':
        return t('xlog:chart.markers.type.start');
      case 'stop':
        return t('xlog:chart.markers.type.stop');
      case 'config':
        return t('xlog:chart.markers.type.config');
      case 'alert':
        return t('xlog:chart.markers.type.alert');
      case 'state':
        return t('xlog:chart.markers.type.state');
      case 'apdex':
        return t('xlog:chart.markers.type.apdex');
      case 'custom':
        return t('xlog:chart.markers.type.custom');
    }
  };
}

export function XLogMarkerLayer({ groups }: XLogMarkerLayerProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const typeLabel = useMarkerTypeLabel();
  const [open, setOpen] = useState<string | null>(null);
  const baseId = useId();
  if (groups.length === 0) {
    return null;
  }
  const entries = groups.map((group, index) => ({
    group,
    // The lead event leads one group only; its id keeps the target (and an open tooltip) while
    // the realtime window moves the group.
    key: group.lead.id,
    tipId: `${baseId}-tip-${String(index)}`,
  }));
  return (
    <ul
      className="xlog-markers"
      data-testid="xlog-markers"
      aria-label={t('xlog:chart.markers.label')}
      style={{ left: PLOT_MARGIN.left, height: PLOT_MARGIN.top + 4 }}
    >
      {entries.map(({ group, key, tipId }) => {
        const lead = group.lead;
        const more = group.markers.length - 1;
        const label = [
          group.emphasized ? t('xlog:chart.markers.emphasized') : null,
          typeLabel(lead.type),
          format.time(lead.ts),
          more > 0 ? t('xlog:chart.markers.more', { count: more }) : null,
        ]
          .filter((part): part is string => part !== null)
          .join(', ');
        return (
          <li key={key} className="xlog-markers__item" style={{ left: group.left - TARGET_PX / 2 }}>
            <button
              type="button"
              className={`xlog-marker${group.emphasized ? ' xlog-marker--emphasized' : ''}`}
              aria-label={label}
              aria-describedby={open === key ? tipId : undefined}
              data-testid="xlog-marker"
              data-marker-id={lead.id}
              data-marker-type={lead.type}
              data-emphasized={group.emphasized ? 'true' : undefined}
              data-count={group.markers.length}
              onMouseEnter={() => {
                setOpen(key);
              }}
              onMouseLeave={() => {
                setOpen((current) => (current === key ? null : current));
              }}
              onFocus={() => {
                setOpen(key);
              }}
              onBlur={() => {
                setOpen((current) => (current === key ? null : current));
              }}
            />
            {open === key ? (
              <div
                id={tipId}
                role="tooltip"
                className="xlog-marker-tip"
                data-testid="xlog-marker-tip"
              >
                <ul className="xlog-marker-tip__list">
                  {group.markers.slice(0, TOOLTIP_MAX_EVENTS).map((marker) => (
                    <li key={marker.id}>
                      <strong>{typeLabel(marker.type)}</strong>{' '}
                      <SafeText value={marker.title ?? null} showTitle={false} />
                      {marker.version === undefined || marker.version === null ? null : (
                        <>
                          {' '}
                          <SafeText value={marker.version} showTitle={false} mono />
                        </>
                      )}{' '}
                      <span className="xlog-marker-tip__time">{format.time(marker.ts)}</span>
                    </li>
                  ))}
                </ul>
                {group.markers.length > TOOLTIP_MAX_EVENTS ? (
                  <p className="xlog-marker-tip__more">
                    {t('xlog:chart.markers.more', {
                      count: group.markers.length - TOOLTIP_MAX_EVENTS,
                    })}
                  </p>
                ) : null}
              </div>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
