// SPDX-License-Identifier: Apache-2.0

/**
 * argos/no-raw-href (docs/16 §9.2·§9.3, SEC-60, T-SEC-60): a link target is either a literal
 * internal path or the result of safeHref(), which lets through only http(s) URLs and internal
 * paths (`javascript:`, `data:`, `vbscript:` and protocol-relative `//host` become undefined).
 *
 * Checked: `href` / `xlinkHref` / `xlink:href` on every intrinsic element (`<a>`, `<area>`, SVG
 * `<a>`/`<use>`), `href` on Ant Design components (any JSX element whose name is bound to an
 * import from `antd`, including `Typography.Link`, `Button`, `Breadcrumb.Item` and names
 * destructured from them), and `href` keys of object literals inside the props of such Ant Design
 * components (`<Breadcrumb items={[{ title, href }]} />`). With type information, spreading an
 * object that has an `href` property into one of these elements is reported too, because the
 * value cannot be verified.
 *
 * Accepted values: a string literal that is an internal path (`/x` but not `//x` or `/\x`,
 * `#x`, `?x`, `./x`, `../x`), a template literal whose static start is such a path and is more
 * than a lone `/` (`/apps/${id}` but not `/${path}`), `undefined`, a call of a sanitizer
 * (option `sanitizers`, default `safeHref`), a `const` initialised with an accepted value, and
 * conditional/logical combinations of accepted values. SafeLink takes any string in `href`
 * because it calls safeHref() itself; it is not an intrinsic element and not checked here.
 *
 * @type {import('eslint').Rule.RuleModule}
 */
