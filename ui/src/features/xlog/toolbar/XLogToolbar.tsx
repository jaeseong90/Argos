// SPDX-License-Identifier: Apache-2.0
//
// Chart options of the XLog screen (docs/15 §9.3 top line, 05 §2.1-1/-3/-4): colour basis (status,
// instance, top services), y axis (linear, log), the latency threshold of the response
// (`meta.slow_threshold_ms`), live on/off, pause/resume of the realtime window and "select the
// visible range" (the keyboard alternative of a drag, WO-04 C-12). A real role="toolbar": one tab
// stop, Left/Right/Home/End move between the buttons (docs/15 §13). Colour and axis are URL state
// (`color`, `y`); live is the global time state; pause is local to the page (docs/15 §7.5).
import { useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { useXLogFormat } from '../../../xlog/useXLogFormat';

import { nextToolbarIndex } from './roving';

export type ColorBasis = 'status' | 'instance' | 'service';
export type YAxis = 'lin' | 'log';

export interface XLogToolbarProps {
  readonly colorBy: ColorBasis;
  readonly onColorBy: (value: ColorBasis) => void;
  readonly yAxis: YAxis;
  readonly onYAxis: (value: YAxis) => void;
  /** Latency threshold of the response (ms); null when the server sent none. */
  readonly threshold: number | null;
  readonly live: boolean;
  /** The screen supports live mode (always for XLog; false only while no app is known). */
  readonly liveAvailable: boolean;
  readonly onLive: (live: boolean) => void;
  readonly paused: boolean;
  readonly onPause: () => void;
  readonly onResume: () => void;
  readonly onSelectVisible: () => void;
  /** No point to select (nothing loaded yet). */
  readonly selectVisibleDisabled: boolean;
}

interface Item {
  readonly id: string;
  readonly label: string;
  readonly pressed?: boolean;
  readonly disabled?: boolean;
  readonly onClick: () => void;
  readonly group: 'color' | 'axis' | 'live' | 'select';
}

export function XLogToolbar(props: XLogToolbarProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const [active, setActive] = useState(0);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const items: Item[] = [
    ...(['status', 'instance', 'service'] as const).map((value): Item => ({
      id: `xlog-colorby-${value}`,
      label: t(`xlog:toolbar.colorBy.${value}`),
      pressed: props.colorBy === value,
      onClick: () => {
        props.onColorBy(value);
      },
      group: 'color',
    })),
    {
      id: 'xlog-yscale-linear',
      label: t('xlog:toolbar.yScale.linear'),
      pressed: props.yAxis === 'lin',
      onClick: () => {
        props.onYAxis('lin');
      },
      group: 'axis',
    },
    {
      id: 'xlog-yscale-log',
      label: t('xlog:toolbar.yScale.log'),
      pressed: props.yAxis === 'log',
      onClick: () => {
        props.onYAxis('log');
      },
      group: 'axis',
    },
    {
      id: 'xlog-live-toggle',
      label: t('xlog:toolbar.live'),
      pressed: props.live,
      disabled: !props.liveAvailable && !props.live,
      onClick: () => {
        props.onLive(!props.live);
      },
      group: 'live',
    },
    ...(props.live
      ? [
          props.paused
            ? {
                id: 'xlog-resume-button',
                label: t('xlog:toolbar.resumeWindow'),
                onClick: props.onResume,
                group: 'live' as const,
              }
            : {
                id: 'xlog-pause-button',
                label: t('xlog:toolbar.pause'),
                onClick: props.onPause,
                group: 'live' as const,
              },
        ]
      : []),
    {
      id: 'xlog-select-visible',
      label: t('xlog:toolbar.selectVisible'),
      disabled: props.selectVisibleDisabled,
      onClick: props.onSelectVisible,
      group: 'select',
    },
  ];
  const current = Math.min(active, items.length - 1);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const next = nextToolbarIndex(event.key, current, items.length);
    if (next === null) {
      return;
    }
    event.preventDefault();
    setActive(next);
    refs.current[next]?.focus();
  };

  const groupLabel = (group: Item['group']): string | null => {
    switch (group) {
      case 'color':
        return t('xlog:toolbar.colorBy.label');
      case 'axis':
        return t('xlog:toolbar.yScale.label');
      default:
        return null;
    }
  };

  return (
    <div
      className="xlog-toolbar"
      role="toolbar"
      aria-label={t('xlog:toolbar.label')}
      data-testid="xlog-toolbar"
      onKeyDown={onKeyDown}
    >
      {items.map((item, index) => {
        const previous = index > 0 ? items[index - 1] : undefined;
        const label = previous?.group === item.group ? null : groupLabel(item.group);
        return (
          <span key={item.id} className="xlog-toolbar__slot">
            {label === null ? null : (
              <span className="xlog-toolbar__label" aria-hidden="true">
                {label}
              </span>
            )}
            <button
              ref={(element) => {
                refs.current[index] = element;
              }}
              type="button"
              className={`xlog-toolbar__button${item.pressed === true ? ' xlog-toolbar__button--on' : ''}`}
              tabIndex={index === current ? 0 : -1}
              aria-pressed={item.pressed}
              // aria-disabled keeps the button reachable with the arrow keys (toolbar pattern).
              aria-disabled={item.disabled === true ? true : undefined}
              aria-label={
                item.group === 'color' || item.group === 'axis'
                  ? t('xlog:toolbar.itemLabel', {
                      group: groupLabel(item.group) ?? '',
                      item: item.label,
                    })
                  : undefined
              }
              data-testid={item.id}
              onFocus={() => {
                setActive(index);
              }}
              onClick={() => {
                if (item.disabled !== true) {
                  item.onClick();
                }
              }}
            >
              {item.label}
            </button>
          </span>
        );
      })}
      <span className="xlog-toolbar__threshold" data-testid="xlog-threshold">
        <span className="xlog-toolbar__threshold-swatch" aria-hidden="true" />
        {props.threshold === null
          ? t('xlog:toolbar.thresholdUnknown')
          : t('xlog:toolbar.threshold', { value: format.duration(props.threshold) })}
      </span>
      {props.live ? (
        <span className="xlog-toolbar__status" role="status" data-testid="xlog-live-status">
          {props.paused ? t('xlog:live.paused') : t('xlog:live.running')}
        </span>
      ) : null}
      {props.live && props.paused ? (
        // The chart keeps its 5-minute live window (docs/15 §7.5), whatever range the top bar's
        // picker shows ("running" already says "the last 5 minutes").
        <span className="xlog-toolbar__label" data-testid="xlog-live-window">
          {t('xlog:live.window')}
        </span>
      ) : null}
    </div>
  );
}
