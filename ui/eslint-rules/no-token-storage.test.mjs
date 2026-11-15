// SPDX-License-Identifier: Apache-2.0
//
// RuleTester cases for argos/no-token-storage (T-SEC-60, SEC-40, docs/15 §11).
import { RuleTester } from 'eslint';
import globals from 'globals';
import { describe, it } from 'vitest';

import noTokenStorage from './no-token-storage.mjs';

RuleTester.describe = describe;
RuleTester.it = it;
RuleTester.itOnly = it.only;

const ruleTester = new RuleTester({
  languageOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    globals: { ...globals.browser },
  },
});

const PREFIX = 'argos.ui.';
/** @param {string} storage @param {string} method */
const key = (storage, method) => ({
  messageId: 'key',
  data: { storage, method, prefix: PREFIX },
});
/** @param {string} storage */
const access = (storage) => ({ messageId: 'access', data: { storage, prefix: PREFIX } });
const cookie = { messageId: 'cookie' };

ruleTester.run('argos/no-token-storage (T-SEC-60)', noTokenStorage, {
  valid: [
    "localStorage.setItem('argos.ui.language', 'ko');",
    "const lang = localStorage.getItem('argos.ui.language');",
    "localStorage.removeItem('argos.ui.language');",
    "sessionStorage.setItem('argos.ui.sidebar.collapsed', '1');",
    'localStorage.getItem(`argos.ui.tab`);',
    "window.localStorage.setItem('argos.ui.columns', JSON.stringify([]));",
    "globalThis.sessionStorage.getItem('argos.ui.tab');",
    "self['localStorage'].removeItem('argos.ui.tab');",
    "localStorage?.getItem('argos.ui.language');",
    // Reading cookies stays allowed: the API wrapper reads argos_csrf (docs/14 §2.2).
    'const csrf = document.cookie;',
    "const csrf = document.cookie.split('; ').find((c) => c.startsWith('argos_csrf='));",
    "document.title = 'Argos';",
    "cookieStore.get('argos_csrf');",
    // Local names that shadow the globals are not browser storage.
    'function clear(localStorage) { localStorage.clear(); }',
    "const sessionStorage = new Map(); sessionStorage.set('token', 'x');",
    "const storage = { setItem() {} }; storage.setItem('token', 'x');",
    "function f(document) { document.cookie = 'x'; }",
    {
      code: "localStorage.setItem('acme.pref', '1');",
      options: [{ prefix: 'acme.' }],
    },
  ],
  invalid: [
    { code: "localStorage.setItem('token', jwt);", errors: [key('localStorage', 'setItem')] },
    { code: "localStorage.getItem('argos_at');", errors: [key('localStorage', 'getItem')] },
    { code: "sessionStorage.setItem('user', name);", errors: [key('sessionStorage', 'setItem')] },
    { code: "localStorage.setItem(name, '1');", errors: [key('localStorage', 'setItem')] },
    {
      code: "localStorage.setItem(`argos.ui.${name}`, '1');",
      errors: [key('localStorage', 'setItem')],
    },
    { code: "localStorage.setItem('argos.ui.', '1');", errors: [key('localStorage', 'setItem')] },
    { code: "localStorage.setItem('ARGOS.UI.x', '1');", errors: [key('localStorage', 'setItem')] },
    { code: 'localStorage.removeItem();', errors: [key('localStorage', 'removeItem')] },
    { code: "localStorage?.setItem('token', jwt);", errors: [key('localStorage', 'setItem')] },
    {
      code: "window.localStorage.setItem('jwt', jwt);",
      errors: [key('localStorage', 'setItem')],
    },
    {
      code: "globalThis['sessionStorage'].getItem('refresh');",
      errors: [key('sessionStorage', 'getItem')],
    },
    {
      code: "localStorage['getItem']('token');",
      errors: [key('localStorage', 'getItem')],
    },
    { code: "localStorage['argos.ui.x'] = '1';", errors: [access('localStorage')] },
    { code: 'localStorage.token = jwt;', errors: [access('localStorage')] },
    { code: 'localStorage[name] = jwt;', errors: [access('localStorage')] },
    { code: 'localStorage.clear();', errors: [access('localStorage')] },
    { code: 'const n = sessionStorage.length;', errors: [access('sessionStorage')] },
    { code: 'sessionStorage.key(0);', errors: [access('sessionStorage')] },
    { code: 'const store = localStorage;', errors: [access('localStorage')] },
    { code: 'persist(sessionStorage);', errors: [access('sessionStorage')] },
    {
      code: "const setItem = localStorage.setItem; setItem('token', jwt);",
      errors: [access('localStorage')],
    },
    {
      code: "Storage.prototype.setItem.call(localStorage, 'token', jwt);",
      errors: [access('localStorage')],
    },
    { code: 'const { localStorage: ls } = window;', errors: [access('localStorage')] },
    { code: "const { 'sessionStorage': ss } = globalThis;", errors: [access('sessionStorage')] },
    { code: "document.cookie = 'argos_at=x';", errors: [cookie] },
    { code: "window.document.cookie = 'argos_csrf=x';", errors: [cookie] },
    { code: "document['cookie'] += '; a=b';", errors: [cookie] },
    { code: "cookieStore.set('argos_at', token);", errors: [cookie] },
    { code: "window.cookieStore.delete('argos_rt');", errors: [cookie] },
    { code: 'keep(cookieStore);', errors: [cookie] },
    { code: "indexedDB.open('argos');", errors: [{ messageId: 'indexedDB' }] },
    { code: "window.indexedDB.open('argos');", errors: [{ messageId: 'indexedDB' }] },
    {
      code: "localStorage.setItem('argos.ui.x', '1');",
      options: [{ prefix: 'acme.' }],
      errors: [
        { messageId: 'key', data: { storage: 'localStorage', method: 'setItem', prefix: 'acme.' } },
      ],
    },
  ],
});
