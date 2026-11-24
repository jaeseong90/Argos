// SPDX-License-Identifier: Apache-2.0
//
// Create and edit an application (docs/15 §9.10 `/admin/apps` "생성·편집 모달", 05 §2.11-1, WO-39
// `admin-app-editor`): name and environment (create only), group, Apdex T, the registration policy
// (environment default, automatic, approval; an edit shows whether the current value is the
// environment's default) and the recommended alert rules. The server checks everything again.
import { useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';

import {
  APP_LIMITS,
  applyRecommendedRules,
  createAdminApp,
  patchApp,
  type App,
  type AppPatchRequest,
} from '../../../api/admin/apps';
import { errorInfo, isApiError } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { AlertDialog } from '../../alerts/common/AlertDialog';

import '../common/admin.css';

type PolicyChoice = 'inherit' | 'auto' | 'approval';

/** C0 and C1 control characters and DEL, which the server refuses in names. */
export function hasControl(value: string): boolean {
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    if (code < 0x20 || (code >= 0x7f && code <= 0x9f)) {
      return true;
    }
  }
  return false;
}

/** A trimmed text value within `max` characters and without control characters, or null. */
export function textValue(raw: string, max: number, allowEmpty = false): string | null {
  const value = raw.trim();
  if ((!allowEmpty && value === '') || Array.from(value).length > max || hasControl(value)) {
    return null;
  }
  return value;
}

type FieldError = 'name' | 'env' | 'group' | 'apdex';

export interface AppEditorProps {
  /** null: create a new application. */
  readonly app: App | null;
  readonly onClose: () => void;
  /** `rulesFailed`: the application is saved but applying the recommended rules failed. */
  readonly onSaved: (app: App, rulesFailed: boolean) => void;
}

