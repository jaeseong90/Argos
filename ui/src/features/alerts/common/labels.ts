// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the alert screens' closed lists (docs/15 §12, T-UI-008): transition
// reasons (ADR-0010 C-227 (3)), target types, delivery states, channel types, delivery and
// destination error codes. Every key is written out (no key built from data), so the i18n check
// sees each one and an unknown value falls back to a fixed "unknown" text, never to the raw code.
import type { TFunction } from 'i18next';

import type {
  AlertDelivery,
  AlertReason,
  AlertTarget,
  ChannelType,
  DestinationReason,
} from '../../../api/alerts';

type T = TFunction<['common', 'alerts']>;

/** The reason of a state transition (null: an old row without a reason, shown as '—'). */
export function reasonLabel(t: T, reason: AlertReason): string {
  switch (reason) {
    case 'condition':
      return t('alerts:common.reason.condition');
    case 'cleared':
      return t('alerts:common.reason.cleared');
    case 'escalated':
      return t('alerts:common.reason.escalated');
    case 'acked':
      return t('alerts:common.reason.acked');
    case 'resolved':
      return t('alerts:common.reason.resolved');
    case 'target_gone':
      return t('alerts:common.reason.targetGone');
    case 'rule_disabled':
      return t('alerts:common.reason.ruleDisabled');
    case 'rule_deleted':
      return t('alerts:common.reason.ruleDeleted');
    case 'no_data':
      return t('alerts:common.reason.noData');
    case 'data_returned':
      return t('alerts:common.reason.dataReturned');
  }
}

export function targetTypeLabel(t: T, type: AlertTarget['type']): string {
  switch (type) {
    case 'app':
      return t('alerts:common.targetType.app');
    case 'instance':
      return t('alerts:common.targetType.instance');
    case 'service':
      return t('alerts:common.targetType.service');
    case 'ext_host':
      return t('alerts:common.targetType.extHost');
    case 'server':
      return t('alerts:common.targetType.server');
  }
}

export function deliveryStatusLabel(t: T, status: AlertDelivery['status']): string {
  switch (status) {
    case 'pending':
      return t('alerts:common.delivery.pending');
    case 'sent':
      return t('alerts:common.delivery.sent');
    case 'failed':
      return t('alerts:common.delivery.failed');
    case 'dropped':
      return t('alerts:common.delivery.dropped');
  }
}

export function channelTypeLabel(t: T, type: ChannelType): string {
  switch (type) {
    case 'webhook':
      return t('alerts:common.channelType.webhook');
    case 'slack':
      return t('alerts:common.channelType.slack');
    case 'email':
      return t('alerts:common.channelType.email');
  }
}

export function severityLabel(t: T, severity: 'warn' | 'critical' | null): string {
  if (severity === 'critical') {
    return t('component.severity.critical');
  }
  if (severity === 'warn') {
    return t('component.severity.warn');
  }
  return t('alerts:common.none');
}

/**
 * A delivery or test-send error code (server DeliveryException codes: fixed codes, never a
 * response body); an unknown code says "delivery failed" with the code itself as plain text.
 */
export function deliveryErrorLabel(t: T, code: string): string {
  switch (code) {
    case 'connect_timeout':
      return t('alerts:common.deliveryError.connectTimeout');
    case 'connect_failed':
      return t('alerts:common.deliveryError.connectFailed');
    case 'tls_failed':
      return t('alerts:common.deliveryError.tlsFailed');
    case 'timeout':
      return t('alerts:common.deliveryError.timeout');
    case 'io_error':
      return t('alerts:common.deliveryError.ioError');
    case 'protocol_error':
      return t('alerts:common.deliveryError.protocolError');
    case 'redirect':
      return t('alerts:common.deliveryError.redirect');
    case 'http_status':
      return t('alerts:common.deliveryError.httpStatus');
    case 'starttls_unavailable':
      return t('alerts:common.deliveryError.starttlsUnavailable');
    case 'auth_failed':
      return t('alerts:common.deliveryError.authFailed');
    case 'smtp_rejected':
      return t('alerts:common.deliveryError.smtpRejected');
    case 'recipient_not_allowed':
      return t('alerts:common.deliveryError.recipientNotAllowed');
    case 'smtp_not_configured':
      return t('alerts:common.deliveryError.smtpNotConfigured');
    case 'destination_blocked':
      return t('alerts:common.deliveryError.destinationBlocked');
    case 'secret_unavailable':
      return t('alerts:common.deliveryError.secretUnavailable');
    case 'no_signing_key':
      return t('alerts:common.deliveryError.noSigningKey');
    default:
      return t('alerts:common.deliveryError.other', { code });
  }
}

/** Why a channel destination was refused (422 webhook_destination_blocked, docs/16 §8.1). */
export function destinationReasonLabel(t: T, reason: DestinationReason | 'blocked'): string {
  switch (reason) {
    case 'scheme':
      return t('alerts:channels.blocked.scheme');
    case 'userinfo':
      return t('alerts:channels.blocked.userinfo');
    case 'invalid_url':
      return t('alerts:channels.blocked.invalidUrl');
    case 'dns':
      return t('alerts:channels.blocked.dns');
    case 'own_address':
      return t('alerts:channels.blocked.ownAddress');
    case 'loopback':
      return t('alerts:channels.blocked.loopback');
    case 'private':
      return t('alerts:channels.blocked.private');
    case 'link_local':
      return t('alerts:channels.blocked.linkLocal');
    case 'unique_local':
      return t('alerts:channels.blocked.uniqueLocal');
    case 'shared_address':
      return t('alerts:channels.blocked.sharedAddress');
    case 'multicast':
    case 'broadcast':
    case 'reserved':
    case 'unspecified':
    case 'this_network':
    case 'documentation':
    case 'benchmarking':
    case 'protocol_assignment':
      return t('alerts:channels.blocked.specialPurpose');
    case 'blocked':
      return t('alerts:channels.blocked.other');
  }
}
