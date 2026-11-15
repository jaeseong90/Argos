<!-- SPDX-License-Identifier: Apache-2.0 -->

# Untrusted input: component review checklist

Source: docs/16 §9.1 (SEC-60, SEC-61), docs/14 §2.10 (DSN-14-13), docs/15 §6.6 (DSN-15-07).

Every string in this list can be chosen by an application under monitoring, an agent, a user of
that application or anyone who can craft a link. The server does not escape it (docs/14 §2.10:
no double escaping). The UI renders it **only as a text node**.

## The list (docs/16 §9.1)

| Group               | Strings                                                                                               |
| ------------------- | ----------------------------------------------------------------------------------------------------- |
| Transactions        | service names, URLs and paths, HTTP header names and values, `user_id`, external call hosts and paths |
| SQL                 | normalized statements, original statements, bind parameters                                           |
| Errors              | exception classes, exception messages, stack frames                                                   |
| Runtime             | thread dump and environment dump bodies, JVM options, version strings                                 |
| Names               | instance, host, thread, pool and connector names; application, environment and group names            |
| Alerts              | alert rule, channel and template names                                                                |
| Users               | user names and notes (also when the contract does not flag them, e.g. `UserView.username`)            |
| Commands and events | command results, deploy event titles and details                                                      |
| Dictionaries        | every dictionary string (service, SQL, frame, ...)                                                    |
| Links               | every query value of a deep link (`f.*`, `q`, `env`, `group`, ...): anyone can craft a URL            |

## How the code enforces it

- The OpenAPI contract marks these properties `x-argos-untrusted: true`. `pnpm gen:api` turns them
  into the branded type `Untrusted` (`src/api/untrusted.ts`); `untrusted(value)` brands values
  that come from elsewhere (WebSocket frames after zod validation, URL state, test fixtures).
- Values parsed from the URL (`src/state/urlState.ts`: filter values, `env`, `group`, `q`) are
  branded `Untrusted` as well.
- ESLint `argos/untrusted-text` rejects an `Untrusted` value anywhere except
  `<SafeText value>`, `<CodeBlock value>` and `<SafeLink href>` (also through templates,
  concatenation, `String()`, string methods, member access on derived text such as
  `value.split('/')[0]`, chained calls such as `Array.from(value).join('')` and call arguments
  including object literals such as `JSON.stringify({ n: value })`). `react/no-danger`,
  `no-restricted-properties` (`innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`)
  and `argos/no-raw-href` are errors. Inline `eslint-disable` comments are ignored in `src/`;
  exceptions go to `eslint-allow.json` with a reason.
- i18n interpolation of a collected string is rejected too: `{t('key', { name: value })}` and
  `aria-label={t('key', { name: value })}` are lint errors. React would still render the result
  as a text node (`escapeValue: false` only avoids double escaping), but the value would bypass
  SafeText (length cap, tooltip) and end up in the attributes item 2 below forbids. Compose the
  sentence instead: `{t('key')} <SafeText value={value} />`, or a translation with a component
  slot, `<Trans i18nKey="key" components={{ v: <SafeText value={value} /> }} />`. Interpolating
  trusted values (numbers, translated field labels) is fine.

## Review checklist for a component

1. Does the component receive any string from the list above (directly, inside an object, from a
   WebSocket frame, from the URL)? Then its type is `Untrusted` or the value passes `untrusted()`
   at the boundary.
2. Is it rendered through `SafeText`, `CodeBlock` (SQL, stacks) or `SafeLink` (external URLs,
   log links) only? No `title`, `aria-label`, `placeholder` or `alt` built from it.
3. Is no HTML string assembled anywhere (`dangerouslySetInnerHTML`, `innerHTML`, a highlighter
   returning HTML)?
4. Are links built with `safeHref()` (`http:`, `https:` and internal paths only)?
5. Does the component test feed the XSS seed payloads (ADR-0004 C-45: union of docs/16 §9.4 and
   docs/18 §4.3, `src/components/testing/xssPayloads.ts`) and assert text rendering and
   `window.__xss === undefined`?
6. Are user names shown through `SafeText` although the contract does not flag them?
