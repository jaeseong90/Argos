// SPDX-License-Identifier: Apache-2.0
//
// RuleTester cases for argos/no-object-url (T-SEC-60, docs/16 §9.3, DSN-15-06).
import { RuleTester } from 'eslint';
import globals from 'globals';
import { describe, it } from 'vitest';

import noObjectUrl from './no-object-url.mjs';

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

const objectUrl = { messageId: 'objectUrl' };

ruleTester.run('argos/no-object-url (T-SEC-60)', noObjectUrl, {
  valid: [
    // Releasing a URL is allowed; only creating one is not.
    'URL.revokeObjectURL(url);',
    "const u = new URL('/api/v1/apps', location.origin);",
    'const link = document.createElement("a"); link.download = "x.csv";',
    'const value = { createObject: 1 }.createObject;',
    'const { revokeObjectURL } = URL;',
  ],
  invalid: [
    { code: 'const url = URL.createObjectURL(blob);', errors: [objectUrl] },
    { code: 'link.href = window.URL.createObjectURL(blob);', errors: [objectUrl] },
    { code: "globalThis.URL['createObjectURL'](blob);", errors: [objectUrl] },
    { code: 'self.URL[`createObjectURL`](blob);', errors: [objectUrl] },
    { code: 'webkitURL.createObjectURL(blob);', errors: [objectUrl] },
    { code: 'const make = URL.createObjectURL;', errors: [objectUrl] },
    { code: 'URL?.createObjectURL(blob);', errors: [objectUrl] },
    { code: 'const { createObjectURL } = URL;', errors: [objectUrl] },
    { code: "const { 'createObjectURL': make } = window.URL;", errors: [objectUrl] },
  ],
});
