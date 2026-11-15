// SPDX-License-Identifier: Apache-2.0
import ts from 'typescript';

/**
 * argos/untrusted-text (SEC-60, docs/15 §6.6, docs/16 §9.3, T-SEC-60): collected strings reach
 * the DOM only through SafeText, SafeLink or CodeBlock.
 *
 * The generated API types mark collected strings with the `Untrusted` brand
 * (src/api/untrusted.ts, scripts/gen-api.mjs). This type-aware rule reports an expression whose
 * type carries the brand (also inside arrays, unions and type parameters) when it is
 *
 *   - a JSX child (`<td>{row.service}</td>`, `<>{names}</>`, `{...items}`),
 *   - a JSX attribute value, except the props listed in the `allowed` option (by default `value`
 *     of SafeText and CodeBlock, `href` of SafeLink); `key` is never rendered and always allowed,
 *   - a property of an object spread into JSX props (`<span {...{ title: row.service }} />`),
 *   - a child or prop of `createElement(type, props, ...children)`.
 *
 * Building a string from an Untrusted value inside such an expression does not hide it: template
 * literals, `+` concatenation, `as` casts, `String(value)`, string methods called on an Untrusted
 * value or on text built from it (`value.split('/')[0]`, `Array.from(value).join('')`), and calls
 * that return text and take it as an argument, also inside an object literal
 * (`JSON.stringify({ n: value })`), are reported too.
 *
 * i18n interpolation is reported as well: `{t('key', { name: value })}` (and an attribute such as
 * `aria-label={t('key', { name: value })}`) puts the collected string into the DOM past SafeText
 * (no length cap, no tooltip) and into attributes the checklist forbids
 * (src/security/untrusted.md). Compose instead: `{t('key')} <SafeText value={value} />`, or a
 * translation with a component slot (`<Trans components={{ v: <SafeText value={value} /> }} />`).
 * Translations interpolating only trusted values (numbers, translated labels) stay allowed.
 *
 * @type {import('eslint').Rule.RuleModule}
 */
