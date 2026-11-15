// SPDX-License-Identifier: Apache-2.0
//
// Small anchored panel for the shared controls (time range presets, filter chip editor, column
// filters, column chooser, cell value menu). Rendered inline next to its trigger, so it needs no
// portal or injected styles; positioning is CSS. Esc and a click outside close it; on Esc focus
// returns to the trigger, or without one to the element that had focus when the panel opened
// (docs/15 §13 keyboard rules). With role="menu", Up/Down (wrapping), Home and End move the focus
// between the menu items (WAI-ARIA APG menu pattern); a menu whose items are all disabled takes
// the focus on its panel, so Esc still closes it and gives the focus back.
import { useEffect, useRef, type CSSProperties, type ReactNode, type RefObject } from 'react';

import './controls.css';

export interface PopupProps {
  readonly open: boolean;
  readonly onClose: () => void;
  /** Accessible name of the panel. */
  readonly label: string;
  /** Element that opened the panel; it gets focus back on close and does not count as outside. */
  readonly triggerRef?: RefObject<HTMLElement | null>;
  readonly align?: 'start' | 'end';
  readonly role?: 'dialog' | 'menu';
  readonly className?: string;
  /** Position overrides (set through the CSSOM, allowed by CSP), e.g. a chart click point. */
  readonly style?: CSSProperties;
  readonly children: ReactNode;
  readonly 'data-testid'?: string;
}

const FOCUSABLE =
  'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
const MENU_ITEM = '[role="menuitem"]:not([disabled])';

/**
 * Moves the focus between the items of a menu panel for Up/Down (wrapping), Home and End. Returns
 * false for other keys and for a menu without items (the key is then not consumed).
 */
function moveMenuFocus(key: string, panel: HTMLElement): boolean {
  if (key !== 'ArrowDown' && key !== 'ArrowUp' && key !== 'Home' && key !== 'End') {
    return false;
  }
  const items = Array.from(panel.querySelectorAll<HTMLElement>(MENU_ITEM));
  if (items.length === 0) {
    return false;
  }
  const current = items.findIndex((item) => item === panel.ownerDocument.activeElement);
  const last = items.length - 1;
  let next: number;
  if (key === 'Home') {
    next = 0;
  } else if (key === 'End') {
    next = last;
  } else if (key === 'ArrowDown') {
    next = current < 0 || current === last ? 0 : current + 1;
  } else {
    next = current <= 0 ? last : current - 1;
  }
  items[next]?.focus();
  return true;
}

/** Anchored panel that closes on Esc and outside clicks. */
export function Popup({
  open,
  onClose,
  label,
  triggerRef,
  align = 'start',
  role = 'dialog',
  className,
  style,
  children,
  'data-testid': testId,
}: PopupProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const panel = panelRef.current;
    // Focus to give back on Esc when there is no trigger (a table row that opened a cell menu
    // with Shift+F10, for example). Read before the panel takes the focus.
    const active = document.activeElement;
    const previous =
      active instanceof HTMLElement && active !== document.body && panel?.contains(active) !== true
        ? active
        : null;
    const first = panel?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? (role === 'menu' ? panel : null))?.focus();
    const trigger = triggerRef?.current ?? null;
    const onPointerDown = (event: MouseEvent): void => {
      const target = event.target as Node | null;
      if (
        target === null ||
        panel?.contains(target) === true ||
        trigger?.contains(target) === true
      ) {
        return;
      }
      onCloseRef.current();
    };
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        event.preventDefault();
        onCloseRef.current();
        if (trigger !== null) {
          trigger.focus();
        } else if (previous?.isConnected === true) {
          previous.focus();
        }
        return;
      }
      if (role === 'menu' && panel !== null && moveMenuFocus(event.key, panel)) {
        event.preventDefault();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    panel?.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      panel?.removeEventListener('keydown', onKeyDown);
    };
  }, [open, triggerRef, role]);

  if (!open) {
    return null;
  }
  const classes = ['popup', `popup--${align}`];
  if (className !== undefined) {
    classes.push(className);
  }
  return (
    <div
      ref={panelRef}
      className={classes.join(' ')}
      role={role}
      aria-label={label}
      tabIndex={role === 'menu' ? -1 : undefined}
      style={style}
      data-testid={testId}
    >
      {children}
    </div>
  );
}
