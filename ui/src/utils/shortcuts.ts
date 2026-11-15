// SPDX-License-Identifier: Apache-2.0
//
// Global keyboard shortcuts (docs/15 §5, §13): Ctrl/Cmd+K search, g o / g d / g x / g a
// navigation, t 1..9 presets, [ ] previous/next range, + - zoom, n now, l live, Esc close/clear,
// ? help, and Up/Down/Enter in lists. Single-key shortcuts are inactive while the user types in a
// field. The handler is pure apart from the "g" and "t" prefix states, so it is tested without a
// browser (utils/shortcuts.test.ts); app/GlobalShortcuts.tsx attaches it to the window.
import { PRESETS, type RelativeRange } from '../state/urlState';

export type ShortcutAction =
  | 'search'
  | 'goOverview'
  | 'goDashboard'
  | 'goXlog'
  | 'goActive'
  /** `t` then a digit 1..9: the preset at that position of PRESETS (docs/15 §5). */
  | 'preset'
  | 'prev'
  | 'next'
  | 'zoomIn'
  | 'zoomOut'
  | 'now'
  | 'toggleLive'
  | 'escape'
  | 'help';

/** The shortcut table shown by the ? help (keys as displayed, i18n key in namespace shell). */
export const SHORTCUTS: readonly { action: ShortcutAction; keys: readonly string[] }[] = [
  { action: 'search', keys: ['Ctrl+K', '⌘K'] },
  { action: 'goOverview', keys: ['g o'] },
  { action: 'goDashboard', keys: ['g d'] },
  { action: 'goXlog', keys: ['g x'] },
  { action: 'goActive', keys: ['g a'] },
  { action: 'preset', keys: ['t 1–9'] },
  { action: 'prev', keys: ['['] },
  { action: 'next', keys: [']'] },
  { action: 'zoomIn', keys: ['+'] },
  { action: 'zoomOut', keys: ['-'] },
  { action: 'now', keys: ['n'] },
  { action: 'toggleLive', keys: ['l'] },
  { action: 'escape', keys: ['Esc'] },
  { action: 'help', keys: ['?'] },
];

/** Time to press the second key of a "g" or "t" sequence. */
export const SEQUENCE_TIMEOUT_MS = 1_000;

/** Presets in the order of the `t` digits: t 1 = PRESETS[0] (5m) … t 9 = PRESETS[8] (yesterday). */
export const PRESET_SHORTCUTS: readonly RelativeRange[] = PRESETS;

/** The subset of KeyboardEvent the handler reads. */
export interface KeyInput {
  readonly key: string;
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly altKey: boolean;
  readonly target: EventTarget | null;
  readonly isComposing?: boolean;
}

const TEXT_INPUT_TYPES = new Set([
  'text',
  'search',
  'email',
  'password',
  'url',
  'tel',
  'number',
  'date',
  'datetime-local',
  'month',
  'time',
  'week',
]);

/** True while the user types: inputs, text areas, selects, editable content, text roles. */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (typeof Element === 'undefined' || !(target instanceof Element)) {
    return false;
  }
  if (target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement) {
    return true;
  }
  if (target instanceof HTMLInputElement) {
    return TEXT_INPUT_TYPES.has(target.type);
  }
  if (target instanceof HTMLElement && target.isContentEditable) {
    return true;
  }
  const role = target.getAttribute('role');
  return role === 'textbox' || role === 'combobox' || role === 'searchbox';
}

const SINGLE_KEYS: Readonly<Record<string, ShortcutAction>> = {
  '[': 'prev',
  ']': 'next',
  '+': 'zoomIn',
  '-': 'zoomOut',
  n: 'now',
  l: 'toggleLive',
  '?': 'help',
};

const G_KEYS: Readonly<Record<string, ShortcutAction>> = {
  o: 'goOverview',
  d: 'goDashboard',
  x: 'goXlog',
  a: 'goActive',
};

