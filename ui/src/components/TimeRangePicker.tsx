// SPDX-License-Identifier: Apache-2.0
//
// Global time range control (docs/15 §5, §6.5, 05 §2.0-1..3, §2.0-9): presets, an absolute range in
// the browser time zone with its UTC offset, previous / next, zoom in / out, now, the live toggle
// and "Resume live". Controlled: the shell owns the range (URL) and the reducer that applies these
// events (src/state, T-UI-002); this component only renders the value and emits the user's intent.
import {
  ClockCircleOutlined,
  LeftOutlined,
  RightOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';
import { useRef, useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';

import {
  formatDateTime,
  formatDateTimeWithOffset,
  formatUtcOffset,
  parseDateTimeLocalValue,
  toDateTimeLocalValue,
} from '../utils/format';

import { Popup } from './Popup';

import './controls.css';

/** Presets of docs/15 §4.2 and 05 §2.0-3. */
export const RELATIVE_RANGES = [
  '5m',
  '15m',
  '1h',
  '6h',
  '24h',
  '7d',
  '30d',
  'today',
  'yesterday',
] as const;
export type RelativeRange = (typeof RELATIVE_RANGES)[number];

/** The time range model of docs/15 §5. */
export type TimeRangeValue =
  | { readonly kind: 'relative'; readonly range: RelativeRange }
  | { readonly kind: 'absolute'; readonly from: number; readonly to: number };

export interface TimeRangePickerProps {
  readonly value: TimeRangeValue;
  /** The range resolved to epoch ms by the shell (relative ranges resolve against now). */
  readonly resolved: { readonly from: number; readonly to: number };
  readonly live: boolean;
  /**
   * The screen supports live mode (overview, dashboard, XLog, active). Default true. Without it
   * the live toggle is disabled while live is off; while live is on it stays usable to turn off.
   */
  readonly liveAvailable?: boolean;
  /** Show "Resume live" (live was turned off by selecting a past range, docs/15 §5). */
  readonly showLiveResume?: boolean;
  readonly presets?: readonly RelativeRange[];
  readonly onPreset: (range: RelativeRange) => void;
  readonly onAbsolute: (from: number, to: number) => void;
  /** Previous (-1) or next (+1) range of the same length. */
  readonly onShift: (direction: -1 | 1) => void;
  readonly onZoom: (direction: 'in' | 'out') => void;
  readonly onNow: () => void;
  readonly onLiveChange: (live: boolean) => void;
  readonly onLiveResume: () => void;
}

interface AbsoluteFormProps {
  readonly from: number;
  readonly to: number;
  readonly onApply: (from: number, to: number) => void;
  readonly onCancel: () => void;
}

function AbsoluteForm({ from, to, onApply, onCancel }: AbsoluteFormProps) {
  const { t } = useTranslation();
  const [fromText, setFromText] = useState(() => toDateTimeLocalValue(from));
  const [toText, setToText] = useState(() => toDateTimeLocalValue(to));
  const fromMs = parseDateTimeLocalValue(fromText);
  const toMs = parseDateTimeLocalValue(toText);
  const valid = fromMs !== null && toMs !== null && fromMs < toMs;
  const submit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (fromMs !== null && toMs !== null && fromMs < toMs) {
      onApply(fromMs, toMs);
    }
  };
  return (
    <form className="popup__form" onSubmit={submit} data-testid="time-range-absolute">
      <p className="popup__heading">{t('component.timeRange.absolute')}</p>
      <label className="popup__field">
        <span>{t('component.timeRange.from')}</span>
        <input
          type="datetime-local"
          step={1}
          value={fromText}
          onChange={(event) => {
            setFromText(event.target.value);
          }}
          data-testid="time-range-absolute-from"
        />
      </label>
      <label className="popup__field">
        <span>{t('component.timeRange.to')}</span>
        <input
          type="datetime-local"
          step={1}
          value={toText}
          onChange={(event) => {
            setToText(event.target.value);
          }}
          data-testid="time-range-absolute-to"
        />
      </label>
      <p className="popup__note" data-testid="time-range-offset">
        {t('component.timeRange.timeZone', { offset: formatUtcOffset(fromMs ?? from) })}
      </p>
      {valid ? null : (
        <p className="popup__error" role="alert">
          {t('component.timeRange.invalid')}
        </p>
      )}
      <div className="popup__actions">
        <button type="button" className="control-button" onClick={onCancel}>
          {t('component.common.cancel')}
        </button>
        <button
          type="submit"
          className="control-button control-button--primary"
          disabled={!valid}
          data-testid="time-range-absolute-apply"
        >
          {t('component.common.apply')}
        </button>
      </div>
    </form>
  );
}

/** Global time range selector with shift, zoom, now and live controls. */
export function TimeRangePicker({
  value,
  resolved,
  live,
  liveAvailable = true,
  showLiveResume = false,
  presets = RELATIVE_RANGES,
  onPreset,
  onAbsolute,
  onShift,
  onZoom,
  onNow,
  onLiveChange,
  onLiveResume,
}: TimeRangePickerProps) {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const presetLabel = (range: RelativeRange): string => {
    switch (range) {
      case '5m':
        return t('component.timeRange.preset.m5');
      case '15m':
        return t('component.timeRange.preset.m15');
      case '1h':
        return t('component.timeRange.preset.h1');
      case '6h':
        return t('component.timeRange.preset.h6');
      case '24h':
        return t('component.timeRange.preset.h24');
      case '7d':
        return t('component.timeRange.preset.d7');
      case '30d':
        return t('component.timeRange.preset.d30');
      case 'today':
        return t('component.timeRange.preset.today');
      case 'yesterday':
        return t('component.timeRange.preset.yesterday');
    }
  };
  const label =
    value.kind === 'relative'
      ? presetLabel(value.range)
      : t('component.timeRange.absoluteValue', {
          from: formatDateTime(value.from, locale),
          to: formatDateTime(value.to, locale),
        });
  const title = t('component.timeRange.absoluteValue', {
    from: formatDateTimeWithOffset(resolved.from, locale),
    to: formatDateTimeWithOffset(resolved.to, locale),
  });
  const close = (): void => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  return (
    <div
      className="time-range"
      role="group"
      aria-label={t('component.timeRange.label')}
      data-testid="time-range-picker"
    >
      <button
        type="button"
        className="control-button control-button--icon"
        aria-label={t('component.timeRange.prev')}
        title={t('component.timeRange.prev')}
        onClick={() => {
          onShift(-1);
        }}
        data-testid="time-range-prev"
      >
        <LeftOutlined aria-hidden="true" />
      </button>
      <span className="time-range__anchor">
        <button
          ref={triggerRef}
          type="button"
          className="control-button time-range__trigger"
          aria-haspopup="dialog"
          aria-expanded={open}
          title={title}
          onClick={() => {
            setOpen((current) => !current);
          }}
          data-testid="time-range-preset"
          data-range={value.kind === 'relative' ? value.range : 'absolute'}
        >
          <ClockCircleOutlined aria-hidden="true" />
          <span className="time-range__label">{label}</span>
        </button>
        <Popup
          open={open}
          onClose={() => {
            setOpen(false);
          }}
          label={t('component.timeRange.label')}
          triggerRef={triggerRef}
          className="time-range__popup"
        >
          <div className="time-range__panel">
            <ul className="time-range__presets" aria-label={t('component.timeRange.presets')}>
              {presets.map((range) => (
                <li key={range}>
                  <button
                    type="button"
                    className={`time-range__preset${
                      value.kind === 'relative' && value.range === range
                        ? ' time-range__preset--current'
                        : ''
                    }`}
                    aria-current={
                      value.kind === 'relative' && value.range === range ? 'true' : undefined
                    }
                    onClick={() => {
                      onPreset(range);
                      close();
                    }}
                    data-testid={`time-range-preset-${range}`}
                  >
                    {presetLabel(range)}
                  </button>
                </li>
              ))}
            </ul>
            <AbsoluteForm
              from={resolved.from}
              to={resolved.to}
              onApply={(from, to) => {
                onAbsolute(from, to);
                close();
              }}
              onCancel={close}
            />
          </div>
        </Popup>
      </span>
      <button
        type="button"
        className="control-button control-button--icon"
        aria-label={t('component.timeRange.next')}
        title={t('component.timeRange.next')}
        onClick={() => {
          onShift(1);
        }}
        data-testid="time-range-next"
      >
        <RightOutlined aria-hidden="true" />
      </button>
      <button
        type="button"
        className="control-button control-button--icon"
        aria-label={t('component.timeRange.zoomIn')}
        title={t('component.timeRange.zoomIn')}
        onClick={() => {
          onZoom('in');
        }}
        data-testid="time-range-zoom-in"
      >
        <ZoomInOutlined aria-hidden="true" />
      </button>
      <button
        type="button"
        className="control-button control-button--icon"
        aria-label={t('component.timeRange.zoomOut')}
        title={t('component.timeRange.zoomOut')}
        onClick={() => {
          onZoom('out');
        }}
        data-testid="time-range-zoom-out"
      >
        <ZoomOutOutlined aria-hidden="true" />
      </button>
      <button type="button" className="control-button" onClick={onNow} data-testid="time-range-now">
        {t('component.timeRange.now')}
      </button>
      <button
        type="button"
        className={`control-button live-toggle${live ? ' live-toggle--on' : ''}`}
        aria-pressed={live}
        // Without live mode the toggle cannot turn live on, but a live state carried over from
        // another screen (docs/15 §5) can still be turned off here.
        disabled={!liveAvailable && !live}
        title={liveAvailable ? undefined : t('component.timeRange.liveUnavailable')}
        onClick={() => {
          onLiveChange(!live);
        }}
        data-testid="live-toggle"
      >
        <span className="live-toggle__dot" aria-hidden="true" />
        {t('component.timeRange.live')}
      </button>
      {showLiveResume && !live ? (
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={onLiveResume}
          data-testid="live-resume"
        >
          {t('component.timeRange.liveResume')}
        </button>
      ) : null}
    </div>
  );
}
