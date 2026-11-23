// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../api/untrusted';

import { FilterChipBar } from './FilterChipBar';
import { KindFilter } from './KindFilter';
import { installDomStubs } from './testing/dom';
import { withI18n } from './testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from './testing/xssPayloads';
import { TimeRangePicker, type TimeRangePickerProps } from './TimeRangePicker';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U14' };

installDomStubs();

const wrapper = withI18n('en');

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
  vi.restoreAllMocks();
});

function pickerProps(overrides: Partial<TimeRangePickerProps> = {}): TimeRangePickerProps {
  const to = new Date(2026, 10, 14, 10, 0, 0).getTime();
  return {
    value: { kind: 'relative', range: '15m' },
    resolved: { from: to - 15 * 60_000, to },
    live: false,
    onPreset: vi.fn(),
    onAbsolute: vi.fn(),
    onShift: vi.fn(),
    onZoom: vi.fn(),
    onNow: vi.fn(),
    onLiveChange: vi.fn(),
    onLiveResume: vi.fn(),
    ...overrides,
  };
}

describe('TimeRangePicker (docs/15 §5, §6.5)', () => {
  it('emits presets, shift, zoom, now and live from the contract test ids', async () => {
    const user = userEvent.setup();
    const props = pickerProps();
    render(<TimeRangePicker {...props} />, { wrapper });
    expect(screen.getByTestId('time-range-preset')).toHaveTextContent('Last 15 minutes');
    await user.click(screen.getByTestId('time-range-preset'));
    const presets = ['5m', '15m', '1h', '6h', '24h', '7d', '30d', 'today', 'yesterday'];
    for (const range of presets) {
      expect(screen.getByTestId(`time-range-preset-${range}`)).toBeInTheDocument();
    }
    await user.click(screen.getByTestId('time-range-preset-1h'));
    expect(props.onPreset).toHaveBeenCalledWith('1h');
    expect(screen.queryByTestId('time-range-absolute')).toBeNull();
    await user.click(screen.getByTestId('time-range-prev'));
    await user.click(screen.getByTestId('time-range-next'));
    await user.click(screen.getByTestId('time-range-zoom-in'));
    await user.click(screen.getByTestId('time-range-zoom-out'));
    await user.click(screen.getByTestId('time-range-now'));
    await user.click(screen.getByTestId('live-toggle'));
    expect(props.onShift).toHaveBeenNthCalledWith(1, -1);
    expect(props.onShift).toHaveBeenNthCalledWith(2, 1);
    expect(props.onZoom).toHaveBeenNthCalledWith(1, 'in');
    expect(props.onZoom).toHaveBeenNthCalledWith(2, 'out');
    expect(props.onNow).toHaveBeenCalledTimes(1);
    expect(props.onLiveChange).toHaveBeenCalledWith(true);
  });

  it('applies an absolute range in the browser time zone and shows its UTC offset', async () => {
    const user = userEvent.setup();
    vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(-540);
    const props = pickerProps();
    render(<TimeRangePicker {...props} />, { wrapper });
    await user.click(screen.getByTestId('time-range-preset'));
    const form = screen.getByTestId('time-range-absolute');
    expect(within(form).getByTestId('time-range-offset')).toHaveTextContent('(UTC+09:00)');
    // The browser normalises a datetime-local value and drops ":00" seconds (HTML "valid
    // normalized local date and time string"); parseDateTimeLocalValue reads both forms.
    expect(screen.getByTestId('time-range-absolute-from')).toHaveValue('2026-11-14T09:45');
    expect(screen.getByTestId('time-range-absolute-to')).toHaveValue('2026-11-14T10:00');
    fireEvent.change(screen.getByTestId('time-range-absolute-from'), {
      target: { value: '2026-11-14T11:00:00' },
    });
    expect(screen.getByTestId('time-range-absolute-apply')).toBeDisabled();
    expect(within(form).getByRole('alert')).toHaveTextContent('The start must be before the end.');
    fireEvent.change(screen.getByTestId('time-range-absolute-from'), {
      target: { value: '2026-11-14T08:00:00' },
    });
    await user.click(screen.getByTestId('time-range-absolute-apply'));
    expect(props.onAbsolute).toHaveBeenCalledWith(
      new Date(2026, 10, 14, 8, 0, 0).getTime(),
      new Date(2026, 10, 14, 10, 0, 0).getTime(),
    );
  });

  it('shows an absolute value, the live state, Resume live and closes on Escape', async () => {
    const user = userEvent.setup();
    const from = new Date(2026, 10, 14, 9, 0, 0).getTime();
    const to = new Date(2026, 10, 14, 9, 30, 0).getTime();
    const props = pickerProps({
      value: { kind: 'absolute', from, to },
      resolved: { from, to },
      showLiveResume: true,
    });
    const { rerender } = render(<TimeRangePicker {...props} />, { wrapper });
    expect(screen.getByTestId('time-range-preset')).toHaveTextContent(
      '11/14/2026, 09:00:00 – 11/14/2026, 09:30:00',
    );
    expect(screen.getByTestId('time-range-preset')).toHaveAttribute('data-range', 'absolute');
    expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'false');
    await user.click(screen.getByTestId('live-resume'));
    expect(props.onLiveResume).toHaveBeenCalledTimes(1);
    await user.click(screen.getByTestId('time-range-preset'));
    expect(screen.getByTestId('time-range-absolute')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByTestId('time-range-absolute')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('time-range-preset'));
    rerender(<TimeRangePicker {...props} live showLiveResume liveAvailable />);
    expect(screen.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('live-resume')).toBeNull();
    rerender(<TimeRangePicker {...props} liveAvailable={false} />);
    expect(screen.getByTestId('live-toggle')).toBeDisabled();
  });

  it('lets a live state carried to a screen without live mode be turned off there (docs/15 §5)', async () => {
    const user = userEvent.setup();
    const props = pickerProps({ live: true, liveAvailable: false });
    render(<TimeRangePicker {...props} />, { wrapper });
    const toggle = screen.getByTestId('live-toggle');
    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    // The screen still says that it has no live mode.
    expect(toggle).toHaveAttribute('title', 'This page has no live mode.');
    await user.click(toggle);
    expect(props.onLiveChange).toHaveBeenCalledTimes(1);
    expect(props.onLiveChange).toHaveBeenCalledWith(false);
  });
});

