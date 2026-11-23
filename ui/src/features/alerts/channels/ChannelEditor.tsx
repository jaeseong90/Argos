// SPDX-License-Identifier: Apache-2.0
//
// Create or edit a notification channel (docs/15 §9.9, 05 §2.4-5, docs/16 §8, SEC-51/52). The URL
// or the recipients are re-entered (never shown after saving: the list shows `target_masked`
// only). The server validates the destination: a refused URL answers 422
// webhook_destination_blocked with the reason, shown at once under the field. The typed target and
// secret live only in this dialog's state and are dropped with it (closing, saving).
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  CHANNEL_TYPES,
  createChannel,
  destinationBlockedReason,
  invalidField,
  updateChannel,
  type AlertChannel,
  type ChannelType,
  type CreateChannelRequest,
  type CreatedChannel,
  type UpdateChannelRequest,
} from '../../../api/alerts';
import { errorInfo, isApiError } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { AlertDialog } from '../common/AlertDialog';
import { channelTypeLabel, destinationReasonLabel } from '../common/labels';

export interface ChannelEditorProps {
  /** The channel to edit, or null to create one. */
  readonly channel: AlertChannel | null;
  readonly onClose: () => void;
  /** After a create: the new channel and the one-time signing secret (webhook) if generated. */
  readonly onCreated: (created: CreatedChannel) => void;
  readonly onUpdated: (channel: AlertChannel) => void;
}

/** Characters of a channel name at most (server ChannelService name check). */
const NAME_MAX = 128;

