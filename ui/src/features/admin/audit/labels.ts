// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the audit log's closed lists (docs/16 §11, T-UI-008): actor types and
// results. Event names (16 §11.1) stay as the stable codes they are, in a monospace cell.
import type { TFunction } from 'i18next';

import type { AuditRecord } from '../../../api/admin/audit';

type T = TFunction<['common', 'admin']>;

export function actorTypeLabel(t: T, type: AuditRecord['actor']['type']): string {
  switch (type) {
    case 'user':
      return t('admin:audit.actorType.user');
    case 'agent':
      return t('admin:audit.actorType.agent');
    case 'system':
      return t('admin:audit.actorType.system');
  }
}

export function auditResultLabel(t: T, result: AuditRecord['result']): string {
  switch (result) {
    case 'success':
      return t('admin:audit.result.success');
    case 'failure':
      return t('admin:audit.result.failure');
    case 'rejected':
      return t('admin:audit.result.rejected');
  }
}
