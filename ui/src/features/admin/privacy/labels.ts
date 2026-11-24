// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the privacy screen's closed lists (docs/15 §12, T-UI-008): capture modes,
// job states, form problems, refusals, warnings and notices of a deletion job and the retention
// fields. Every key is written out; an unknown value gets a fixed "unknown" text.
import type { TFunction } from 'i18next';

import type { DeletionErrorKind, DeletionFormProblem } from '../../../api/admin/privacy';
import type { RetentionField } from '../../../api/admin/retention';

type T = TFunction<['common', 'admin']>;

/** A capture mode of an instance (ip, user_id, query string, SQL parameters). */
export function modeLabel(t: T, mode: string): string {
  switch (mode) {
    case 'full':
      return t('admin:privacy.mode.full');
    case 'masked':
      return t('admin:privacy.mode.masked');
    case 'off':
      return t('admin:privacy.mode.off');
    case 'plain':
      return t('admin:privacy.mode.plain');
    case 'hashed':
      return t('admin:privacy.mode.hashed');
    case 'whitelist':
      return t('admin:privacy.mode.whitelist');
    case 'partial':
      return t('admin:privacy.mode.partial');
    default:
      return t('admin:privacy.mode.unknown');
  }
}

export function jobStatusLabel(t: T, status: string): string {
  switch (status) {
    case 'queued':
      return t('admin:privacy.job.status.queued');
    case 'running':
      return t('admin:privacy.job.status.running');
    case 'done':
      return t('admin:privacy.job.status.done');
    case 'failed':
      return t('admin:privacy.job.status.failed');
    default:
      return t('admin:privacy.job.status.unknown');
  }
}

/** A warning or notice code of a deletion (e.g. ip_masked_scope). */
export function noticeLabel(t: T, code: string): string {
  switch (code) {
    case 'ip_masked_scope':
      return t('admin:privacy.notice.ipMaskedScope');
    case 'interrupted_by_restart':
      return t('admin:privacy.notice.interruptedByRestart');
    default:
      return t('admin:privacy.notice.other', { code });
  }
}

export function formProblemLabel(t: T, problem: DeletionFormProblem): string {
  switch (problem) {
    case 'valueRequired':
      return t('admin:privacy.delete.problem.valueRequired');
    case 'valueTooLong':
      return t('admin:privacy.delete.problem.valueTooLong');
    case 'ipInvalid':
      return t('admin:privacy.delete.problem.ipInvalid');
    case 'rangeRequired':
      return t('admin:privacy.delete.problem.rangeRequired');
    case 'rangeOrder':
      return t('admin:privacy.delete.problem.rangeOrder');
    case 'rangeTooLong':
      return t('admin:privacy.delete.problem.rangeTooLong');
  }
}

export function deletionErrorLabel(t: T, kind: DeletionErrorKind): string {
  switch (kind) {
    case 'rateLimited':
      return t('admin:privacy.delete.error.rateLimited');
    case 'valueInvalid':
      return t('admin:privacy.delete.error.valueInvalid');
    case 'rangeTooLong':
      return t('admin:privacy.delete.problem.rangeTooLong');
    case 'rangeOrder':
      return t('admin:privacy.delete.problem.rangeOrder');
    case 'appNotFound':
      return t('admin:privacy.delete.error.appNotFound');
    case 'queueFull':
      return t('admin:privacy.delete.error.queueFull');
    case 'other':
      return t('admin:privacy.delete.error.other');
  }
}

export function retentionFieldLabel(t: T, field: RetentionField): string {
  switch (field) {
    case 'xlog_days':
      return t('admin:retention.field.xlog');
    case 'profile_days':
      return t('admin:retention.field.profile');
    case 'stat_raw_days':
      return t('admin:retention.field.statRaw');
    case 'metric_raw_days':
      return t('admin:retention.field.metricRaw');
    case 'rollup_1m_days':
      return t('admin:retention.field.rollup1m');
    case 'rollup_1h_days':
      return t('admin:retention.field.rollup1h');
  }
}
