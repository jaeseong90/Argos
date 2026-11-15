// SPDX-License-Identifier: Apache-2.0
import noObjectUrl from './no-object-url.mjs';
import noRawHref from './no-raw-href.mjs';
import noTokenStorage from './no-token-storage.mjs';
import spdxHeader from './spdx-header.mjs';
import untrustedText from './untrusted-text.mjs';

/**
 * Project-local ESLint rules, registered as the `argos` plugin in eslint.config.mjs.
 *
 * @type {import('eslint').ESLint.Plugin}
 */
const plugin = {
  meta: { name: 'eslint-plugin-argos', version: '0.0.0' },
  rules: {
    'no-object-url': noObjectUrl,
    'no-raw-href': noRawHref,
    'no-token-storage': noTokenStorage,
    'spdx-header': spdxHeader,
    'untrusted-text': untrustedText,
  },
};

export default plugin;
