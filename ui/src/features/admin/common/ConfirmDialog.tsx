// SPDX-License-Identifier: Apache-2.0
//
// The confirmation dialog of the administration screens (docs/15 §9.10, WO-39 §1.4 `admin-confirm`):
// a title, what the action does (`admin-confirm-impact`), optional fields (a reason, a name typed
// again) and OK / Cancel. Cancel and Esc send nothing; OK runs the action and shows its error in
// place, so the dialog stays open on a refusal. Built on the shared modal (focus trap, Esc).
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { AlertDialog } from '../../alerts/common/AlertDialog';

import './admin.css';

export interface ConfirmDialogProps {
  readonly open: boolean;
  readonly title: string;
  /** What happens (who is affected, what stops); plain text built from trusted values. */
  readonly impact: ReactNode;
  readonly okLabel: string;
  readonly onOk: () => void;
  readonly onCancel: () => void;
  /** OK stays disabled until the fields are valid. */
  readonly okDisabled?: boolean;
  readonly pending?: boolean;
  readonly error?: unknown;
  readonly children?: ReactNode;
}

export function ConfirmDialog({
  open,
  title,
  impact,
  okLabel,
  onOk,
  onCancel,
  okDisabled = false,
  pending = false,
  error = null,
  children,
}: ConfirmDialogProps) {
  const { t } = useTranslation(['common', 'admin']);
  return (
    <AlertDialog
      open={open}
      size="narrow"
      title={title}
      onClose={onCancel}
      data-testid="admin-confirm"
      footer={
        <>
          <button
            type="button"
            className="control-button"
            onClick={onCancel}
            data-testid="admin-confirm-cancel"
          >
            {t('admin:common.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={okDisabled || pending}
            onClick={onOk}
            data-testid="admin-confirm-ok"
          >
            {pending ? t('admin:common.working') : okLabel}
          </button>
        </>
      }
    >
      <p className="admin-note" data-testid="admin-confirm-impact">
        {impact}
      </p>
      {children}
      {error === null || error === undefined ? null : (
        <ErrorState compact error={errorInfo(error)} data-testid="admin-confirm-error" />
      )}
    </AlertDialog>
  );
}
