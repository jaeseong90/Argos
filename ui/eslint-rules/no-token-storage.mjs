// SPDX-License-Identifier: Apache-2.0

/**
 * argos/no-token-storage (SEC-40, docs/15 §11, docs/16 §7.1, T-SEC-60): the session lives in
 * httpOnly cookies the UI cannot read; nothing token-like or user-identifying is kept in browser
 * storage.
 *
 *   - localStorage / sessionStorage (also through window, globalThis or self) may only be used as
 *     `<storage>.getItem|setItem|removeItem(key, …)` with a string literal key (or a template
 *     literal without expressions) that starts with the prefix option (default 'argos.ui.').
 *     Computed keys, other keys, other members (clear, key, length, property access), aliases
 *     (`const s = localStorage`) and destructuring (`const { localStorage } = window`) are errors.
 *   - Writing document.cookie (assignment or update) and cookieStore.set/delete are errors: the
 *     server sets argos_at, argos_rt and argos_csrf. Reading document.cookie stays allowed (the API
 *     wrapper reads argos_csrf for the X-Argos-CSRF header, docs/14 §2.2).
 *   - indexedDB is not used by the UI and is an error.
 *
 * Only references to the real globals count; a local variable that shadows a name is ignored.
 *
 * @type {import('eslint').Rule.RuleModule}
 */