describe('FilterChipBar (docs/15 §6.5, deep link 3)', () => {
  it('renders chips, removes one, clears all and edits a chip', async () => {
    const user = userEvent.setup();
    const onRemove = vi.fn();
    const onClear = vi.fn();
    const onEdit = vi.fn();
    render(
      <FilterChipBar
        filters={[
          { field: 'service', op: 'eq', value: 'GET /api/orders/{id}' },
          { field: 'instance', op: 'ne', value: 'api-2' },
        ]}
        fieldLabel={(field) => (field === 'service' ? 'Service' : 'Instance')}
        onRemove={onRemove}
        onClear={onClear}
        onEdit={onEdit}
      />,
      { wrapper },
    );
    const chips = screen.getAllByTestId('filter-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0]).toHaveTextContent('Service=GET /api/orders/{id}');
    expect(chips[1]).toHaveTextContent('Instance≠api-2');
    await user.click(within(chips[1] as HTMLElement).getByTestId('filter-chip-remove'));
    expect(onRemove).toHaveBeenCalledWith(1);
    expect(within(chips[1] as HTMLElement).getByTestId('filter-chip-remove')).toHaveAccessibleName(
      'Remove filter Instance',
    );
    await user.click(screen.getByTestId('filter-chip-clear'));
    expect(onClear).toHaveBeenCalledTimes(1);
    await user.click(within(chips[0] as HTMLElement).getByRole('button', { name: /^Service/ }));
    await user.selectOptions(screen.getByTestId('filter-chip-op'), 'ne');
    await user.clear(screen.getByTestId('filter-chip-value'));
    await user.type(screen.getByTestId('filter-chip-value'), 'GET /x');
    await user.click(screen.getByTestId('filter-chip-save'));
    expect(onEdit).toHaveBeenCalledWith(0, { field: 'service', op: 'ne', value: 'GET /x' });
  });

  it('names a key value (valueLabel) and keeps the key in data-value and the tooltip', () => {
    const names: Record<string, string> = {
      '18446744073709551610': 'GET /api/orders/{id}',
      '7': 'api-7:8080',
    };
    render(
      <FilterChipBar
        filters={[
          { field: 'service', op: 'eq', value: '18446744073709551610' },
          { field: 'instance', op: 'ne', value: '7' },
          { field: 'service', op: 'eq', value: '42' },
          { field: 'status', op: 'eq', value: '500' },
        ]}
        valueLabel={(f) =>
          f.field === 'status' || names[f.value] === undefined
            ? null
            : untrusted(names[f.value] ?? '')
        }
        onRemove={vi.fn()}
        onClear={vi.fn()}
      />,
      { wrapper },
    );
    const chips = screen.getAllByTestId('filter-chip');
    const value = (i: number) => chips[i]?.querySelector('.filter-chip__value');
    // Named: the name is shown, the key is data-value and the chip's tooltip.
    expect(value(0)).toHaveTextContent('GET /api/orders/{id}');
    expect(chips[0]).toHaveAttribute('data-value', '18446744073709551610');
    expect(chips[0]).toHaveAttribute('title', '18446744073709551610');
    expect(chips[0]).toHaveAttribute('data-named', 'true');
    expect(value(1)).toHaveTextContent('api-7:8080');
    expect(chips[1]).toHaveAttribute('data-value', '7');
    // Unknown key and a field without names: the value itself, no chip tooltip.
    expect(value(2)).toHaveTextContent(/^42$/);
    expect(chips[2]).toHaveAttribute('data-value', '42');
    expect(chips[2]).not.toHaveAttribute('title');
    expect(chips[2]).not.toHaveAttribute('data-named');
    expect(value(3)).toHaveTextContent(/^500$/);
  });

  it('T-UI-005 renders named chip values (XSS seed names) as text', () => {
    const { container } = render(
      <FilterChipBar
        filters={XSS_PAYLOADS.map((_, i) => ({
          field: 'service',
          op: 'eq' as const,
          value: String(1000 + i),
        }))}
        valueLabel={(f) => untrusted(XSS_PAYLOADS[Number(f.value) - 1000] ?? '')}
        onRemove={vi.fn()}
        onClear={vi.fn()}
      />,
      { wrapper },
    );
    const shown = [...container.querySelectorAll('.filter-chip__value')].map((e) => e.textContent);
    expect(shown).toEqual([...XSS_PAYLOADS]);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-005 renders chip values as text', () => {
    const { container } = render(
      <FilterChipBar
        filters={XSS_PAYLOADS.map((value) => ({ field: 'service', op: 'eq' as const, value }))}
        onRemove={vi.fn()}
        onClear={vi.fn()}
      />,
      { wrapper },
    );
    for (const payload of XSS_PAYLOADS) {
      expect(screen.getAllByTitle(payload)).toHaveLength(1);
    }
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('renders nothing without filters', () => {
    const { container } = render(
      <FilterChipBar filters={[]} onRemove={vi.fn()} onClear={vi.fn()} />,
      { wrapper },
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('KindFilter (docs/15 §6.5, D-20)', () => {
  it('selects with click and arrow keys and disables batch with a tooltip', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { rerender } = render(<KindFilter value="HTTP" onChange={onChange} />, { wrapper });
    expect(screen.getByTestId('kind-filter')).toHaveAttribute('role', 'radiogroup');
    expect(screen.getByTestId('kind-filter-http')).toHaveAttribute('aria-checked', 'true');
    await user.click(screen.getByTestId('kind-filter-message'));
    expect(onChange).toHaveBeenLastCalledWith('MESSAGE');
    screen.getByTestId('kind-filter-http').focus();
    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('BATCH');

    rerender(<KindFilter value="HTTP" onChange={onChange} batchAvailable={false} />);
    const batch = screen.getByTestId('kind-filter-batch');
    expect(batch).toHaveAttribute('aria-disabled', 'true');
    onChange.mockClear();
    await user.click(batch);
    expect(onChange).not.toHaveBeenCalled();
    await user.hover(batch);
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'This application has no batch transactions.',
    );
    screen.getByTestId('kind-filter-http').focus();
    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('MESSAGE');
  });
});
