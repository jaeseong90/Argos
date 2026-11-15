// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CodeBlock } from './CodeBlock';
import { tokenize } from './codeTokenizer';
import { SafeLink } from './SafeLink';
import { SafeText } from './SafeText';
import { withI18n } from './testing/withI18n';
import {
  findInjectedMarkup,
  LISTED_XSS_PAYLOADS,
  XSS_PAYLOADS,
  xssFired,
} from './testing/xssPayloads';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-61' };

const wrapper = withI18n('en');
const JS = 'javascript';

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
  vi.restoreAllMocks();
});

describe('XSS seed set (ADR-0004 C-45)', () => {
  it('is the union of docs/16 §9.4 (10) and docs/18 §4.3 (4) without duplicates: 12 strings', () => {
    expect(LISTED_XSS_PAYLOADS.filter((p) => p.source === '16 §9.4')).toHaveLength(10);
    expect(LISTED_XSS_PAYLOADS.filter((p) => p.source === '18 §4.3')).toHaveLength(4);
    expect(XSS_PAYLOADS).toHaveLength(12);
  });
});

describe('SafeText (docs/15 §6.6)', () => {
  it.each(XSS_PAYLOADS)('T-UI-005 renders %j as one text node', (payload) => {
    const { container } = render(<SafeText value={payload} data-testid="safe" />, { wrapper });
    const span = screen.getByTestId('safe');
    expect(span.childNodes).toHaveLength(1);
    expect(span.firstChild?.nodeType).toBe(Node.TEXT_NODE);
    expect(span.textContent).toBe(payload);
    expect(span.getAttribute('title')).toBe(payload);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-007 renders null as the dash with the No data tooltip', () => {
    render(<SafeText value={null} data-testid="safe" />, { wrapper });
    expect(screen.getByTestId('safe')).toHaveTextContent('—');
    expect(screen.getByTestId('safe')).toHaveAttribute('title', 'No data');
  });

  it('clamps lines with a class and the CSSOM line clamp, truncating very long values', () => {
    render(<SafeText value={'x'.repeat(5000)} maxLines={3} mono data-testid="safe" />, { wrapper });
    const span = screen.getByTestId('safe');
    expect(span).toHaveClass('safe-text--clamp', 'safe-text--mono');
    expect(span.style.webkitLineClamp).toBe('3');
    expect(span.textContent).toHaveLength(4097);
    expect(span.textContent.endsWith('…')).toBe(true);
  });
});

describe('SafeLink (docs/16 §9.3)', () => {
  it.each([
    `${JS}:window.__xss=1`,
    'data:text/html,<script>window.__xss=1</script>',
    'vbscript:msgbox(1)',
    '//evil.example.test',
  ])('T-SEC-61 renders %j as text without a link', (href) => {
    const { container } = render(<SafeLink href={href} data-testid="link" />, { wrapper });
    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByTestId('link')).toHaveTextContent(href);
    expect(findInjectedMarkup(container)).toEqual([]);
  });

  it.each(XSS_PAYLOADS)('T-UI-005 never turns %j into a link or markup', (payload) => {
    const { container } = render(<SafeLink href={payload} />, { wrapper });
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent).toContain(payload);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-SEC-61 opens external links in a new tab with rel=noopener noreferrer', () => {
    render(<SafeLink href="https://logs.example.test/q?a=1">logs</SafeLink>, { wrapper });
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', 'https://logs.example.test/q?a=1');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('keeps internal links in the tab and hands plain clicks to onNavigate', () => {
    const onNavigate = vi.fn();
    render(
      <SafeLink href="/apps/1/xlog" onNavigate={onNavigate}>
        xlog
      </SafeLink>,
      { wrapper },
    );
    const link = screen.getByRole('link');
    expect(link).not.toHaveAttribute('target');
    fireEvent.click(link);
    expect(onNavigate).toHaveBeenCalledWith('/apps/1/xlog');
    fireEvent.click(link, { ctrlKey: true });
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });
});

describe('CodeBlock (docs/15 §6.6)', () => {
  it.each(XSS_PAYLOADS)('T-UI-005 renders %j as text in every language', (payload) => {
    for (const language of ['sql', 'stack', 'config', 'text'] as const) {
      const { container, unmount } = render(<CodeBlock value={payload} language={language} />, {
        wrapper,
      });
      expect(container.querySelector('code')?.textContent).toBe(payload.replace(/\r?\n$/, ''));
      expect(findInjectedMarkup(container)).toEqual([]);
      unmount();
    }
    expect(xssFired()).toBe(false);
  });

  it('highlights SQL with spans built from tokens', () => {
    const sql = "SELECT name FROM users WHERE id = ? AND note = 'it''s' -- c";
    const { container } = render(<CodeBlock value={sql} language="sql" />, { wrapper });
    expect(container.querySelector('code')?.textContent).toBe(sql);
    expect([...container.querySelectorAll('.tok-keyword')].map((n) => n.textContent)).toEqual([
      'SELECT',
      'FROM',
      'WHERE',
      'AND',
    ]);
    expect(container.querySelector('.tok-placeholder')?.textContent).toBe('?');
    expect(container.querySelector('.tok-string')?.textContent).toBe("'it''s'");
    expect(container.querySelector('.tok-comment')?.textContent).toBe('-- c');
  });

  it('folds long content and expands it', () => {
    const text = Array.from({ length: 30 }, (_, i) => `line ${String(i)}`).join('\n');
    const { container } = render(<CodeBlock value={text} />, { wrapper });
    expect(container.querySelectorAll('.code-block__line')).toHaveLength(20);
    const fold = screen.getByTestId('code-block-fold');
    expect(fold).toHaveTextContent('Show all 30 lines');
    expect(fold).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(fold);
    expect(container.querySelectorAll('.code-block__line')).toHaveLength(30);
    expect(fold).toHaveAttribute('aria-expanded', 'true');
  });

  it('copies the original value and reports when the clipboard is unavailable', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const value = 'at <script>window.__xss=1</script>';
    render(<CodeBlock value={value} language="stack" />, { wrapper });
    fireEvent.click(screen.getByTestId('code-block-copy'));
    expect(await screen.findByText('Copied', { selector: 'button' })).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(value);
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    fireEvent.click(screen.getByTestId('code-block-copy'));
    expect(await screen.findByText('Copy failed')).toBeInTheDocument();
  });

  it('renders the dash for a missing value', () => {
    const { container } = render(<CodeBlock value={null} />, { wrapper });
    expect(container).toHaveTextContent('—');
  });

  it('keeps token texts equal to the input', () => {
    expect(
      tokenize('a\nb', 'text')
        .map((t) => t.text)
        .join(''),
    ).toBe('a\nb');
  });
});