const noTokenStorage = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Allow browser storage only for argos.ui.* preference keys; never write cookies (SEC-40)',
    },
    schema: [
      {
        type: 'object',
        properties: { prefix: { type: 'string', minLength: 1 } },
        additionalProperties: false,
      },
    ],
    defaultOptions: [{ prefix: 'argos.ui.' }],
    messages: {
      key:
        '{{storage}}.{{method}}() needs a string literal key that starts with "{{prefix}}": browser ' +
        'storage holds UI preferences only, never tokens or user data (SEC-40, docs/15 §11).',
      access:
        'Use {{storage}} only as {{storage}}.getItem/setItem/removeItem("{{prefix}}…"): browser ' +
        'storage holds UI preferences only, never tokens or user data (SEC-40, docs/15 §11).',
      cookie:
        'The UI never writes cookies: the server sets the httpOnly session cookies and argos_csrf ' +
        '(SEC-40, SEC-43).',
      indexedDB:
        'indexedDB is not used by the UI: tokens and user data stay out of the browser (SEC-40).',
    },
  },
  create(context) {
    const options = /** @type {{ prefix?: string } | undefined} */ (context.options[0]);
    const prefix = options?.prefix ?? 'argos.ui.';
    const STORAGES = new Set(['localStorage', 'sessionStorage']);
    const GLOBAL_OBJECTS = new Set(['window', 'globalThis', 'self']);
    const METHODS = new Set(['getItem', 'setItem', 'removeItem']);

    /**
     * @param {any} node MemberExpression
     * @returns {string | null} the property name, also for computed string literals
     */
    const propertyName = (node) => {
      if (!node.computed && node.property.type === 'Identifier') {
        return node.property.name;
      }
      if (
        node.computed &&
        node.property.type === 'Literal' &&
        typeof node.property.value === 'string'
      ) {
        return node.property.value;
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
     * @param {any} node
     * @returns {any} the node with TypeScript-only wrappers and optional chains removed upward
     */
    const outer = (node) => {
      let current = node;
      while (
        current.parent &&
        [
          'TSNonNullExpression',
          'TSAsExpression',
          'TSSatisfiesExpression',
          'ChainExpression',
        ].includes(current.parent.type)
      ) {
        current = current.parent;
      }
      return current;
    };

    /**
     * @param {any} key first argument of a storage call
     * @returns {boolean}
     */
    const isAllowedKey = (key) => {
      if (key?.type === 'Literal' && typeof key.value === 'string') {
        return key.value.startsWith(prefix) && key.value.length > prefix.length;
      }
      if (key?.type === 'TemplateLiteral' && key.expressions.length === 0) {
        const value = key.quasis[0]?.value.cooked ?? '';
        return value.startsWith(prefix) && value.length > prefix.length;
      }
      return false;
    };

    /**
     * @param {any} node the expression that evaluates to a storage object
     * @param {string} storage
     */
    const checkStorageUse = (node, storage) => {
      const target = outer(node);
      const member = target.parent;
      if (member?.type !== 'MemberExpression' || member.object !== target) {
        context.report({ node, messageId: 'access', data: { storage, prefix } });
        return;
      }
      const method = propertyName(member);
      const call = outer(member).parent;
      if (
        method === null ||
        !METHODS.has(method) ||
        call?.type !== 'CallExpression' ||
        call.callee !== outer(member)
      ) {
        context.report({ node: member, messageId: 'access', data: { storage, prefix } });
        return;
      }
      if (!isAllowedKey(call.arguments[0])) {
        context.report({
          node: call.arguments[0] ?? call,
          messageId: 'key',
          data: { storage, method, prefix },
        });
      }
    };

    /**
     * @param {any} node expression evaluating to `document`
     */
    const checkDocumentUse = (node) => {
      const target = outer(node);
      const member = target.parent;
      if (member?.type !== 'MemberExpression' || member.object !== target) {
        return;
      }
      if (propertyName(member) !== 'cookie') {
        return;
      }
      const written = outer(member);
      const parent = written.parent;
      if (
        (parent?.type === 'AssignmentExpression' && parent.left === written) ||
        parent?.type === 'UpdateExpression'
      ) {
        context.report({ node: parent, messageId: 'cookie' });
      }
    };

    /**
     * @param {any} node expression evaluating to `cookieStore`
     */
    const checkCookieStoreUse = (node) => {
      const target = outer(node);
      const member = target.parent;
      if (member?.type === 'MemberExpression' && member.object === target) {
        const method = propertyName(member);
        if (method === 'set' || method === 'delete') {
          context.report({ node: member, messageId: 'cookie' });
        }
        return;
      }
      // Passing cookieStore around would hide a later write.
      context.report({ node, messageId: 'cookie' });
    };

    /**
     * Dispatches one use of a global (`name`) at `node`.
     *
     * @param {string} name
     * @param {any} node
     */
    const checkGlobalUse = (name, node) => {
      if (STORAGES.has(name)) {
        checkStorageUse(node, name);
      } else if (name === 'document') {
        checkDocumentUse(node);
      } else if (name === 'cookieStore') {
        checkCookieStoreUse(node);
      } else if (name === 'indexedDB') {
        context.report({ node, messageId: 'indexedDB' });
      }
    };

    const WATCHED = new Set([...STORAGES, 'document', 'cookieStore', 'indexedDB']);

    return {
      'Program:exit'(program) {
        const globalScope = context.sourceCode.getScope(program);
        // Configured globals (languageOptions.globals, TypeScript lib globals) are variables of
        // the global scope; names without a declaration stay in `through`. Module-level
        // declarations live in the module scope, so a shadowing variable is not collected.
        /** @type {any[]} */
        const references = [...globalScope.through];
        for (const name of [...WATCHED, ...GLOBAL_OBJECTS]) {
          references.push(...(globalScope.set.get(name)?.references ?? []));
        }
        /** @type {Set<unknown>} */
        const seen = new Set();
        for (const reference of references) {
          const identifier = /** @type {any} */ (reference.identifier);
          if (seen.has(identifier)) {
            continue;
          }
          seen.add(identifier);
          const name = identifier.name;
          if (WATCHED.has(name)) {
            checkGlobalUse(name, identifier);
            continue;
          }
          if (!GLOBAL_OBJECTS.has(name)) {
            continue;
          }
          // window.localStorage, globalThis['sessionStorage'], self.document.cookie = …
          const target = outer(identifier);
          const parent = target.parent;
          if (parent?.type === 'MemberExpression' && parent.object === target) {
            const property = propertyName(parent);
            if (property !== null && WATCHED.has(property)) {
              checkGlobalUse(property, parent);
            }
            continue;
          }
          // const { localStorage } = window;
          if (
            parent?.type === 'VariableDeclarator' &&
            parent.init === target &&
            parent.id.type === 'ObjectPattern'
          ) {
            for (const property of parent.id.properties) {
              const key =
                property.type === 'Property' &&
                property.key.type === 'Identifier' &&
                !property.computed
                  ? property.key.name
                  : property.type === 'Property' && property.key.type === 'Literal'
                    ? String(property.key.value)
                    : null;
              if (
                key !== null &&
                (STORAGES.has(key) || key === 'indexedDB' || key === 'cookieStore')
              ) {
                context.report({
                  node: property,
                  messageId:
                    key === 'indexedDB' ? 'indexedDB' : key === 'cookieStore' ? 'cookie' : 'access',
                  data: { storage: key, prefix },
                });
              }
            }
          }
        }
      },
    };
  },
};

export default noTokenStorage;
