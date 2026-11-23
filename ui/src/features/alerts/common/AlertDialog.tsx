// SPDX-License-Identifier: Apache-2.0
//
// Modal dialog of the alert screens (ACK note, mute, channel editor, one-time secret, confirm;
// docs/15 §9.9). A plain element tree rendered in place (no portal, no injected styles, CSP-safe):
// role="dialog" with aria-modal and the title as its name, the first field focused on open, Tab
// kept inside the dialog, Esc closes the topmost dialog wherever the focus is (as does the
// dialog's own cancel button), and the focus
// goes back to the element
// that opened it (docs/15 §13 keyboard rules, WAI-ARIA APG dialog pattern).
import { useEffect, useId, useRef, type ReactNode } from 'react';

import './alerts.css';

const FOCUSABLE =
  'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

export interface AlertDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
  /** Buttons of the dialog footer. */
  readonly footer?: ReactNode;
  /** Width class: 'narrow' (confirmations) or 'wide' (forms). Default wide. */
  readonly size?: 'narrow' | 'wide';
  readonly 'data-testid'?: string;
}

export function AlertDialog({
  open,
  title,
  onClose,
  children,
  footer,
  size = 'wide',
  'data-testid': testId,
}: AlertDialogProps) {
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
    const first = panel?.querySelector<HTMLElement>(
      'input:not([disabled]), textarea:not([disabled]), select:not([disabled])',
    );
    (first ?? panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel)?.focus();
    // Esc is heard on the document, not only inside the panel: a button that disables itself while
    // a request runs (Save) drops the focus to <body>, and Esc must still close the dialog. Only
    // the topmost open dialog closes.
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
      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      if (firstItem === undefined || lastItem === undefined) {
        event.preventDefault();
        return;
      }
      const active = panel.ownerDocument.activeElement;
      if (event.shiftKey && (active === firstItem || active === panel)) {
        event.preventDefault();
        lastItem.focus();
      } else if (!event.shiftKey && active === lastItem) {
        event.preventDefault();
        firstItem.focus();
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
    <div className="alerts-dialog__backdrop">
      <div
        ref={panelRef}
        className={`alerts-dialog alerts-dialog--${size}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid={testId}
      >
        <h2 id={titleId} className="alerts-dialog__title">
          {title}
        </h2>
        <div className="alerts-dialog__body">{children}</div>
        {footer === undefined ? null : <div className="alerts-dialog__footer">{footer}</div>}
      </div>
    </div>
  );
}
