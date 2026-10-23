// SPDX-License-Identifier: Apache-2.0
import spdxHeader from './spdx-header.mjs';

/**
 * Project-local ESLint rules, registered as the `argos` plugin in eslint.config.mjs.
 *
 * @type {import('eslint').ESLint.Plugin}
 */
const plugin = {
  meta: { name: 'eslint-plugin-argos', version: '0.0.0' },
  rules: {
    'spdx-header': spdxHeader,
  },
};

export default plugin;
