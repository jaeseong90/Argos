// SPDX-License-Identifier: Apache-2.0
import { useLayoutEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import { placeTooltip } from './tooltipPlacement';
import { useXLogFormat } from './useXLogFormat';
import type { XLogDictionary, XLogPoint } from './XLogBuffer';

export interface XLogTooltipProps {
  readonly point: XLogPoint;
  readonly dictionary: XLogDictionary;
  /** Anchor (the hovered point) relative to the canvas container's padding box, CSS px. */
  readonly x: number;
  readonly y: number;
  /** Size of the container's padding box, CSS px (0 = unknown); the tooltip stays inside it. */
  readonly containerWidth: number;
  readonly containerHeight: number;
}

/**
 * Hover tooltip of one point (docs/05 §2.1: service, response time, status, instance). The service,
 * instance and error class names are collected data and appear as React text nodes only (SEC-60).
 * After every render the tooltip measures itself and is placed inside the container
 * (`placeTooltip`), before the browser paints.
 */
export function XLogTooltip({
  point,
  dictionary,
  x,
  y,
  containerWidth,
  containerHeight,
}: XLogTooltipProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const ref = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (element === null) {
      return;
    }
    const position = placeTooltip({
      x,
      y,
      width: element.offsetWidth,
      height: element.offsetHeight,
      containerWidth,
      containerHeight,
    });
    element.style.left = `${String(position.left)}px`;
    element.style.top = `${String(position.top)}px`;
  });

  const service = format.name(dictionary.services[point.service] ?? null, point.service);
  const instance = format.name(dictionary.instances[point.instance] ?? null, point.instance);
  const errorClass =
    point.errorClass === 0 || dictionary.errorClasses === null
      ? null
      : format.name(dictionary.errorClasses[point.errorClass - 1] ?? null, point.errorClass - 1);
  return (
    <div ref={ref} role="tooltip" data-testid="xlog-tooltip" className="xlog-tooltip">
      <div className="xlog-tooltip__service" data-testid="xlog-tooltip-service">
        {service}
      </div>
      <dl className="xlog-tooltip__fields">
        <dt>{t('xlog:tooltip.elapsed')}</dt>
        <dd data-testid="xlog-tooltip-elapsed">{format.duration(point.elapsed)}</dd>
        <dt>{t('xlog:tooltip.status')}</dt>
        <dd data-testid="xlog-tooltip-status">{format.status(point.status)}</dd>
        <dt>{t('xlog:tooltip.instance')}</dt>
        <dd data-testid="xlog-tooltip-instance">{instance}</dd>
        <dt>{t('xlog:tooltip.http')}</dt>
        <dd>{format.httpStatus(point.httpStatus)}</dd>
        {errorClass !== null && (
          <>
            <dt>{t('xlog:tooltip.errorClass')}</dt>
            <dd data-testid="xlog-tooltip-error-class">{errorClass}</dd>
          </>
        )}
        <dt>{t('xlog:tooltip.time')}</dt>
        <dd>{format.time(point.ts)}</dd>
      </dl>
    </div>
  );
}
