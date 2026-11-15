// SPDX-License-Identifier: Apache-2.0
//
// Global keyboard shortcuts (docs/15 §13): the key table, the "g" sequences, inactivity while the
// user types, and list focus handling. The shell wiring (time range, navigation, help) is tested
// in src/routes/shell.test.tsx.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PRESETS } from '../state/urlState';

import {
  createShortcutHandler,
  handleListKey,
  isTypingTarget,
  nextListIndex,
  PRESET_SHORTCUTS,
  SEQUENCE_TIMEOUT_MS,
  SHORTCUTS,
  type KeyInput,
  type ShortcutAction,
} from './shortcuts';

function key(k: string, extra: Partial<KeyInput> = {}): KeyInput {
  return { key: k, ctrlKey: false, metaKey: false, altKey: false, target: document.body, ...extra };
}

function setup(result: boolean | undefined = true) {
  const actions: ShortcutAction[] = [];
  const presets: (string | undefined)[] = [];
  let clock = 1_000_000;
  const handler = createShortcutHandler(
    (action, preset) => {
      actions.push(action);
      presets.push(preset);
      return result;
    },
    () => clock,
  );
  return {
    actions,
    presets,
    handler,
    tick: (ms: number) => {
      clock += ms;
    },
  };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('shortcuts: key table (docs/15 §13)', () => {
  it.each([
    ['[', 'prev'],
    [']', 'next'],
    ['+', 'zoomIn'],
    ['-', 'zoomOut'],
    ['n', 'now'],
    ['l', 'toggleLive'],
    ['?', 'help'],
    ['Escape', 'escape'],
  ] as const)('%s -> %s', (k, action) => {
    const { actions, handler } = setup();
    expect(handler(key(k))).toBe(true);
    expect(actions).toEqual([action]);
  });

  it('Ctrl+K and Cmd+K focus the search, also while typing in a field', () => {
    const { actions, handler } = setup();
    const input = document.createElement('input');
    document.body.append(input);
    expect(handler(key('k', { ctrlKey: true }))).toBe(true);
    expect(handler(key('K', { metaKey: true, target: input }))).toBe(true);
    expect(actions).toEqual(['search', 'search']);
    // Alt+Ctrl+K is not the shortcut.
    expect(handler(key('k', { ctrlKey: true, altKey: true }))).toBe(false);
  });

  it.each([
    ['o', 'goOverview'],
    ['d', 'goDashboard'],
    ['x', 'goXlog'],
    ['a', 'goActive'],
  ] as const)('g %s -> %s within one second', (second, action) => {
    const { actions, handler, tick } = setup();
    expect(handler(key('g'))).toBe(true);
    tick(SEQUENCE_TIMEOUT_MS);
    expect(handler(key(second))).toBe(true);
    expect(actions).toEqual([action]);
  });

  it('a g sequence expires after one second and an unknown second key does nothing', () => {
    const { actions, handler, tick } = setup();
    handler(key('g'));
    tick(SEQUENCE_TIMEOUT_MS + 1);
    expect(handler(key('o'))).toBe(false);
    handler(key('g'));
    expect(handler(key('z'))).toBe(false);
    // The single-key meaning still applies after an expired prefix.
    handler(key('g'));
    tick(SEQUENCE_TIMEOUT_MS + 1);
    expect(handler(key('n'))).toBe(true);
    expect(actions).toEqual(['now']);
  });

  it('lists every shortcut in the help table', () => {
    expect(SHORTCUTS.map((s) => s.action)).toEqual([
      'search',
      'goOverview',
      'goDashboard',
      'goXlog',
      'goActive',
      'preset',
      'prev',
      'next',
      'zoomIn',
      'zoomOut',
      'now',
      'toggleLive',
      'escape',
      'help',
    ]);
  });
});

describe('shortcuts: t then a digit selects a preset (docs/15 §5)', () => {
  it('maps t 1..9 to the presets in picker order', () => {
    expect(PRESET_SHORTCUTS).toEqual(PRESETS);
    expect(PRESET_SHORTCUTS).toEqual([
      '5m',
      '15m',
      '1h',
      '6h',
      '24h',
      '7d',
      '30d',
      'today',
      'yesterday',
    ]);
    PRESETS.forEach((preset, index) => {
      const { actions, presets, handler } = setup();
      expect(handler(key('t'))).toBe(true);
      expect(handler(key(String(index + 1)))).toBe(true);
      expect(actions).toEqual(['preset']);
      expect(presets).toEqual([preset]);
    });
  });

  it('t 3 selects the last hour within one second', () => {
    const { actions, presets, handler, tick } = setup();
    expect(handler(key('t'))).toBe(true);
    expect(actions).toEqual([]);
    tick(SEQUENCE_TIMEOUT_MS);
    expect(handler(key('3'))).toBe(true);
    expect(actions).toEqual(['preset']);
    expect(presets).toEqual(['1h']);
  });

  it('a t sequence expires after one second; 0 and other keys select nothing', () => {
    const { actions, handler, tick } = setup();
    handler(key('t'));
    tick(SEQUENCE_TIMEOUT_MS + 1);
    expect(handler(key('3'))).toBe(false);
    handler(key('t'));
    expect(handler(key('0'))).toBe(false);
    handler(key('t'));
    expect(handler(key('x'))).toBe(false);
    // The single-key meaning still applies after the prefix.
    handler(key('t'));
    expect(handler(key('n'))).toBe(true);
    expect(actions).toEqual(['now']);
    // A digit without the prefix does nothing.
    expect(handler(key('3'))).toBe(false);
    expect(actions).toEqual(['now']);
  });

  it('t t restarts the sequence: the second t is a new prefix', () => {
    const { actions, presets, handler, tick } = setup();
    handler(key('t'));
    tick(SEQUENCE_TIMEOUT_MS);
    expect(handler(key('t'))).toBe(true);
    tick(SEQUENCE_TIMEOUT_MS);
    expect(handler(key('2'))).toBe(true);
    expect(actions).toEqual(['preset']);
    expect(presets).toEqual(['15m']);
  });

  it('does not mix with the g sequences', () => {
    const { actions, presets, handler } = setup();
    // g then t: the t starts its own sequence; the pending g is gone.
    handler(key('g'));
    expect(handler(key('t'))).toBe(true);
    expect(handler(key('o'))).toBe(false);
    // t then g: the g starts its own sequence; the pending t is gone.
    handler(key('t'));
    expect(handler(key('g'))).toBe(true);
    expect(handler(key('3'))).toBe(false);
    expect(actions).toEqual([]);
    // Both still work on their own afterwards.
    handler(key('g'));
    handler(key('o'));
    handler(key('t'));
    handler(key('6'));
    expect(actions).toEqual(['goOverview', 'preset']);
    expect(presets).toEqual([undefined, '7d']);
  });

  it('ignores t in a field, with modifiers and during IME composition', () => {
    const input = document.createElement('input');
    document.body.append(input);
    const { actions, handler } = setup();
    expect(handler(key('t', { target: input }))).toBe(false);
    expect(handler(key('3', { target: input }))).toBe(false);
    expect(handler(key('t', { ctrlKey: true }))).toBe(false);
    expect(handler(key('t', { isComposing: true }))).toBe(false);
    // A prefix typed on the page is dropped when the next key goes into a field.
    handler(key('t'));
    expect(handler(key('3', { target: input }))).toBe(false);
    expect(handler(key('3'))).toBe(false);
    // Alt+digit after t is not the shortcut either.
    handler(key('t'));
    expect(handler(key('3', { altKey: true }))).toBe(false);
    expect(actions).toEqual([]);
  });
});

describe('shortcuts: inactive while typing (docs/15 §13)', () => {
  it.each([
    ['text input', () => document.createElement('input')],
    ['search input', () => Object.assign(document.createElement('input'), { type: 'search' })],
    ['textarea', () => document.createElement('textarea')],
    ['select', () => document.createElement('select')],
    [
      'role=combobox',
      () => {
        const div = document.createElement('div');
        div.setAttribute('role', 'combobox');
        return div;
      },
    ],
  ])('ignores single keys in a %s', (_name, make) => {
    const target = make();
    document.body.append(target);
    const { actions, handler } = setup();
    expect(isTypingTarget(target)).toBe(true);
    for (const k of ['[', ']', '+', '-', 'n', 'l', '?', 'g']) {
      expect(handler(key(k, { target }))).toBe(false);
    }
    expect(actions).toEqual([]);
  });

  it('does not treat checkboxes, buttons or the page as typing', () => {
    const checkbox = Object.assign(document.createElement('input'), { type: 'checkbox' });
    const button = document.createElement('button');
    document.body.append(checkbox, button);
    expect(isTypingTarget(checkbox)).toBe(false);
    expect(isTypingTarget(button)).toBe(false);
    expect(isTypingTarget(document.body)).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });

  it('ignores keys with modifiers and keys during IME composition', () => {
    const { actions, handler } = setup();
    expect(handler(key('n', { ctrlKey: true }))).toBe(false);
    expect(handler(key('[', { altKey: true }))).toBe(false);
    expect(handler(key('n', { isComposing: true }))).toBe(false);
    expect(actions).toEqual([]);
  });

  it('does not consume a key whose action does not apply here', () => {
    const { actions, handler } = setup(false);
    expect(handler(key('l'))).toBe(false);
    expect(actions).toEqual(['toggleLive']);
  });
});

describe('shortcuts: lists (Up/Down/Enter, docs/15 §13)', () => {
  it('moves within the bounds without wrapping', () => {
    expect(nextListIndex(-1, 3, 'ArrowDown')).toBe(0);
    expect(nextListIndex(0, 3, 'ArrowDown')).toBe(1);
    expect(nextListIndex(2, 3, 'ArrowDown')).toBe(2);
    expect(nextListIndex(1, 3, 'ArrowUp')).toBe(0);
    expect(nextListIndex(0, 3, 'ArrowUp')).toBe(0);
    expect(nextListIndex(1, 3, 'Home')).toBe(0);
    expect(nextListIndex(1, 3, 'End')).toBe(2);
    expect(nextListIndex(0, 0, 'ArrowDown')).toBe(-1);
  });

  it('focuses list items with the arrow keys and opens the focused one with Enter', () => {
    const list = document.createElement('ul');
    const items = ['a', 'b', 'c'].map((name) => {
      const li = document.createElement('li');
      li.tabIndex = 0;
      li.dataset.listItem = name;
      list.append(li);
      return li;
    });
    document.body.append(list);
    const onOpen = vi.fn();
    expect(handleListKey(list, 'ArrowDown', onOpen)).toBe(true);
    expect(document.activeElement).toBe(items[0]);
    handleListKey(list, 'ArrowDown', onOpen);
    expect(document.activeElement).toBe(items[1]);
    expect(handleListKey(list, 'Enter', onOpen)).toBe(true);
    expect(onOpen).toHaveBeenCalledWith(items[1], 1);
    handleListKey(list, 'End', onOpen);
    expect(document.activeElement).toBe(items[2]);
    expect(handleListKey(list, 'x', onOpen)).toBe(false);
  });
});
