// SPDX-License-Identifier: Apache-2.0
//
// Notification channels `/alerts/channels` (docs/15 §9.9, 05 §2.4-5, docs/16 §8, WO-38): the list
// (type, name, masked target, last success and failure, consecutive failures), create and edit
// with the URL or recipients re-entered (the destination validation result shown at once), a test
// send through the same validation (delivered, status code and duration only, never a response
// body), the webhook signing-key rotation (confirm, then the new secret shown once) and delete
// (409 while a rule uses the channel). Editing is for administrators (`channels.edit-url`);
// operators see the list read-only. Channel names are entered strings (SafeText, SEC-60).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  deleteChannel,
  isConflict,
  rotateSigningKey,
  testChannel,
  type AlertChannel,
  type ChannelTestResult,
} from '../../../api/alerts';
import type { Enveloped } from '../../../api/client';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import { useFormat } from '../../../i18n/format';
import { useCan } from '../../../state/permissions';
import { MISSING_DASH } from '../../../utils/missing';
import { AlertDialog } from '../common/AlertDialog';
import { channelTypeLabel, deliveryErrorLabel } from '../common/labels';
import { useChannelsQuery } from '../history/data';

import { ChannelEditor } from './ChannelEditor';
import { SecretOnceDialog, type OneTimeSecret } from './SecretOnceDialog';

import '../common/alerts.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

type EditorState =
  { readonly mode: 'new' } | { readonly mode: 'edit'; readonly channel: AlertChannel };

type Confirm =
  | { readonly kind: 'rotate'; readonly channel: AlertChannel }
  | { readonly kind: 'delete'; readonly channel: AlertChannel };

interface TestState {
  readonly channelId: string;
  readonly result: ChannelTestResult | null;
  readonly error: unknown;
  readonly pending: boolean;
}

