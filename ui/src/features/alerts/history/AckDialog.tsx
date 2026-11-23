// SPDX-License-Identifier: Apache-2.0
//
// Acknowledge a firing alert with a note (05 §2.9-4, docs/14 §4.13 POST /alerts/events/{id}/ack).
// The note holds at most 512 characters and 1 KB (ADR-0010 C-226 (7)); a 409 says the alert is no
// longer firing (acknowledged by someone else or resolved) and refreshes the lists. On success the
// ['alerts'] prefix is invalidated, which refreshes the history, the pinned panel and the badge.
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ACK_NOTE_MAX_CHARS, ackAlert, isConflict } from '../../../api/alerts';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import type { Untrusted } from '../../../api/untrusted';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { AlertDialog } from '../common/AlertDialog';

import { alertTextFits } from './model';

export interface AckTarget {
  readonly eventId: string;
  readonly rule: Untrusted;
}

export interface AckDialogProps {
  readonly target: AckTarget | null;
  readonly onClose: () => void;
}

export function AckDialog({ target, onClose }: AckDialogProps) {
  return target === null ? null : (
    <AckDialogBody key={target.eventId} target={target} onClose={onClose} />
  );
}

function AckDialogBody({
  target,
  onClose,
}: {
  readonly target: AckTarget;
  readonly onClose: () => void;
}) {
  const { t } = useTranslation(['common', 'alerts']);
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const fits = alertTextFits(note);
  const mutation = useMutation({
    mutationFn: () => ackAlert(target.eventId, note),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.all() });
      onClose();
    },
    onError: (error) => {
      if (isConflict(error)) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.all() });
      }
    },
  });
  const conflict = mutation.isError && isConflict(mutation.error);
  return (
    <AlertDialog
      open
      title={t('alerts:history.ack.title')}
      onClose={onClose}
      data-testid="alerts-ack-modal"
      footer={
        <>
          <button type="button" className="control-button" onClick={onClose}>
            {t('alerts:common.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={!fits || mutation.isPending || conflict}
            onClick={() => {
              mutation.mutate();
            }}
            data-testid="alerts-ack-submit"
          >
            {t('alerts:history.ack.submit')}
          </button>
        </>
      }
    >
      <p className="alerts-dialog__subject">
        <SafeText value={target.rule} maxLines={2} />
      </p>
      <label className="alerts-field">
        <span className="alerts-field__label">{t('alerts:history.ack.note')}</span>
        <textarea
          className="alerts-textarea"
          value={note}
          maxLength={ACK_NOTE_MAX_CHARS}
          rows={4}
          onChange={(event) => {
            setNote(event.target.value);
          }}
          aria-describedby="alerts-ack-note-help"
          data-testid="alerts-ack-note"
        />
      </label>
      <p id="alerts-ack-note-help" className="alerts-field__help" data-testid="alerts-ack-count">
        {t('alerts:history.ack.count', { n: note.length, max: ACK_NOTE_MAX_CHARS })}
      </p>
      {fits ? null : (
        <p className="alerts-field__error" role="alert" data-testid="alerts-ack-too-long">
          {t('alerts:history.ack.tooLong')}
        </p>
      )}
      {conflict ? (
        <p className="alerts-field__error" role="alert" data-testid="alerts-ack-conflict">
          {t('alerts:history.ack.conflict')}
        </p>
      ) : mutation.isError ? (
        <ErrorState compact error={errorInfo(mutation.error)} data-testid="alerts-ack-error" />
      ) : null}
    </AlertDialog>
  );
}