/**
 * Creates a keydown handler. `onAction` returns false when the action does not apply here (the
 * key is then not consumed); for 'preset' it also gets the chosen range. The handler returns true
 * when it consumed the key (the caller calls preventDefault).
 */
export function createShortcutHandler(
  onAction: (action: ShortcutAction, preset?: RelativeRange) => boolean | undefined,
  now: () => number = Date.now,
): (event: KeyInput) => boolean {
  // At most one prefix is pending: pressing g clears a pending t and the other way round.
  let gPressedAt: number | null = null;
  let tPressedAt: number | null = null;

  const run = (action: ShortcutAction, preset?: RelativeRange): boolean =>
    onAction(action, preset) !== false;
  const clearPrefixes = (): void => {
    gPressedAt = null;
    tPressedAt = null;
  };

  return (event) => {
    if (event.isComposing === true) {
      return false;
    }
    const key = event.key;
    // Ctrl/Cmd+K works everywhere, also in fields (it moves the focus to the search).
    if ((event.ctrlKey || event.metaKey) && !event.altKey && key.toLowerCase() === 'k') {
      clearPrefixes();
      return run('search');
    }
    if (key === 'Escape') {
      clearPrefixes();
      return run('escape');
    }
    if (event.ctrlKey || event.metaKey || event.altKey || isTypingTarget(event.target)) {
      clearPrefixes();
      return false;
    }
    if (gPressedAt !== null) {
      const fresh = now() - gPressedAt <= SEQUENCE_TIMEOUT_MS;
      gPressedAt = null;
      const action = G_KEYS[key.toLowerCase()];
      if (fresh && action !== undefined) {
        return run(action);
      }
    }
    if (tPressedAt !== null) {
      const fresh = now() - tPressedAt <= SEQUENCE_TIMEOUT_MS;
      tPressedAt = null;
      const preset = /^[1-9]$/.test(key) ? PRESET_SHORTCUTS[Number(key) - 1] : undefined;
      if (fresh && preset !== undefined) {
        return run('preset', preset);
      }
    }
    if (key === 'g') {
      clearPrefixes();
      gPressedAt = now();
      return true;
    }
    if (key === 't') {
      clearPrefixes();
      tPressedAt = now();
      return true;
    }
    const action = SINGLE_KEYS[key];
    return action === undefined ? false : run(action);
  };
}

/**
 * Index after an Up/Down/Home/End key in a list of `count` items (Enter is the caller's "open"):
 * -1 means "no item" (empty list). Stops at the ends instead of wrapping.
 */
export function nextListIndex(current: number, count: number, key: string): number {
  if (count <= 0) {
    return -1;
  }
  switch (key) {
    case 'ArrowDown':
      return current < 0 ? 0 : Math.min(count - 1, current + 1);
    case 'ArrowUp':
      return current < 0 ? 0 : Math.max(0, current - 1);
    case 'Home':
      return 0;
    case 'End':
      return count - 1;
    default:
      return Math.min(Math.max(current, -1), count - 1);
  }
}

/**
 * Keyboard handling for a list of focusable items (`[data-list-item]` inside `container`):
 * Up/Down/Home/End move the focus, Enter calls `onOpen` with the focused item. Returns true when
 * the key was handled.
 */
export function handleListKey(
  container: HTMLElement,
  key: string,
  onOpen?: (item: HTMLElement, index: number) => void,
): boolean {
  const items = Array.from(container.querySelectorAll<HTMLElement>('[data-list-item]'));
  const active = typeof document === 'undefined' ? null : document.activeElement;
  const current = items.findIndex((item) => item === active || item.contains(active));
  if (key === 'Enter') {
    const item = items[current];
    if (item !== undefined && onOpen !== undefined) {
      onOpen(item, current);
      return true;
    }
    return false;
  }
  if (key !== 'ArrowDown' && key !== 'ArrowUp' && key !== 'Home' && key !== 'End') {
    return false;
  }
  const next = nextListIndex(current, items.length, key);
  const target = items[next];
  if (target === undefined) {
    return false;
  }
  target.focus();
  return true;
}