export function AlertChannelsPage() {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const queryClient = useQueryClient();
  const canEdit = useCan('channels.edit-url');
  const query = useChannelsQuery();
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [confirmError, setConfirmError] = useState<unknown>(null);
  const [confirmPending, setConfirmPending] = useState(false);
  const [secret, setSecret] = useState<OneTimeSecret | null>(null);
  const [test, setTest] = useState<TestState | null>(null);

  const channels = query.data?.data ?? [];
  const nameOf = (channelId: string): AlertChannel | undefined =>
    channels.find((channel) => channel.channel_id === channelId);

  /** Puts a saved channel into the cached list at once (masked target only), then refetches. */
  const storeChannel = (channel: AlertChannel): void => {
    queryClient.setQueryData<Enveloped<AlertChannel[]>>(queryKeys.alerts.channels(), (previous) => {
      if (previous === undefined) {
        return previous;
      }
      const others = previous.data.filter((item) => item.channel_id !== channel.channel_id);
      return {
        ...previous,
        data: [...others, channel].sort((a, b) => a.name.localeCompare(b.name)),
      };
    });
    void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.channels() });
  };

  const runTest = async (channel: AlertChannel): Promise<void> => {
    setTest({ channelId: channel.channel_id, result: null, error: null, pending: true });
    try {
      const result = await testChannel(channel.channel_id);
      setTest({ channelId: channel.channel_id, result, error: null, pending: false });
    } catch (error) {
      setTest({ channelId: channel.channel_id, result: null, error, pending: false });
    } finally {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.channels() });
    }
  };

  const runConfirm = async (): Promise<void> => {
    if (confirm === null) {
      return;
    }
    setConfirmPending(true);
    setConfirmError(null);
    try {
      if (confirm.kind === 'rotate') {
        const rotated = await rotateSigningKey(confirm.channel.channel_id);
        setConfirm(null);
        setSecret({
          secret: rotated.signing_key,
          previousValidUntil: rotated.previous_valid_until,
        });
      } else {
        await deleteChannel(confirm.channel.channel_id);
        setConfirm(null);
        if (test?.channelId === confirm.channel.channel_id) {
          setTest(null);
        }
        void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.channels() });
      }
    } catch (error) {
      setConfirmError(error);
    } finally {
      setConfirmPending(false);
    }
  };

  const time = (iso: string | null) => {
    const ms = iso === null ? Number.NaN : Date.parse(iso);
    return Number.isNaN(ms) ? (
      <span className="alerts-muted">{MISSING_DASH}</span>
    ) : (
      <time dateTime={new Date(ms).toISOString()}>{format.dateTime(ms)}</time>
    );
  };

  const columns: DataTableColumn<AlertChannel>[] = [
    {
      id: 'type',
      header: t('alerts:channels.column.type'),
      value: (row) => row.type,
      width: 100,
      render: (row) => channelTypeLabel(t, row.type),
    },
    {
      id: 'name',
      header: t('alerts:channels.column.name'),
      value: (row) => row.name,
      width: 200,
      render: (row) => <SafeText value={row.name} data-testid="alert-channel-name" />,
    },
    {
      id: 'target_masked',
      header: t('alerts:channels.column.target'),
      value: (row) => row.target_masked,
      width: 260,
      render: (row) => (
        <SafeText value={row.target_masked} mono data-testid="alert-channel-target-masked" />
      ),
    },
    {
      id: 'last_success_at',
      header: t('alerts:channels.column.lastSuccess'),
      value: (row) => row.last_success_at,
      width: 160,
      render: (row) => time(row.last_success_at),
    },
    {
      id: 'last_failure_at',
      header: t('alerts:channels.column.lastFailure'),
      value: (row) => row.last_failure_at,
      width: 160,
      render: (row) => time(row.last_failure_at),
    },
    {
      id: 'consecutive_failures',
      header: t('alerts:channels.column.failures'),
      value: (row) => row.consecutive_failures,
      kind: 'count',
      width: 110,
    },
  ];
  if (canEdit) {
    columns.push({
      id: 'actions',
      header: t('alerts:channels.column.actions'),
      value: () => null,
      width: 330,
      hideable: false,
      render: (row) => (
        <span className="alerts-actions">
          <button
            type="button"
            className="control-button"
            disabled={test?.pending === true}
            onClick={() => {
              void runTest(row);
            }}
            data-testid="alert-channel-test"
          >
            {t('alerts:channels.action.test')}
          </button>
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setEditor({ mode: 'edit', channel: row });
            }}
            data-testid="alert-channel-edit"
          >
            {t('alerts:channels.action.edit')}
          </button>
          {row.type === 'webhook' ? (
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setConfirmError(null);
                setConfirm({ kind: 'rotate', channel: row });
              }}
              data-testid="alert-channel-rotate-key"
            >
              {t('alerts:channels.action.rotate')}
            </button>
          ) : null}
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setConfirmError(null);
              setConfirm({ kind: 'delete', channel: row });
            }}
            data-testid="alert-channel-delete"
          >
            {t('alerts:channels.action.delete')}
          </button>
        </span>
      ),
    });
  }

  let screenState: ScreenState;
  if (query.isPending) {
    screenState = 'loading';
  } else if (query.isError) {
    screenState = 'error';
  } else if (channels.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }
  const tested = test === null ? undefined : nameOf(test.channelId);

  return (
    <section
      className="alerts-page"
      aria-labelledby="alert-channels-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="alert-channels"
      data-state={screenState}
      data-editable={canEdit ? 'true' : 'false'}
    >
      <header className="alerts-page__header">
        <h1 id="alert-channels-title" className="alerts-page__title">
          {t('alerts:channels.title')}
        </h1>
        {canEdit ? (
          <button
            type="button"
            className="control-button control-button--primary"
            onClick={() => {
              setEditor({ mode: 'new' });
            }}
            data-testid="alert-channel-new"
          >
            {t('alerts:channels.action.new')}
          </button>
        ) : (
          <p className="alerts-muted" data-testid="alert-channels-read-only">
            {t('alerts:channels.readOnly')}
          </p>
        )}
      </header>
      {test === null ? null : (
        <div
          className="alerts-panel alerts-test-result"
          role="status"
          data-testid="alert-channel-test-result"
          data-channel-id={test.channelId}
          data-pending={test.pending ? 'true' : undefined}
          data-delivered={test.result === null ? undefined : String(test.result.delivered)}
          data-status-code={
            test.result === null || test.result.status_code === null
              ? undefined
              : String(test.result.status_code)
          }
        >
          <span className="alerts-test-result__channel">
            {t('alerts:channels.test.label')}{' '}
            {tested === undefined ? null : <SafeText value={tested.name} />}
          </span>
          {test.pending ? (
            <span>{t('alerts:channels.test.sending')}</span>
          ) : test.result !== null ? (
            <>
              <span
                className={
                  test.result.delivered ? 'alerts-test-result--ok' : 'alerts-delivery__error'
                }
              >
                {test.result.delivered
                  ? t('alerts:channels.test.delivered')
                  : t('alerts:channels.test.notDelivered')}
              </span>
              <span>
                {test.result.status_code === null
                  ? t('alerts:channels.test.noStatus')
                  : t('alerts:channels.test.status', { code: test.result.status_code })}
              </span>
              <ValueText value={test.result.duration_ms} unit="ms" />
              {test.result.error === null ? null : (
                <span className="alerts-delivery__error">
                  {deliveryErrorLabel(t, test.result.error)}
                </span>
              )}
            </>
          ) : (
            <ErrorState
              compact
              error={errorInfo(test.error)}
              data-testid="alert-channel-test-error"
            />
          )}
        </div>
      )}
      {screenState === 'loading' ? (
        <LoadingState variant="table" slowHint="wait" data-testid="alert-channels-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="alert-channels-error"
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={t('alerts:channels.empty.title')}
          description={
            canEdit
              ? t('alerts:channels.empty.descriptionAdmin')
              : t('alerts:channels.empty.descriptionReadOnly')
          }
          actions={
            canEdit
              ? [
                  {
                    label: t('alerts:channels.action.new'),
                    onClick: () => {
                      setEditor({ mode: 'new' });
                    },
                    'data-testid': 'alert-channel-new-empty',
                  },
                ]
              : []
          }
          data-testid="alert-channels-empty"
        />
      ) : (
        <DataTable
          tableId="alert-channels"
          caption={t('alerts:channels.caption')}
          columns={columns}
          rows={channels}
          rowKey={(row) => row.channel_id}
          dataComplete
          rowAttributes={(row) => ({
            'data-channel-id': row.channel_id,
            'data-type': row.type,
            'data-status': row.status,
          })}
        />
      )}
      {editor === null ? null : (
        <ChannelEditor
          channel={editor.mode === 'edit' ? editor.channel : null}
          onClose={() => {
            setEditor(null);
          }}
          onCreated={(created) => {
            setEditor(null);
            storeChannel(created.channel);
            if (created.signing_key !== null) {
              setSecret({ secret: created.signing_key, previousValidUntil: null });
            }
          }}
          onUpdated={(channel) => {
            setEditor(null);
            storeChannel(channel);
          }}
        />
      )}
      <AlertDialog
        open={confirm !== null}
        size="narrow"
        title={
          confirm?.kind === 'rotate'
            ? t('alerts:channels.confirm.rotateTitle')
            : t('alerts:channels.confirm.deleteTitle')
        }
        onClose={() => {
          setConfirm(null);
        }}
        data-testid="alert-channel-confirm"
        footer={
          <>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setConfirm(null);
              }}
            >
              {t('alerts:common.cancel')}
            </button>
            <button
              type="button"
              className="control-button control-button--primary"
              disabled={confirmPending || (confirm?.kind === 'delete' && isConflict(confirmError))}
              onClick={() => {
                void runConfirm();
              }}
              data-testid="alert-channel-confirm-ok"
            >
              {confirm?.kind === 'rotate'
                ? t('alerts:channels.confirm.rotate')
                : t('alerts:channels.confirm.delete')}
            </button>
          </>
        }
      >
        {confirm === null ? null : (
          <>
            <p className="alerts-dialog__subject">
              <SafeText value={confirm.channel.name} maxLines={2} />
            </p>
            <p className="alerts-field__help">
              {confirm.kind === 'rotate'
                ? t('alerts:channels.confirm.rotateHelp')
                : t('alerts:channels.confirm.deleteHelp')}
            </p>
            {confirmError === null ? null : confirm.kind === 'delete' &&
              isConflict(confirmError) ? (
              <p className="alerts-field__error" role="alert" data-testid="alert-channel-in-use">
                {t('alerts:channels.confirm.inUse')}
              </p>
            ) : (
              <ErrorState
                compact
                error={errorInfo(confirmError)}
                data-testid="alert-channel-confirm-error"
              />
            )}
          </>
        )}
      </AlertDialog>
      <SecretOnceDialog
        value={secret}
        onClose={() => {
          setSecret(null);
        }}
      />
    </section>
  );
}
