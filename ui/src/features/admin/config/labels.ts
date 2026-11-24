// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the configuration screen's closed lists (docs/15 §12, T-UI-008): change and
// per-instance result states, drift reasons, change kinds and the refusal messages. Every key is
// written out (no key built from data) so that the i18n check sees each one; an unknown value gets
// a fixed "unknown" text, never the raw code as a label.
import type { TFunction } from 'i18next';

import type { ConfigErrorKind } from '../../../api/admin/config';

import type { PreviewProblem } from './model';

type T = TFunction<['common', 'admin']>;

/** A change state (previewed, applying, applied, superseded). */
export function changeStatusLabel(t: T, status: string): string {
  switch (status) {
    case 'previewed':
      return t('admin:config.changeStatus.previewed');
    case 'applying':
      return t('admin:config.changeStatus.applying');
    case 'applied':
      return t('admin:config.changeStatus.applied');
    case 'superseded':
      return t('admin:config.changeStatus.superseded');
    default:
      return t('admin:config.changeStatus.unknown');
  }
}

/** A per-instance result (applied, partial, rejected, offline, pending). */
export function resultStatusLabel(t: T, status: string): string {
  switch (status) {
    case 'applied':
      return t('admin:config.resultStatus.applied');
    case 'partial':
      return t('admin:config.resultStatus.partial');
    case 'rejected':
      return t('admin:config.resultStatus.rejected');
    case 'offline':
      return t('admin:config.resultStatus.offline');
    case 'pending':
      return t('admin:config.resultStatus.pending');
    default:
      return t('admin:config.resultStatus.unknown');
  }
}

/** Why an instance does not run a server-held value. */
export function driftReasonLabel(t: T, reason: string): string {
  switch (reason) {
    case 'not_allowed':
      return t('admin:config.driftReason.notAllowed');
    case 'rejected':
      return t('admin:config.driftReason.rejected');
    case 'pending':
      return t('admin:config.driftReason.pending');
    case 'config_hash':
      return t('admin:config.driftReason.configHash');
    default:
      return t('admin:config.driftReason.unknown');
  }
}

/** The kind of a change (config, revert = a config change with `revert_of`, kill switch). */
export function changeKindLabel(
  t: T,
  change: { readonly kind: string; readonly revert_of: string | null },
): string {
  const kind = change.revert_of === null ? change.kind : 'revert';
  switch (kind) {
    case 'config':
      return t('admin:config.kind.config');
    case 'revert':
      return t('admin:config.kind.revert');
    case 'kill_switch':
      return t('admin:config.kind.killSwitch');
    default:
      return t('admin:config.kind.unknown');
  }
}

/** The message of a refused change request; `key` is the agent key the server named. */
export function configProblemLabel(t: T, kind: ConfigErrorKind, key: string | null): string {
  const k = key ?? '—';
  switch (kind) {
    case 'keyNotAllowed':
      return t('admin:config.error.keyNotAllowed', { key: k });
    case 'unknownKey':
      return t('admin:config.error.unknownKey', { key: k });
    case 'immutableKey':
      return t('admin:config.error.immutableKey', { key: k });
    case 'invalidValue':
      return t('admin:config.error.invalidValue', { key: k });
    case 'reduceMasking':
      return t('admin:config.error.reduceMasking', { key: k });
    case 'killSwitchKey':
      return t('admin:config.error.killSwitchKey');
    case 'invalidState':
      return t('admin:config.error.invalidState');
    case 'rateLimited':
      return t('admin:config.error.rateLimited');
    case 'forbidden':
      return t('admin:config.error.forbidden');
    case 'other':
      return t('admin:config.error.other');
  }
}

/** Why the preview button is disabled. */
export function previewProblemLabel(t: T, problem: PreviewProblem): string {
  switch (problem) {
    case 'noChanges':
      return t('admin:config.problem.noChanges');
    case 'tooManyKeys':
      return t('admin:config.problem.tooManyKeys');
    case 'noTargets':
      return t('admin:config.problem.noTargets');
    case 'tooManyTargets':
      return t('admin:config.problem.tooManyTargets');
    case 'required':
      return t('admin:config.problem.reasonRequired');
    case 'tooLong':
      return t('admin:config.problem.reasonTooLong');
  }
}
