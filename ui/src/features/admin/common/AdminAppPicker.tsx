// SPDX-License-Identifier: Apache-2.0
//
// Application choice of the administration screens (instances, tokens; docs/15 §9.10): a native
// select over every application ("name · env"), written to the screen's `app=` URL key. Native so
// that the keyboard and assistive technology work without a popup; application names are entered
// strings and become option text only (React sets textContent, never markup).
import { useId } from 'react';
import { useTranslation } from 'react-i18next';

import type { App } from '../../../api/apps';

import './admin.css';

export interface AdminAppPickerProps {
  readonly apps: readonly App[];
  readonly value: number | null;
  readonly onChange: (appId: number) => void;
  readonly disabled?: boolean;
}

/** Option text of an application: name and environment (text only). */
export function appOptionText(app: Pick<App, 'name' | 'env'>): string {
  return `${String(app.name)} · ${String(app.env)}`;
}

export function AdminAppPicker({ apps, value, onChange, disabled = false }: AdminAppPickerProps) {
  const { t } = useTranslation(['common', 'admin']);
  const id = useId();
  const known = value !== null && apps.some((app) => app.app_id === value);
  return (
    <span className="admin-field admin-field--inline">
      <label className="admin-field__label" htmlFor={id}>
        {t('admin:common.application')}
      </label>
      <select
        id={id}
        className="admin-select"
        value={known ? String(value) : ''}
        disabled={disabled}
        onChange={(event) => {
          const next = Number(event.target.value);
          if (Number.isInteger(next) && next > 0) {
            onChange(next);
          }
        }}
        data-testid="admin-app-select"
      >
        {known ? null : <option value="">{t('admin:common.chooseApplication')}</option>}
        {apps.map((app) => (
          <option key={app.app_id} value={String(app.app_id)}>
            {appOptionText(app)}
          </option>
        ))}
      </select>
    </span>
  );
}
