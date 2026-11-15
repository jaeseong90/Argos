// SPDX-License-Identifier: Apache-2.0
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { Popup } from './Popup';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U16' };

function MenuHarness({ onClose = vi.fn() }: { readonly onClose?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        onKeyDown={(event) => {
          if (event.shiftKey && event.key === 'F10') {
            setOpen(true);
          }
        }}
      >
        opener
      </button>
      <Popup
        open={open}
        role="menu"
        label="Actions"
        onClose={() => {
          setOpen(false);
          onClose();
        }}
      >
        <p>Heading text</p>
        <button type="button" role="menuitem">
          First
        </button>
        <button type="button" role="menuitem" disabled>
          Disabled
        </button>
        <button type="button" role="menuitem">
          Second
        </button>
        <button type="button" role="menuitem">
          Third
        </button>
      </Popup>
    </div>
  );
}

function DialogHarness() {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => {
          setOpen(true);
        }}
      >
        trigger
      </button>
      <button type="button">elsewhere</button>
      <Popup
        open={open}
        label="Form"
        triggerRef={triggerRef}
        onClose={() => {
          setOpen(false);
        }}
      >
        <input aria-label="Value" />
        <button type="button">Apply</button>
      </Popup>
    </div>
  );
}

const active = (): Element | null => document.activeElement;

describe('Popup menu keyboard (WAI-ARIA APG menu, docs/15 §13)', () => {
  it('T-UI-004 moves the focus between menu items with Up/Down (wrapping), Home and End', async () => {
    const user = userEvent.setup();
    render(<MenuHarness />);
    act(() => {
      screen.getByRole('button', { name: 'opener' }).focus();
    });
    await user.keyboard('{Shift>}{F10}{/Shift}');
    const menu = screen.getByRole('menu', { name: 'Actions' });
    const item = (name: string): HTMLElement => screen.getByRole('menuitem', { name });
    // The first item gets the focus on open.
    expect(active()).toBe(item('First'));
    // Down skips the disabled item and wraps from the last item to the first.
    await user.keyboard('{ArrowDown}');
    expect(active()).toBe(item('Second'));
    await user.keyboard('{ArrowDown}');
    expect(active()).toBe(item('Third'));
    await user.keyboard('{ArrowDown}');
    expect(active()).toBe(item('First'));
    // Up wraps from the first item to the last.
    await user.keyboard('{ArrowUp}');
    expect(active()).toBe(item('Third'));
    await user.keyboard('{ArrowUp}');
    expect(active()).toBe(item('Second'));
    await user.keyboard('{Home}');
    expect(active()).toBe(item('First'));
    await user.keyboard('{End}');
    expect(active()).toBe(item('Third'));
    expect(menu).toBeInTheDocument();
  });

  it('T-UI-004 consumes the menu keys and gives the focus back on Esc without a trigger', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const outer = vi.fn();
    const listener = (event: KeyboardEvent): void => {
      outer(event.key, event.defaultPrevented);
    };
    document.addEventListener('keydown', listener);
    render(<MenuHarness onClose={onClose} />);
    const opener = screen.getByRole('button', { name: 'opener' });
    act(() => {
      opener.focus();
    });
    await user.keyboard('{Shift>}{F10}{/Shift}');
    await user.keyboard('{End}');
    document.removeEventListener('keydown', listener);
    // The key moved the focus and is marked handled (no page scroll).
    expect(outer).toHaveBeenLastCalledWith('End', true);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
    // No trigger: the focus returns to the element that had it when the menu opened.
    expect(active()).toBe(opener);
  });
});

describe('Popup dialog keyboard', () => {
  it('keeps the arrow keys for the dialog content and returns the focus to the trigger on Esc', async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    await user.click(screen.getByRole('button', { name: 'trigger' }));
    const input = screen.getByRole('textbox', { name: 'Value' });
    expect(active()).toBe(input);
    await user.keyboard('{ArrowDown}{End}{Home}');
    // No menu roving in a dialog: the focus stays in the field.
    expect(active()).toBe(input);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(active()).toBe(screen.getByRole('button', { name: 'trigger' }));
  });
});