export function ChannelEditor({ channel, onClose, onCreated, onUpdated }: ChannelEditorProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const editing = channel !== null;
  const [type, setType] = useState<ChannelType>(channel?.type ?? 'webhook');
  const [name, setName] = useState<string>(channel?.name ?? '');
  const [target, setTarget] = useState('');
  const [secret, setSecret] = useState('');
  const [allowHttp, setAllowHttp] = useState<boolean>(channel?.allow_http ?? false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const urlTarget = type !== 'email';
  const blocked = destinationBlockedReason(error);
  const invalid = invalidField(error);
  const secretUnavailable = isApiError(error) && error.code === 'secret_unavailable';
  const canSave = name.trim() !== '' && (editing || target.trim() !== '') && !pending;

  const save = async (): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      if (channel === null) {
        const request: CreateChannelRequest = { type, name: name.trim(), target: target.trim() };
        if (urlTarget && allowHttp) {
          request.allow_http = true;
        }
        if (type === 'webhook' && secret !== '') {
          request.secret = secret;
        }
        const created = await createChannel(request);
        setTarget('');
        setSecret('');
        onCreated(created);
      } else {
        const request: UpdateChannelRequest = {};
        if (name.trim() !== channel.name) {
          request.name = name.trim();
        }
        if (target.trim() !== '') {
          request.target = target.trim();
        }
        if (urlTarget && allowHttp !== channel.allow_http) {
          request.allow_http = allowHttp;
        }
        if (Object.keys(request).length === 0) {
          onClose();
          return;
        }
        const updated = await updateChannel(channel.channel_id, request);
        setTarget('');
        onUpdated(updated);
      }
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };

  const invalidText = (): string | null => {
    if (invalid === null) {
      return null;
    }
    if (invalid.field === 'target') {
      switch (invalid.reason) {
        case 'required':
          return t('alerts:channels.invalid.targetRequired');
        case 'invalid_address':
          return t('alerts:channels.invalid.invalidAddress');
        case 'too_many':
          return t('alerts:channels.invalid.tooManyRecipients');
        case 'recipient_domain_not_allowed':
          return t('alerts:channels.invalid.domainNotAllowed');
        default:
          return t('alerts:channels.invalid.target');
      }
    }
    if (invalid.field === 'name') {
      return t('alerts:channels.invalid.name');
    }
    if (invalid.field === 'secret') {
      return t('alerts:channels.invalid.secret');
    }
    if (invalid.field === 'allow_http') {
      return t('alerts:channels.invalid.allowHttp');
    }
    return null;
  };
  const fieldError = invalidText();

  return (
    <AlertDialog
      open
      title={editing ? t('alerts:channels.editor.editTitle') : t('alerts:channels.editor.newTitle')}
      onClose={onClose}
      data-testid="alert-channel-editor"
      footer={
        <>
          <button type="button" className="control-button" onClick={onClose}>
            {t('alerts:common.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={!canSave}
            onClick={() => {
              void save();
            }}
            data-testid="alert-channel-save"
          >
            {t('alerts:channels.editor.save')}
          </button>
        </>
      }
    >
      <label className="alerts-field">
        <span className="alerts-field__label">{t('alerts:channels.field.type')}</span>
        <select
          className="alerts-select"
          value={type}
          disabled={editing}
          onChange={(event) => {
            const next = CHANNEL_TYPES.find((value) => value === event.target.value);
            if (next !== undefined) {
              setType(next);
              setError(null);
            }
          }}
          data-testid="alert-channel-field-type"
        >
          {CHANNEL_TYPES.map((value) => (
            <option key={value} value={value}>
              {channelTypeLabel(t, value)}
            </option>
          ))}
        </select>
      </label>
      <label className="alerts-field">
        <span className="alerts-field__label">{t('alerts:channels.field.name')}</span>
        <input
          className="alerts-input"
          type="text"
          value={name}
          maxLength={NAME_MAX}
          onChange={(event) => {
            setName(event.target.value);
          }}
          data-testid="alert-channel-field-name"
        />
      </label>
      {editing ? (
        <p className="alerts-field__help" data-testid="alert-channel-current-target">
          {t('alerts:channels.editor.currentTarget')}{' '}
          <SafeText value={channel.target_masked} mono />
        </p>
      ) : null}
      <label className="alerts-field">
        <span className="alerts-field__label">
          {type === 'email'
            ? t('alerts:channels.field.recipients')
            : t('alerts:channels.field.url')}
        </span>
        <input
          className="alerts-input"
          type={type === 'email' ? 'text' : 'url'}
          value={target}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => {
            setTarget(event.target.value);
            if (blocked !== null || invalid !== null) {
              setError(null);
            }
          }}
          aria-invalid={blocked !== null || fieldError !== null || undefined}
          aria-describedby="alert-channel-target-help"
          data-testid="alert-channel-field-target"
        />
      </label>
      <p id="alert-channel-target-help" className="alerts-field__help">
        {editing
          ? t('alerts:channels.editor.reenterHelp')
          : type === 'email'
            ? t('alerts:channels.editor.recipientsHelp')
            : t('alerts:channels.editor.urlHelp')}
      </p>
      {blocked === null ? null : (
        <p
          className="alerts-field__error"
          role="alert"
          data-testid="alert-channel-destination-error"
          data-code={blocked}
        >
          {destinationReasonLabel(t, blocked)}
        </p>
      )}
      {fieldError === null ? null : (
        <p className="alerts-field__error" role="alert" data-testid="alert-channel-field-error">
          {fieldError}
        </p>
      )}
      {type === 'webhook' && !editing ? (
        <label className="alerts-field">
          <span className="alerts-field__label">{t('alerts:channels.field.secret')}</span>
          <input
            className="alerts-input"
            type="password"
            value={secret}
            autoComplete="new-password"
            onChange={(event) => {
              setSecret(event.target.value);
            }}
            data-testid="alert-channel-field-secret"
          />
          <span className="alerts-field__help">{t('alerts:channels.editor.secretHelp')}</span>
        </label>
      ) : null}
      {urlTarget ? (
        <label className="alerts-checkbox">
          <input
            type="checkbox"
            checked={allowHttp}
            onChange={(event) => {
              setAllowHttp(event.target.checked);
            }}
            data-testid="alert-channel-field-allow_http"
          />
          <span>{t('alerts:channels.field.allowHttp')}</span>
        </label>
      ) : null}
      {secretUnavailable ? (
        <p
          className="alerts-field__error"
          role="alert"
          data-testid="alert-channel-secret-unavailable"
        >
          {t('alerts:channels.editor.secretUnavailable')}
        </p>
      ) : error !== null && blocked === null && fieldError === null ? (
        <ErrorState compact error={errorInfo(error)} data-testid="alert-channel-save-error" />
      ) : null}
    </AlertDialog>
  );
}
