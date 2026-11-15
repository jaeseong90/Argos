// SPDX-License-Identifier: Apache-2.0
//
// Instance / app status badge (docs/15 §6.4, C-8, T-UI-008). The label text is always shown next to
// the coloured dot, so the colour never carries the meaning alone (docs/15 §13, WO-04 C-9).
// Before WO-22 the API reports only UP and DOWN (C-160); STALE and WARN render the same way once
// the server sends them.
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';

import './badges.css';

/** Status values of docs/14 (overview status, instance state). */
export const INSTANCE_STATUSES = ['UP', 'STALE', 'DOWN', 'WARN'] as const;
export type InstanceStatus = (typeof INSTANCE_STATUSES)[number];

/** Reasons the server gives for WARN (docs/15 §6.4: clock skew, drops, outdated agent, module off). */
export const WARN_REASONS = ['clock_skew', 'drops', 'outdated_agent', 'module_disabled'] as const;
export type WarnReason = (typeof WARN_REASONS)[number];

const STATUS_CLASS: Record<InstanceStatus | 'UNKNOWN', string> = {
  UP: 'status-badge--up',
  STALE: 'status-badge--stale',
  DOWN: 'status-badge--down',
  WARN: 'status-badge--warn',
  UNKNOWN: 'status-badge--unknown',
};

/** Normalises an API status string (case-insensitive); anything else is UNKNOWN. */
export function normalizeStatus(value: string | null | undefined): InstanceStatus | 'UNKNOWN' {
  const upper = (value ?? '').trim().toUpperCase();
  return (INSTANCE_STATUSES as readonly string[]).includes(upper)
    ? (upper as InstanceStatus)
    : 'UNKNOWN';
}

export interface StatusBadgeProps {
  /** Status from the API (UP, STALE, DOWN, WARN; any case). */
  readonly status: string | null | undefined;
  /** WARN reasons for the tooltip; unknown reason codes are listed as "Other". */
  readonly warnReasons?: readonly string[];
  readonly 'data-testid'?: string;
}

/** Coloured dot plus the status label, with the C-8 explanation as tooltip. */
export function StatusBadge({ status, warnReasons = [], 'data-testid': testId }: StatusBadgeProps) {
  const { t } = useTranslation();
  const normalized = normalizeStatus(status);
  const label = {
    UP: t('component.status.up'),
    STALE: t('component.status.stale'),
    DOWN: t('component.status.down'),
    WARN: t('component.status.warn'),
    UNKNOWN: t('component.status.unknown'),
  }[normalized];
  const reasonLabel = (reason: string): string => {
    switch (reason.toLowerCase()) {
      case 'clock_skew':
        return t('component.status.warnReason.clockSkew');
      case 'drops':
        return t('component.status.warnReason.drops');
      case 'outdated_agent':
        return t('component.status.warnReason.outdatedAgent');
      case 'module_disabled':
        return t('component.status.warnReason.moduleDisabled');
      default:
        return t('component.status.warnReason.other');
    }
  };
  const description =
    normalized === 'STALE'
      ? t('component.status.tooltip.stale')
      : normalized === 'DOWN'
        ? t('component.status.tooltip.down')
        : normalized === 'UP'
          ? t('component.status.tooltip.up')
          : normalized === 'WARN'
            ? [
                t('component.status.tooltip.warn'),
                ...[...new Set(warnReasons.map(reasonLabel))],
              ].join(' · ')
            : t('component.status.tooltip.unknown');

  return (
    <Tooltip title={description}>
      <span
        className={`status-badge ${STATUS_CLASS[normalized]}`}
        data-status={normalized}
        data-testid={testId ?? 'status-badge'}
      >
        <span className="status-badge__dot" aria-hidden="true" />
        <span className="status-badge__label">{label}</span>
        <span className="visually-hidden">{description}</span>
      </span>
    </Tooltip>
  );
}
