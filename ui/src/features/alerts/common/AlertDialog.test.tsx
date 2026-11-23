// SPDX-License-Identifier: Apache-2.0
//
// The alert screens' modal dialog (docs/15 §13 keyboard rules): Esc closes the topmost dialog even
// when the focus has left it (a Save button that disables itself while its request runs drops the
// focus to <body>; T-E2E-alerts-7 closes the channel editor with Esc after a refused save).
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { AlertDialog } from './AlertDialog';

describe('AlertDialog', () => {
  it('T-UI-006 Esc closes the dialog while the focus is on the body', () => {
    const onClose = vi.fn();
    render(
      <AlertDialog open title="Editor" onClose={onClose} data-testid="dialog">
        <input aria-label="name" />
      </AlertDialog>,
    );
    expect(screen.getByLabelText('name')).toHaveFocus();
    (document.activeElement as HTMLElement).blur();
    expect(document.body).toHaveFocus();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('T-UI-006 Esc inside the dialog closes it once', () => {
    const onClose = vi.fn();
    render(
      <AlertDialog open title="Editor" onClose={onClose}>
        <input aria-label="name" />
      </AlertDialog>,
    );
    fireEvent.keyDown(screen.getByLabelText('name'), { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('T-UI-006 with two open dialogs Esc closes only the topmost', () => {
    const outer = vi.fn();
    const inner = vi.fn();
    render(
      <>
        <AlertDialog open title="Outer" onClose={outer}>
          <input aria-label="outer" />
        </AlertDialog>
        <AlertDialog open title="Inner" onClose={inner}>
          <input aria-label="inner" />
        </AlertDialog>
      </>,
    );
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(inner).toHaveBeenCalledTimes(1);
    expect(outer).not.toHaveBeenCalled();
  });

  it('a closed dialog does not listen', () => {
    const onClose = vi.fn();
    render(
      <AlertDialog open={false} title="Editor" onClose={onClose}>
        <input aria-label="name" />
      </AlertDialog>,
    );
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });
});
