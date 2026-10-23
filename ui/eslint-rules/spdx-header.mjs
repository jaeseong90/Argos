// SPDX-License-Identifier: Apache-2.0

/**
 * Requires the SPDX license identifier as the first line of every JS/TS source file
 * (docs/19 §5.1, O-L07). A shebang line may precede it. Auto-fixable.
 *
 * @type {import('eslint').Rule.RuleModule}
 */
const spdxHeader = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Require `// SPDX-License-Identifier: <id>` as the first line of the file',
    },
    fixable: 'code',
    schema: [
      {
        type: 'object',
        properties: { identifier: { type: 'string', minLength: 1 } },
        additionalProperties: false,
      },
    ],
    defaultOptions: [{ identifier: 'Apache-2.0' }],
    messages: {
      missing: 'The first line must be the SPDX header "{{expected}}".',
    },
  },
  create(context) {
    const options = /** @type {{ identifier?: string } | undefined} */ (context.options[0]);
    const expected = `// SPDX-License-Identifier: ${options?.identifier ?? 'Apache-2.0'}`;
    return {
      Program() {
        const text = context.sourceCode.text;
        let start = 0;
        if (text.startsWith('#!')) {
          const newline = text.indexOf('\n');
          start = newline === -1 ? text.length : newline + 1;
        }
        const end = text.indexOf('\n', start);
        const firstLine = text.slice(start, end === -1 ? text.length : end).replace(/\r$/, '');
        if (firstLine === expected) {
          return;
        }
        context.report({
          loc: { line: start === 0 ? 1 : 2, column: 0 },
          messageId: 'missing',
          data: { expected },
          fix: (fixer) => fixer.insertTextBeforeRange([start, start], `${expected}\n`),
        });
      },
    };
  },
};

export default spdxHeader;