export function AppEditor({ app, onClose, onSaved }: AppEditorProps) {
  const { t } = useTranslation(['common', 'admin']);
  const creating = app === null;
  const [name, setName] = useState('');
  const [env, setEnv] = useState('prod');
  const [group, setGroup] = useState(app?.group === null || app === null ? '' : String(app.group));
  const [apdex, setApdex] = useState(String(app?.apdex_t_ms ?? APP_LIMITS.apdexDefault));
  const [policy, setPolicy] = useState<PolicyChoice>(
    app === null ? 'inherit' : app.registration_policy === 'approval' ? 'approval' : 'auto',
  );
  const [rules, setRules] = useState(creating);
  const [fieldError, setFieldError] = useState<FieldError | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState(false);

  const submit = (event?: SubmitEvent<HTMLFormElement>): void => {
    event?.preventDefault();
    if (pending) {
      return;
    }
    setError(null);
    setFieldError(null);
    const groupValue = textValue(group, APP_LIMITS.groupMax, true);
    if (groupValue === null) {
      setFieldError('group');
      return;
    }
    const apdexValue = Number(apdex);
    if (
      !Number.isInteger(apdexValue) ||
      apdexValue < APP_LIMITS.apdexMin ||
      apdexValue > APP_LIMITS.apdexMax
    ) {
      setFieldError('apdex');
      return;
    }
    let call: Promise<App>;
    if (creating) {
      const nameValue = textValue(name, APP_LIMITS.nameMax);
      if (nameValue === null) {
        setFieldError('name');
        return;
      }
      const envValue = textValue(env, APP_LIMITS.envMax);
      if (envValue === null) {
        setFieldError('env');
        return;
      }
      call = createAdminApp({
        name: nameValue,
        env: envValue,
        apdex_t_ms: apdexValue,
        ...(groupValue === '' ? {} : { group: groupValue }),
        ...(policy === 'inherit' ? {} : { registration_policy: policy }),
      });
    } else {
      const body: { -readonly [K in keyof AppPatchRequest]: AppPatchRequest[K] } = {};
      if (groupValue !== (app.group === null ? '' : String(app.group))) {
        body.group = groupValue;
      }
      if (apdexValue !== app.apdex_t_ms) {
        body.apdex_t_ms = apdexValue;
      }
      if (
        policy !== 'inherit' &&
        (policy !== app.registration_policy || app.registration_policy_inherited === true)
      ) {
        body.registration_policy = policy;
      }
      call = Object.keys(body).length === 0 ? Promise.resolve(app) : patchApp(app.app_id, body);
    }
    setPending(true);
    call
      .then(async (saved) => {
        if (rules && !saved.recommended_rules_applied) {
          try {
            await applyRecommendedRules(saved.app_id);
            onSaved({ ...saved, recommended_rules_applied: true }, false);
          } catch {
            onSaved(saved, true);
          }
          return;
        }
        onSaved(saved, false);
      })
      .catch((failure: unknown) => {
        setError(failure);
      })
      .finally(() => {
        setPending(false);
      });
  };

  const exists = isApiError(error) && error.status === 409;
  const fieldMessage =
    fieldError === null ? null : (
      <p className="admin-error" role="alert" data-testid="admin-app-field-error">
        {t(`admin:apps.editor.invalid.${fieldError}`)}
      </p>
    );

  return (
    <AlertDialog
      open
      title={creating ? t('admin:apps.editor.createTitle') : t('admin:apps.editor.editTitle')}
      onClose={onClose}
      data-testid="admin-app-editor"
      footer={
        <>
          <button
            type="button"
            className="control-button"
            onClick={onClose}
            data-testid="admin-app-editor-cancel"
          >
            {t('admin:common.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={pending}
            onClick={() => {
              submit();
            }}
            data-testid="admin-app-editor-save"
          >
            {pending ? t('admin:common.working') : t('admin:apps.editor.save')}
          </button>
        </>
      }
    >
      <form className="admin-page" onSubmit={submit} noValidate>
        {creating ? (
          <>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:apps.column.name')}</span>
              <input
                className="admin-input"
                type="text"
                autoComplete="off"
                spellCheck={false}
                maxLength={APP_LIMITS.nameMax}
                value={name}
                onChange={(e) => {
                  setName(e.target.value);
                }}
                data-testid="admin-app-field-name"
              />
            </label>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:apps.column.env')}</span>
              <input
                className="admin-input"
                type="text"
                autoComplete="off"
                spellCheck={false}
                maxLength={APP_LIMITS.envMax}
                value={env}
                onChange={(e) => {
                  setEnv(e.target.value);
                }}
                data-testid="admin-app-field-env"
              />
            </label>
          </>
        ) : null}
        <label className="admin-field">
          <span className="admin-field__label">{t('admin:apps.column.group')}</span>
          <input
            className="admin-input"
            type="text"
            autoComplete="off"
            spellCheck={false}
            maxLength={APP_LIMITS.groupMax}
            value={group}
            onChange={(e) => {
              setGroup(e.target.value);
            }}
            data-testid="admin-app-field-group"
          />
          <span className="admin-field__help">{t('admin:apps.editor.groupHelp')}</span>
        </label>
        <label className="admin-field">
          <span className="admin-field__label">{t('admin:apps.editor.apdex')}</span>
          <input
            className="admin-input"
            type="number"
            min={APP_LIMITS.apdexMin}
            max={APP_LIMITS.apdexMax}
            step={1}
            value={apdex}
            onChange={(e) => {
              setApdex(e.target.value);
            }}
            data-testid="admin-app-field-apdex_t_ms"
          />
          <span className="admin-field__help">{t('admin:apps.editor.apdexHelp')}</span>
        </label>
        <label className="admin-field">
          <span className="admin-field__label">{t('admin:apps.column.registration_policy')}</span>
          <select
            className="admin-select"
            value={policy}
            onChange={(e) => {
              setPolicy(e.target.value as PolicyChoice);
            }}
            data-testid="admin-app-field-registration_policy"
          >
            {creating ? <option value="inherit">{t('admin:apps.policy.inherit')}</option> : null}
            <option value="auto">{t('admin:apps.policy.auto')}</option>
            <option value="approval">{t('admin:apps.policy.approval')}</option>
          </select>
          {!creating && app.registration_policy_inherited === true ? (
            <span className="admin-field__help" data-testid="admin-app-policy-inherited">
              {t('admin:apps.editor.policyInherited')}
            </span>
          ) : null}
        </label>
        {creating || !app.recommended_rules_applied ? (
          <label className="admin-field admin-field--inline">
            <input
              type="checkbox"
              checked={rules}
              onChange={(e) => {
                setRules(e.target.checked);
              }}
              data-testid="admin-app-field-recommended_rules"
            />
            <span>{t('admin:apps.editor.recommendedRules')}</span>
          </label>
        ) : (
          <p className="admin-note">{t('admin:apps.editor.rulesApplied')}</p>
        )}
        {fieldMessage}
        {exists ? (
          <p className="admin-error" role="alert" data-testid="admin-app-exists">
            {t('admin:apps.editor.exists')}
          </p>
        ) : error === null ? null : (
          <ErrorState compact error={errorInfo(error)} data-testid="admin-app-editor-error" />
        )}
      </form>
    </AlertDialog>
  );
}
