// SPDX-License-Identifier: Apache-2.0
//
// Filters of the alert history (05 §2.9-3): application (or every application, 05 §2.9-6),
// severity, state, rule and "failed deliveries only". The range and the environment are the
// global ones of the top bar. Every filter is a URL key (AlertsQ) and a server parameter of
// GET /alerts/events; rule names are entered strings, so the rule options are plain text nodes.
import { useTranslation } from 'react-i18next';

import type { App } from '../../../api/apps';
import { AppSelect } from '../../../routes/Shell';
import type { AlertsState } from '../../../state/urlState';

/**
 * A rule of the filter. `name` is an entered string; an <option> can only hold text, which React
 * sets as a text node (never parsed as markup, SEC-60), so it is passed as plain text here.
 */
export interface RuleOption {
  readonly id: string;
  readonly name: string;
}

export interface HistoryFiltersProps {
  readonly state: AlertsState;
  readonly apps: readonly App[];
  readonly appsLoading: boolean;
  readonly rules: readonly RuleOption[];
  readonly set: (partial: Partial<AlertsState>) => void;
}

const STATE_OPTIONS = ['all', 'firing', 'acked', 'resolved', 'nodata', 'pending', 'ok'] as const;

export function HistoryFilters({ state, apps, appsLoading, rules, set }: HistoryFiltersProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const stateLabel = (value: (typeof STATE_OPTIONS)[number]): string => {
    switch (value) {
      case 'all':
        return t('alerts:history.filter.stateAll');
      case 'firing':
        return t('component.alertState.firing');
      case 'acked':
        return t('component.alertState.acked');
      case 'resolved':
        return t('component.alertState.resolved');
      case 'nodata':
        return t('component.alertState.noData');
      case 'pending':
        return t('component.alertState.pending');
      case 'ok':
        return t('component.alertState.ok');
    }
  };
  const ruleKnown = state.rule === undefined || rules.some((rule) => rule.id === state.rule);
  return (
    <div className="alerts-filters" role="group" aria-label={t('alerts:history.filter.label')}>
      <span className="alerts-filters__app">
        <AppSelect
          apps={apps}
          value={state.app ?? null}
          loading={appsLoading}
          onChange={(appId) => {
            set({ app: appId, rule: undefined });
          }}
        />
        {state.app === undefined ? (
          <span className="alerts-muted" data-testid="alerts-scope-all">
            {t('alerts:history.filter.allApps')}
          </span>
        ) : (
          <button
            type="button"
            className="control-button"
            onClick={() => {
              set({ app: undefined, rule: undefined });
            }}
            data-testid="alerts-filter-all-apps"
          >
            {t('alerts:history.filter.showAllApps')}
          </button>
        )}
      </span>
      <label className="alerts-control">
        <span className="alerts-control__label">{t('alerts:history.filter.severity')}</span>
        <select
          className="alerts-select"
          value={state.severity}
          onChange={(event) => {
            const value = event.target.value;
            if (value === 'all' || value === 'warn' || value === 'critical') {
              set({ severity: value });
            }
          }}
          data-testid="alerts-filter-severity"
        >
          <option value="all">{t('alerts:history.filter.severityAll')}</option>
          <option value="critical">{t('component.severity.critical')}</option>
          <option value="warn">{t('component.severity.warn')}</option>
        </select>
      </label>
      <label className="alerts-control">
        <span className="alerts-control__label">{t('alerts:history.filter.state')}</span>
        <select
          className="alerts-select"
          value={state.state}
          onChange={(event) => {
            const value = STATE_OPTIONS.find((option) => option === event.target.value);
            if (value !== undefined) {
              set({ state: value });
            }
          }}
          data-testid="alerts-filter-state"
        >
          {STATE_OPTIONS.map((value) => (
            <option key={value} value={value}>
              {stateLabel(value)}
            </option>
          ))}
        </select>
      </label>
      <label className="alerts-control">
        <span className="alerts-control__label">{t('alerts:history.filter.rule')}</span>
        <select
          className="alerts-select alerts-select--rule"
          value={state.rule ?? ''}
          onChange={(event) => {
            const value = event.target.value;
            set({ rule: value === '' ? undefined : value });
          }}
          data-testid="alerts-filter-rule"
        >
          <option value="">{t('alerts:history.filter.ruleAll')}</option>
          {ruleKnown || state.rule === undefined ? null : (
            <option value={state.rule}>{t('alerts:history.filter.ruleUnknown')}</option>
          )}
          {rules.map((rule) => (
            <option key={rule.id} value={rule.id}>
              {rule.name}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        className={`control-button${state.failedOnly ? ' alerts-segment--on' : ''}`}
        aria-pressed={state.failedOnly}
        onClick={() => {
          set({ failedOnly: !state.failedOnly });
        }}
        data-testid="alerts-filter-failed-only"
      >
        {t('alerts:history.filter.failedOnly')}
      </button>
    </div>
  );
}
