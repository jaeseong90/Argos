// SPDX-License-Identifier: Apache-2.0
//
// Modal dialog of the command screens (run confirmation, single-thread stack; docs/15 §9.8,
// §13 keyboard rules). A plain element tree rendered in place (no portal, no injected styles,
// CSP-safe): role="dialog" with aria-modal and the title as its name, the first field (else the
// first button) focused on open, Tab kept inside, Esc closes the topmost dialog, and the focus goes
// back to the element that opened it (WAI-ARIA APG dialog pattern).
import { useEffect, useId, useRef, type ReactNode } from 'react';

import './commands.css';

const FOCUSABLE =
  'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

export interface CommandDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
  readonly footer?: ReactNode;
  readonly size?: 'narrow' | 'wide';
  readonly 'data-testid'?: string;
}

export function CommandDialog({
  open,
  title,
  onClose,
  children,
  footer,
  size = 'narrow',
  'data-testid': testId,
}: CommandDialogProps) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;
    const field = panel?.querySelector<HTMLElement>(
      'input:not([disabled]), textarea:not([disabled]), select:not([disabled])',
    );
    (field ?? panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel)?.focus();
    const onDocumentKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape' || panel === null || event.defaultPrevented) {
        return;
      }
      const dialogs = panel.ownerDocument.querySelectorAll('[role="dialog"][aria-modal="true"]');
      if (dialogs[dialogs.length - 1] !== panel) {
        return;
      }
      event.stopPropagation();
      event.preventDefault();
      onCloseRef.current();
    };
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Tab' || panel === null) {
        return;
      }
      const items = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      const first = items[0];
      const last = items[items.length - 1];
      if (first === undefined || last === undefined) {
        event.preventDefault();
        return;
      }
      const active = panel.ownerDocument.activeElement;
      if (event.shiftKey && (active === first || active === panel)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    panel?.addEventListener('keydown', onKeyDown);
    panel?.ownerDocument.addEventListener('keydown', onDocumentKeyDown);
    return () => {
      panel?.removeEventListener('keydown', onKeyDown);
      panel?.ownerDocument.removeEventListener('keydown', onDocumentKeyDown);
      if (opener !== null && opener.isConnected) {
        opener.focus();
      }
    };
  }, [open]);

  if (!open) {
    return null;
  }
  return (
    <div className="cmd-dialog__backdrop">
      <div
        ref={panelRef}
        className={`cmd-dialog cmd-dialog--${size}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid={testId}
      >
        <h2 id={titleId} className="cmd-dialog__title">
          {title}
        </h2>
        <div className="cmd-dialog__body">{children}</div>
        {footer === undefined ? null : <div className="cmd-dialog__footer">{footer}</div>}
      </div>
    </div>
  );
}
