// SPDX-License-Identifier: Apache-2.0

/**
 * argos/no-object-url (docs/16 §9.3, SEC-60, DSN-15-06, T-SEC-60): no `blob:` object URLs in src/.
 * An object URL is a link target that safeHref() refuses and argos/no-raw-href cannot see when it
 * is assigned in code; it can turn response data into a document of the page's origin. Downloads
 * (the server CSV export) go through the one approved helper, saveFile() in src/api/exportCsv.ts,
 * which eslint-allow.json exempts from this rule with its reason.
 *
 * Reported: any member access named `createObjectURL` (`URL.createObjectURL(…)`,
 * `window.URL.createObjectURL`, `globalThis.URL['createObjectURL']`, `webkitURL.createObjectURL`,
 * also without a call) and destructuring it (`const { createObjectURL } = URL`).
 * `URL.revokeObjectURL` stays allowed: it only releases a URL.
 *
 * @type {import('eslint').Rule.RuleModule}
 */
const noObjectUrl = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Forbid object URLs (URL.createObjectURL) outside the approved download helper (SEC-60)',
    },
    schema: [],
    messages: {
      objectUrl:
        'Object URLs (blob:) bypass safeHref() (docs/16 §9.3). Save downloads through saveFile() ' +
        'in src/api/exportCsv.ts (the server CSV export, DSN-15-06).',
    },
  },
  create(context) {
    const NAME = 'createObjectURL';

    /**
     * @param {any} node MemberExpression or Property key holder
     * @returns {string | null}
     */
    const memberName = (node) => {
      if (!node.computed && node.property.type === 'Identifier') {
        return node.property.name;
      }
      if (node.computed && node.property.type === 'Literal') {
        return typeof node.property.value === 'string' ? node.property.value : null;
      }
      if (
        node.computed &&
        node.property.type === 'TemplateLiteral' &&
        node.property.expressions.length === 0
      ) {
        return node.property.quasis[0]?.value.cooked ?? null;
      }
      return null;
    };

    /**
     * @param {any} key
     * @param {boolean} computed
     * @returns {string | null}
     */
    const keyName = (key, computed) => {
      if (!computed && key.type === 'Identifier') {
        return key.name;
      }
      if (key.type === 'Literal' && typeof key.value === 'string') {
        return key.value;
      }
      return null;
    };

    return {
      MemberExpression(node) {
        if (memberName(node) === NAME) {
          context.report({ node, messageId: 'objectUrl' });
        }
      },
      ObjectPattern(node) {
        for (const property of node.properties) {
          if (property.type === 'Property' && keyName(property.key, property.computed) === NAME) {
            context.report({ node: property, messageId: 'objectUrl' });
          }
        }
      },
    };
  },
};

export default noObjectUrl;