const untrustedText = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Render collected (Untrusted) strings only through SafeText, SafeLink or CodeBlock',
    },
    schema: [
      {
        type: 'object',
        properties: {
          brand: { type: 'string', minLength: 1 },
          allowed: {
            type: 'object',
            additionalProperties: { type: 'array', items: { type: 'string' }, uniqueItems: true },
          },
        },
        additionalProperties: false,
      },
    ],
    defaultOptions: [
      {
        brand: '__argosUntrusted',
        allowed: { SafeText: ['value'], SafeLink: ['href'], CodeBlock: ['value'] },
      },
    ],
    messages: {
      child:
        'Collected data (Untrusted) must not be rendered as a JSX child; use <SafeText value={…} /> ' +
        '(or SafeLink/CodeBlock) instead (SEC-60, docs/15 §6.6).',
      attribute:
        'Collected data (Untrusted) must not be passed to "{{attribute}}" of <{{element}}>; render it ' +
        'with <SafeText value={…} /> (only SafeText/CodeBlock value and SafeLink href take it, SEC-60).',
      createElement:
        'Collected data (Untrusted) must not be passed to createElement; render it with ' +
        '<SafeText value={…} /> (SEC-60).',
    },
  },
  create(context) {
    const options =
      /** @type {{ brand?: string, allowed?: Record<string, string[]> } | undefined} */ (
        context.options[0]
      );
    const brand = options?.brand ?? '__argosUntrusted';
    const allowed = options?.allowed ?? {
      SafeText: ['value'],
      SafeLink: ['href'],
      CodeBlock: ['value'],
    };
    const services = /** @type {any} */ (context.sourceCode.parserServices);
    if (services?.program == null || services.esTreeNodeToTSNodeMap == null) {
      throw new Error(
        'argos/untrusted-text needs type information: enable typescript-eslint with ' +
          'parserOptions.projectService for the linted files',
      );
    }
    const checker = /** @type {ts.TypeChecker} */ (services.program.getTypeChecker());

    /**
     * @param {any} node ESTree node
     * @returns {ts.Type}
     */
    const typeOf = (node) => checker.getTypeAtLocation(services.esTreeNodeToTSNodeMap.get(node));

    /**
     * @param {ts.Type} type
     * @param {number} depth
     * @returns {boolean}
     */
    const hasBrand = (type, depth = 0) => {
      if (depth > 6) {
        return false;
      }
      if (type.isUnion()) {
        return type.types.some((member) => hasBrand(member, depth + 1));
      }
      if (type.flags & ts.TypeFlags.TypeParameter) {
        const constraint = checker.getBaseConstraintOfType(type);
        return constraint !== undefined && constraint !== type && hasBrand(constraint, depth + 1);
      }
      if (checker.getPropertyOfType(type, brand) !== undefined) {
        return true;
      }
      if (checker.isArrayType(type) || checker.isTupleType(type)) {
        return checker
          .getTypeArguments(/** @type {ts.TypeReference} */ (type))
          .some((argument) => hasBrand(argument, depth + 1));
      }
      return false;
    };

    /**
     * @param {ts.Type} type
     * @returns {boolean}
     */
    const isStringLike = (type) =>
      type.isUnion()
        ? type.types.some((member) => isStringLike(member))
        : (type.flags & ts.TypeFlags.StringLike) !== 0;

    /**
     * A string or a list of strings (`value.split('/')`, `Array.from(value)`): text that can still
     * reach the DOM through a further member access or call.
     *
     * @param {ts.Type} type
     * @returns {boolean}
     */
    const isTextLike = (type) => {
      if (type.isUnion()) {
        return type.types.some((member) => isTextLike(member));
      }
      if (isStringLike(type)) {
        return true;
      }
      if (checker.isArrayType(type) || checker.isTupleType(type)) {
        return checker
          .getTypeArguments(/** @type {ts.TypeReference} */ (type))
          .some((argument) => isStringLike(argument));
      }
      return false;
    };

    /**
     * The first sub-expression of `node` that is or builds a string from an Untrusted value.
     * Bounded by depth (8), so deeply nested expressions are not followed.
     *
     * @param {any} node
     * @param {number} depth
     * @returns {any}
     */
    const findUntrusted = (node, depth = 0) => {
      if (node == null || depth > 8) {
        return null;
      }
      if (node.type === 'JSXEmptyExpression' || node.type.startsWith('JSX')) {
        return null;
      }
      if (hasBrand(typeOf(node))) {
        return node;
      }
      switch (node.type) {
        case 'TemplateLiteral':
          return firstOf(node.expressions, depth);
        case 'BinaryExpression':
          return node.operator === '+' ? firstOf([node.left, node.right], depth) : null;
        case 'LogicalExpression':
          return firstOf([node.left, node.right], depth);
        case 'ConditionalExpression':
          return firstOf([node.consequent, node.alternate], depth);
        case 'ArrayExpression':
          return firstOf(
            node.elements.map((/** @type {any} */ element) =>
              element?.type === 'SpreadElement' ? element.argument : element,
            ),
            depth,
          );
        case 'ObjectExpression':
          // Arguments such as JSON.stringify({ n: value }) or t('key', { name: value }).
          return firstOf(
            node.properties.map((/** @type {any} */ property) =>
              property.type === 'SpreadElement' ? property.argument : property.value,
            ),
            depth,
          );
        case 'TSAsExpression':
        case 'TSTypeAssertion':
        case 'TSNonNullExpression':
        case 'TSSatisfiesExpression':
        case 'ChainExpression':
          return findUntrusted(node.expression, depth + 1);
        case 'MemberExpression':
          // value.split('/')[0], value[0]: a string read from text built on an Untrusted value.
          // Other results (value.length, names.length) carry no text.
          return isStringLike(typeOf(node)) ? findUntrusted(node.object, depth + 1) : null;
        case 'CallExpression': {
          // value.trim(), value.split('/'), Array.from(value).join(''), JSON.stringify({ n: value }),
          // String(value), t('key', { name: value }): text built from the callee object or from an
          // argument. Calls that return no text (value.includes(x), names.map(() => <li />)) are
          // not followed.
          if (!isTextLike(typeOf(node))) {
            return null;
          }
          const { callee } = node;
          const fromCallee =
            callee.type === 'MemberExpression' ? findUntrusted(callee.object, depth + 1) : null;
          if (fromCallee !== null) {
            return fromCallee;
          }
          return firstOf(
            node.arguments.map((/** @type {any} */ argument) =>
              argument.type === 'SpreadElement' ? argument.argument : argument,
            ),
            depth,
          );
        }
        default:
          return null;
      }
    };

    /**
     * @param {any[]} nodes
     * @param {number} depth
     * @returns {any}
     */
    const firstOf = (nodes, depth) => {
      for (const child of nodes) {
        const found = findUntrusted(child, depth + 1);
        if (found !== null) {
          return found;
        }
      }
      return null;
    };

    /**
     * @param {any} name JSXIdentifier | JSXMemberExpression | JSXNamespacedName
     * @returns {string}
     */
    const elementName = (name) => {
      switch (name.type) {
        case 'JSXIdentifier':
          return name.name;
        case 'JSXMemberExpression':
          return `${elementName(name.object)}.${String(name.property.name)}`;
        case 'JSXNamespacedName':
          return `${String(name.namespace.name)}:${String(name.name.name)}`;
        default:
          return '?';
      }
    };

    /**
     * @param {any} name JSXIdentifier | JSXNamespacedName
     * @returns {string}
     */
    const attributeName = (name) =>
      name.type === 'JSXNamespacedName'
        ? `${String(name.namespace.name)}:${String(name.name.name)}`
        : String(name.name);

    /**
     * @param {string} element
     * @param {string} attribute
     * @returns {boolean}
     */
    const isAllowed = (element, attribute) =>
      attribute === 'key' || (allowed[element]?.includes(attribute) ?? false);

    /**
     * Reports the properties of a spread props object whose types carry the brand.
     *
     * @param {any} expression spread argument
     * @param {string} element
     * @param {string} messageId
     */
    const checkPropsObject = (expression, element, messageId) => {
      if (expression?.type === 'ObjectExpression') {
        for (const property of expression.properties) {
          if (property.type === 'SpreadElement') {
            checkPropsObject(property.argument, element, messageId);
            continue;
          }
          const key =
            property.key.type === 'Identifier' && !property.computed
              ? property.key.name
              : property.key.type === 'Literal'
                ? String(property.key.value)
                : null;
          if (key !== null && isAllowed(element, key)) {
            continue;
          }
          const found = findUntrusted(property.value);
          if (found !== null) {
            context.report({ node: found, messageId, data: { element, attribute: key ?? '?' } });
          }
        }
        return;
      }
      if (expression == null) {
        return;
      }
      const type = typeOf(expression);
      for (const property of checker.getPropertiesOfType(type)) {
        if (isAllowed(element, property.name)) {
          continue;
        }
        if (
          hasBrand(
            checker.getTypeOfSymbolAtLocation(
              property,
              services.esTreeNodeToTSNodeMap.get(expression),
            ),
          )
        ) {
          context.report({
            node: expression,
            messageId,
            data: { element, attribute: property.name },
          });
        }
      }
    };

    return {
      /** @param {any} node */
      JSXExpressionContainer(node) {
        const parent = /** @type {any} */ (node).parent;
        if (parent.type !== 'JSXElement' && parent.type !== 'JSXFragment') {
          return; // attribute values are handled by JSXAttribute
        }
        const found = findUntrusted(node.expression);
        if (found !== null) {
          context.report({ node: found, messageId: 'child' });
        }
      },
      /** @param {any} node */
      JSXSpreadChild(node) {
        const found = findUntrusted(/** @type {any} */ (node).expression);
        if (found !== null) {
          context.report({ node: found, messageId: 'child' });
        }
      },
      /** @param {any} node */
      JSXAttribute(node) {
        const attribute = /** @type {any} */ (node);
        if (attribute.value?.type !== 'JSXExpressionContainer') {
          return;
        }
        const element = elementName(attribute.parent.name);
        const name = attributeName(attribute.name);
        if (isAllowed(element, name)) {
          return;
        }
        const found = findUntrusted(attribute.value.expression);
        if (found !== null) {
          context.report({
            node: found,
            messageId: 'attribute',
            data: { element, attribute: name },
          });
        }
      },
      /** @param {any} node */
      JSXSpreadAttribute(node) {
        const spread = /** @type {any} */ (node);
        checkPropsObject(spread.argument, elementName(spread.parent.name), 'attribute');
      },
      CallExpression(node) {
        const call = /** @type {any} */ (node);
        const { callee } = call;
        const isCreateElement =
          (callee.type === 'Identifier' && callee.name === 'createElement') ||
          (callee.type === 'MemberExpression' &&
            !callee.computed &&
            callee.property.name === 'createElement');
        if (!isCreateElement || call.arguments.length < 2) {
          return;
        }
        const [type, props, ...children] = call.arguments;
        const element =
          type.type === 'Identifier'
            ? type.name
            : type.type === 'Literal'
              ? String(type.value)
              : '?';
        checkPropsObject(props, element, 'createElement');
        for (const child of children) {
          const found = findUntrusted(child.type === 'SpreadElement' ? child.argument : child);
          if (found !== null) {
            context.report({ node: found, messageId: 'createElement' });
          }
        }
      },
    };
  },
};

export default untrustedText;
