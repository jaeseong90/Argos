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

/**
 * Reasons the server gives for WARN (docs/15 §6.4, docs/12 §4.2, ADR-0010 C-216 (2)): clock skew,
 * drops, outdated agent (`old_version`; `outdated_agent` is the older spelling), module disabled,
 * kill switch, configuration drift, context leak.
 */
export const WARN_REASONS = [
  'clock_skew',
  'drops',
  'old_version',
  'outdated_agent',
  'module_disabled',
  'kill_switch',
  'config_drift',
  'context_leak',
] as const;
export type WarnReason = (typeof WARN_REASONS)[number];

/** The i18n keys (namespace common) of the WARN reason labels. */
export type WarnReasonKey =
  | 'component.status.warnReason.clockSkew'
  | 'component.status.warnReason.drops'
  | 'component.status.warnReason.outdatedAgent'
  | 'component.status.warnReason.moduleDisabled'
  | 'component.status.warnReason.killSwitch'
  | 'component.status.warnReason.configDrift'
  | 'component.status.warnReason.contextLeak'
  | 'component.status.warnReason.other';

/** The i18n key (namespace common) of a WARN reason code; unknown codes are "Other". */
export function warnReasonKey(reason: string): WarnReasonKey {
  switch (reason.trim().toLowerCase()) {
    case 'clock_skew':
      return 'component.status.warnReason.clockSkew';
    case 'drops':
      return 'component.status.warnReason.drops';
    case 'old_version':
    case 'outdated_agent':
      return 'component.status.warnReason.outdatedAgent';
    case 'module_disabled':
      return 'component.status.warnReason.moduleDisabled';
    case 'kill_switch':
      return 'component.status.warnReason.killSwitch';
    case 'config_drift':
      return 'component.status.warnReason.configDrift';
    case 'context_leak':
      return 'component.status.warnReason.contextLeak';
    default:
      return 'component.status.warnReason.other';
  }
}

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
  const reasonLabel = (reason: string): string => t(warnReasonKey(reason));
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
