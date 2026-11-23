// SPDX-License-Identifier: Apache-2.0
//
// "Clone to another application" (05 §2.4-4, docs/14 §4.13 POST /alerts/rules/{id}/clone): the
// target application and environment (every environment when none is chosen); the server copies
// the rule. Server rules cannot be cloned (the action is not offered for them).
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { alertRuleKeys, cloneAlertRule, type AlertRule } from '../../../api/alertRules';
import type { App } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { AppSelect, EnvSelect  } from '../../../routes/Shell';
import { AlertDialog } from '../common/AlertDialog';

export interface CloneRuleDialogProps {
  /** The rule to clone; null keeps the dialog closed. */
  readonly rule: AlertRule | null;
  readonly apps: readonly App[];
  readonly onClose: () => void;
  readonly onCloned: (rule: AlertRule) => void;
}

export function CloneRuleDialog({ rule, apps, onClose, onCloned }: CloneRuleDialogProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const queryClient = useQueryClient();
  const [appId, setAppId] = useState<number | null>(null);
  const [env, setEnv] = useState<string | null>(null);
  const clone = useMutation({
    mutationFn: (target: { app: number; env: string | null }) =>
      cloneAlertRule(rule?.rule_id ?? '', target),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: alertRuleKeys.all() });
      setAppId(null);
      setEnv(null);
      onCloned(created);
    },
  });
  const envs: string[] = [...new Set<string>(apps.map((app) => app.env))];
  const close = (): void => {
    clone.reset();
    setAppId(null);
    setEnv(null);
    onClose();
  };
  return (
    <AlertDialog
      open={rule !== null}
      title={t('alerts:rules.clone.title')}
      onClose={close}
      data-testid="alert-rule-clone-modal"
      footer={
        <>
          <button type="button" className="control-button" onClick={close}>
            {t('alerts:rules.actions.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={appId === null || clone.isPending}
            data-testid="alert-rule-clone-submit"
            onClick={() => {
              if (appId !== null) {
                clone.mutate({ app: appId, env });
              }
            }}
          >
            {t('alerts:rules.clone.submit')}
          </button>
        </>
      }
    >
      {rule === null ? null : (
        <div className="alert-rule-dialog">
          <p>
            {t('alerts:rules.clone.description')}{' '}
            <SafeText value={rule.name} className="alert-rule-dialog__name" />
          </p>
          <span className="alert-rule-dialog__label">{t('alerts:rules.clone.app')}</span>
          <span className="alert-rule-select" data-testid="alert-rule-clone-app">
            <AppSelect apps={apps} value={appId} onChange={setAppId} />
          </span>
          <span className="alert-rule-dialog__label">{t('alerts:rules.clone.env')}</span>
          <span className="alert-rule-select" data-testid="alert-rule-clone-env">
            <EnvSelect envs={envs} value={env} onChange={setEnv} />
          </span>
          {clone.isError ? (
            <ErrorState
              compact
              error={errorInfo(clone.error)}
              data-testid="alert-rule-clone-error"
            />
          ) : null}
        </div>
      )}
    </AlertDialog>
  );
}