const noRawHref = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Require link targets to be literal internal paths or the result of safeHref()',
    },
    schema: [
      {
        type: 'object',
        properties: {
          sanitizers: { type: 'array', items: { type: 'string' }, uniqueItems: true },
          antdSources: { type: 'array', items: { type: 'string' }, uniqueItems: true },
        },
        additionalProperties: false,
      },
    ],
    defaultOptions: [{ sanitizers: ['safeHref'], antdSources: ['^antd(?:/|$)'] }],
    messages: {
      raw:
        '"{{attribute}}" of <{{element}}> must be a literal internal path or safeHref(…) ' +
        '(javascript:, data: and //host URLs execute or leave the site; docs/16 §9.3). ' +
        'For collected URLs use <SafeLink href={…}>.',
      spread:
        'Props spread into <{{element}}> include "href", which cannot be verified here; pass ' +
        'href={safeHref(…)} explicitly (docs/16 §9.3).',
    },
  },
  create(context) {
    const options = /** @type {{ sanitizers?: string[], antdSources?: string[] } | undefined} */ (
      context.options[0]
    );
    const sanitizers = new Set(options?.sanitizers ?? ['safeHref']);
    const antdSources = (options?.antdSources ?? ['^antd(?:/|$)']).map(
      (source) => new RegExp(source),
    );
    const services = /** @type {any} */ (context.sourceCode.parserServices);
    const checker = services?.program?.getTypeChecker?.() ?? null;

    /**
     * @param {string} value
     * @param {boolean} prefixOnly the static start of a template literal
     * @returns {boolean}
     */
    const isInternalPath = (value, prefixOnly) => {
      if (/^[#?]/.test(value) || /^\.\.?\//.test(value)) {
        return true;
      }
      if (prefixOnly) {
        return /^\/[^/\\]/.test(value); // a lone "/" before ${…} could become "//host"
      }
      return /^\/(?![/\\])/.test(value);
    };

    /**
     * @param {any} node
     * @returns {any} the variable a reference resolves to
     */
    const resolveVariable = (node) => {
      /** @type {any} */
      let scope = context.sourceCode.getScope(node);
      while (scope !== null) {
        const variable = scope.set.get(node.name);
        if (variable !== undefined) {
          return variable;
        }
        scope = scope.upper;
      }
      return null;
    };

    /**
     * @param {any} node expression
     * @param {number} depth
     * @returns {boolean}
     */
    const isSafe = (node, depth = 0) => {
      if (node == null || depth > 6) {
        return false;
      }
      switch (node.type) {
        case 'Literal':
          return (
            node.value === null ||
            (typeof node.value === 'string' && isInternalPath(node.value, false))
          );
        case 'TemplateLiteral': {
          const start = node.quasis[0]?.value.cooked ?? '';
          return node.expressions.length === 0
            ? isInternalPath(start, false)
            : isInternalPath(start, true);
        }
        case 'Identifier': {
          if (node.name === 'undefined') {
            return true;
          }
          const variable = resolveVariable(node);
          const definition = variable?.defs.length === 1 ? variable.defs[0] : null;
          if (
            definition?.type === 'Variable' &&
            definition.parent?.kind === 'const' &&
            definition.node.id.type === 'Identifier'
          ) {
            return isSafe(definition.node.init, depth + 1);
          }
          return false;
        }
        case 'CallExpression': {
          const { callee } = node;
          if (callee.type === 'Identifier') {
            return sanitizers.has(callee.name);
          }
          return (
            callee.type === 'MemberExpression' &&
            !callee.computed &&
            sanitizers.has(callee.property.name)
          );
        }
        case 'ConditionalExpression':
          return isSafe(node.consequent, depth + 1) && isSafe(node.alternate, depth + 1);
        case 'LogicalExpression':
          return node.operator === '&&'
            ? isSafe(node.right, depth + 1)
            : isSafe(node.left, depth + 1) && isSafe(node.right, depth + 1);
        case 'TSAsExpression':
        case 'TSTypeAssertion':
        case 'TSNonNullExpression':
        case 'TSSatisfiesExpression':
        case 'ChainExpression':
          return isSafe(node.expression, depth + 1);
        default:
          return false;
      }
    };

    /**
     * @param {any} node an Identifier used as (the root of) a JSX element name
     * @param {number} depth
     * @returns {boolean} the name is bound to an import from Ant Design
     */
    const isAntdBinding = (node, depth = 0) => {
      if (depth > 4) {
        return false;
      }
      const variable = resolveVariable(node);
      const definition = variable?.defs.length === 1 ? variable.defs[0] : null;
      if (definition == null) {
        return false;
      }
      if (definition.type === 'ImportBinding') {
        const source = String(definition.parent.source.value);
        return antdSources.some((pattern) => pattern.test(source));
      }
      if (definition.type === 'Variable') {
        // const { Link } = Typography;  const Link = Typography.Link;
        let init = definition.node.init;
        while (init?.type === 'MemberExpression') {
          init = init.object;
        }
        return init?.type === 'Identifier' && isAntdBinding(init, depth + 1);
      }
      return false;
    };

    /**
     * @param {any} name JSX element name
     * @returns {{ label: string, intrinsic: boolean, antd: boolean }}
     */
    const describeElement = (name) => {
      if (name.type === 'JSXIdentifier') {
        const intrinsic = /^[a-z]/.test(name.name);
        return {
          label: name.name,
          intrinsic,
          antd: !intrinsic && isAntdBinding(name),
        };
      }
      if (name.type === 'JSXMemberExpression') {
        let root = name.object;
        let label = String(name.property.name);
        while (root.type === 'JSXMemberExpression') {
          label = `${String(root.property.name)}.${label}`;
          root = root.object;
        }
        return {
          label: `${String(root.name)}.${label}`,
          intrinsic: false,
          antd: isAntdBinding(root),
        };
      }
      // svg:a and the like
      return {
        label: `${String(name.namespace.name)}:${String(name.name.name)}`,
        intrinsic: true,
        antd: false,
      };
    };

    /**
     * Checks `href` keys of object literals anywhere inside an Ant Design prop value (not inside
     * nested JSX elements, which are checked on their own).
     *
     * @param {any} node
     * @param {string} element
     */
    const checkNestedHrefs = (node, element) => {
      if (
        node == null ||
        typeof node !== 'object' ||
        node.type === 'JSXElement' ||
        node.type === 'JSXFragment'
      ) {
        return;
      }
      if (node.type === 'Property' && !node.computed) {
        const key = node.key.type === 'Identifier' ? node.key.name : node.key.value;
        if (key === 'href' && !isSafe(node.value)) {
          context.report({
            node: node.value,
            messageId: 'raw',
            data: { element, attribute: 'href' },
          });
        }
      }
      for (const [key, child] of Object.entries(node)) {
        if (key === 'parent' || key === 'loc' || key === 'range') {
          continue;
        }
        if (Array.isArray(child)) {
          child.forEach((item) => {
            checkNestedHrefs(item, element);
          });
        } else if (child !== null && typeof child === 'object' && typeof child.type === 'string') {
          checkNestedHrefs(child, element);
        }
      }
    };

    return {
      /** @param {any} node */
      JSXOpeningElement(node) {
        const opening = /** @type {any} */ (node);
        const element = describeElement(opening.name);
        if (!element.intrinsic && !element.antd) {
          return;
        }
        for (const attribute of opening.attributes) {
          if (attribute.type === 'JSXSpreadAttribute') {
            if (attribute.argument.type === 'ObjectExpression') {
              checkNestedHrefs(attribute.argument, element.label);
            } else if (checker !== null) {
              const type = checker.getTypeAtLocation(
                services.esTreeNodeToTSNodeMap.get(attribute.argument),
              );
              if (checker.getPropertyOfType(type, 'href') !== undefined) {
                context.report({
                  node: attribute,
                  messageId: 'spread',
                  data: { element: element.label },
                });
              }
            }
            continue;
          }
          const name =
            attribute.name.type === 'JSXNamespacedName'
              ? `${String(attribute.name.namespace.name)}:${String(attribute.name.name.name)}`
              : String(attribute.name.name);
          const isLinkAttribute = element.intrinsic
            ? name === 'href' || name === 'xlinkHref' || name === 'xlink:href'
            : name === 'href';
          if (!isLinkAttribute) {
            if (element.antd && attribute.value?.type === 'JSXExpressionContainer') {
              checkNestedHrefs(attribute.value.expression, element.label);
            }
            continue;
          }
          const { value } = attribute;
          const safe =
            value === null
              ? false
              : value.type === 'Literal'
                ? isSafe(value)
                : value.type === 'JSXExpressionContainer'
                  ? isSafe(value.expression)
                  : false;
          if (!safe) {
            context.report({
              node: value ?? attribute,
              messageId: 'raw',
              data: { element: element.label, attribute: name },
            });
          }
        }
      },
    };
  },
};

export default noRawHref;
