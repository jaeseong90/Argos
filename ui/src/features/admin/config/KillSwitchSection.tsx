// SPDX-License-Identifier: Apache-2.0
//
// Kill switch of `/admin/config` (docs/15 §9.10 "킬 스위치 섹션(앱·인스턴스 단위, 영향 설명 모달,
// 결과·시각)", 05 §2.11-6, docs/14 §4.14): the scope (every instance of the application or one),
// the action (stop tracing / resume tracing; the default is the opposite of the application's
// current state) and a confirmation dialog that says what happens and takes the reason. Nothing is
// sent before OK. The result (state and time) shows here and the per-instance results open below
// like any configuration change; the server records it in the audit log.
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  configProblem,
  setKillSwitch,
  type AppConfig,
  type ConfigChange,
} from '../../../api/admin/config';
import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';

import { changeStatusLabel, configProblemLabel } from './labels';
import { instanceOptionText, killSwitchOn, reasonProblem } from './model';

export interface KillSwitchSectionProps {
  readonly appId: number;
  readonly config: AppConfig;
  /** The kill switch change was accepted: show its per-instance results. */
  readonly onChanged: (change: ConfigChange) => void;
}

type Action = 'on' | 'off';

export function KillSwitchSection({ appId, config, onChanged }: KillSwitchSectionProps) {
  const { t } = useTranslation(['common', 'admin']);
  const active = killSwitchOn(config);
  const instances = config.remote_allowed_by_instance;
  const [instanceId, setInstanceId] = useState<number | null>(null);
  const [chosen, setChosen] = useState<Action | null>(null);
  const action: Action = chosen ?? (active ? 'off' : 'on');
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<ConfigChange | null>(null);
  const reasonIssue = reasonProblem(reason);
  const scopeName = instances.find((instance) => instance.instance_id === instanceId)?.name;

  const run = async (): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      const change = await setKillSwitch(appId, {
        enabled: action === 'off',
        reason: reason.trim(),
        instanceIds: instanceId === null ? [] : [instanceId],
      });
      setConfirming(false);
      setReason('');
      setChosen(null);
      setResult(change);
      onChanged(change);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };

  const problem = error === null ? null : configProblem(error);
  return (
    <section
      className="admin-panel"
      aria-labelledby="admin-kill-switch-title"
      data-testid="admin-kill-switch"
      data-active={active ? 'true' : 'false'}
    >
      <h2 id="admin-kill-switch-title" className="admin-panel__title">
        {t('admin:killSwitch.title')}
      </h2>
      <p className="admin-note" data-testid="admin-kill-switch-state">
        {active ? t('admin:killSwitch.stateOn') : t('admin:killSwitch.stateOff')}
      </p>
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:killSwitch.scope')}</span>
          <select
            className="admin-select"
            value={instanceId === null ? '' : String(instanceId)}
            onChange={(event) => {
              const next = Number(event.target.value);
              setInstanceId(Number.isInteger(next) && next > 0 ? next : null);
            }}
            data-testid="admin-kill-switch-scope"
          >
            <option value="">{t('admin:killSwitch.scopeAll', { count: instances.length })}</option>
            {instances.map((instance) => (
              <option key={instance.instance_id} value={String(instance.instance_id)}>
                {instanceOptionText(instance)}
              </option>
            ))}
          </select>
        </label>
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:killSwitch.action')}</span>
          <select
            className="admin-select"
            value={action}
            onChange={(event) => {
              setChosen(event.target.value === 'off' ? 'off' : 'on');
            }}
            data-testid="admin-kill-switch-action"
          >
            <option value="on">{t('admin:killSwitch.actionOn')}</option>
            <option value="off">{t('admin:killSwitch.actionOff')}</option>
          </select>
        </label>
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
          data-testid="admin-kill-switch-toggle"
          data-action={action}
        >
          {action === 'on' ? t('admin:killSwitch.turnOn') : t('admin:killSwitch.turnOff')}
        </button>
      </div>
      {result === null ? null : (
        <p
          className="admin-note"
          role="status"
          data-testid="admin-kill-switch-result"
          data-status={result.status}
          data-change-id={result.change_id}
        >
          {t('admin:killSwitch.result', { status: changeStatusLabel(t, result.status) })}{' '}
          <TimeCell iso={result.applied_at ?? result.requested_at} />{' '}
          <span className="admin-muted">{t('admin:killSwitch.audited')}</span>
        </p>
      )}
      <ConfirmDialog
        open={confirming}
        title={
          action === 'on'
            ? t('admin:killSwitch.confirmOnTitle')
            : t('admin:killSwitch.confirmOffTitle')
        }
        impact={
          <>
            {scopeName === undefined ? (
              t('admin:killSwitch.scopeAllImpact', { count: instances.length })
            ) : (
              <>
                {t('admin:killSwitch.scopeOneImpact')} <SafeText value={scopeName} />
              </>
            )}{' '}
            {action === 'on' ? t('admin:killSwitch.impactOn') : t('admin:killSwitch.impactOff')}
          </>
        }
        okLabel={action === 'on' ? t('admin:killSwitch.turnOn') : t('admin:killSwitch.turnOff')}
        okDisabled={reasonIssue !== null}
        pending={pending}
        onOk={() => {
          void run();
        }}
        onCancel={() => {
          setConfirming(false);
          setReason('');
        }}
      >
        <label className="admin-field">
          <span className="admin-field__label">{t('admin:config.reason.label')}</span>
          <textarea
            className="admin-input"
            rows={2}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value);
            }}
            aria-invalid={reasonIssue === 'tooLong' || undefined}
            data-testid="admin-kill-switch-reason"
          />
        </label>
        {problem === null ? null : problem.kind === 'other' ? (
          <ErrorState compact error={errorInfo(error)} data-testid="admin-kill-switch-error" />
        ) : (
          <p className="admin-error" role="alert" data-testid="admin-kill-switch-error">
            {configProblemLabel(t, problem.kind, problem.key)}
          </p>
        )}
      </ConfirmDialog>
    </section>
  );
}
