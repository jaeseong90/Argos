// SPDX-License-Identifier: Apache-2.0
//
// SQL, stack trace and configuration snippets (docs/15 §6.6, docs/16 §9.3 SafeCode, SEC-61). The
// tokenizer output becomes React elements (one text node per token); no HTML string is built.
// Long content is folded; the copy button copies the original value.
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { MISSING_DASH } from '../utils/missing';

import { tokenize, toLines, type CodeLanguage } from './codeTokenizer';

import './safe.css';

/** Lines shown before the rest is folded behind "Show all N lines". */
export const CODE_BLOCK_FOLD_LINES = 20;
const COPY_FEEDBACK_MS = 2000;

export interface CodeBlockProps {
  /** The collected text; untrusted values are allowed here (argos/untrusted-text). */
  readonly value: string | null | undefined;
  readonly language?: CodeLanguage;
  /** Lines shown while folded. Default 20; 0 = never fold. */
  readonly foldLines?: number;
  /** Show the copy button. Default true. */
  readonly copyable?: boolean;
  /** Accessible name of the block (defaults to the language label). */
  readonly label?: string;
  readonly 'data-testid'?: string;
}

type CopyState = 'idle' | 'copied' | 'failed';

/** Renders tokenized code with copy and fold controls. */
export function CodeBlock({
  value,
  language = 'text',
  foldLines = CODE_BLOCK_FOLD_LINES,
  copyable = true,
  label,
  'data-testid': testId,
}: CodeBlockProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const [copyState, setCopyState] = useState<CopyState>('idle');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const text = value ?? '';
  const lines = useMemo(() => toLines(tokenize(text, language)), [text, language]);

  useEffect(
    () => () => {
      if (timer.current !== undefined) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  const languageLabel = t(`component.codeBlock.language.${language}`);
  const name = label ?? languageLabel;
  if (value === null || value === undefined) {
    return (
      <span className="safe-text safe-text--missing" title={t('component.missing.noData')}>
        {MISSING_DASH}
      </span>
    );
  }

  const folded = foldLines > 0 && lines.length > foldLines && !expanded;
  const shown = folded ? lines.slice(0, foldLines) : lines;

  const copy = async (): Promise<void> => {
    if (timer.current !== undefined) {
      clearTimeout(timer.current);
    }
    try {
      const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
      if (clipboard === undefined) {
        throw new Error('Clipboard API unavailable');
      }
      await clipboard.writeText(text);
      setCopyState('copied');
    } catch {
      setCopyState('failed');
    }
    timer.current = setTimeout(() => {
      setCopyState('idle');
    }, COPY_FEEDBACK_MS);
  };

  return (
    <figure className="code-block" aria-label={name} data-testid={testId ?? 'code-block'}>
      <div className="code-block__toolbar">
        <span className="code-block__label">{languageLabel}</span>
        {copyable ? (
          <button
            type="button"
            className="code-block__button"
            onClick={() => {
              void copy();
            }}
            data-testid="code-block-copy"
          >
            {copyState === 'copied'
              ? t('component.codeBlock.copied')
              : copyState === 'failed'
                ? t('component.codeBlock.copyFailed')
                : t('component.codeBlock.copy')}
          </button>
        ) : null}
        <span className="visually-hidden" role="status">
          {copyState === 'copied' ? t('component.codeBlock.copied') : ''}
        </span>
      </div>
      <pre className="code-block__pre">
        <code>
          {shown.map((line, lineIndex) => (
            <span key={lineIndex} className="code-block__line">
              {line.map((token, tokenIndex) =>
                token.type === 'text' ? (
                  <span key={tokenIndex}>{token.text}</span>
                ) : (
                  <span key={tokenIndex} className={`tok-${token.type}`}>
                    {token.text}
                  </span>
                ),
              )}
            </span>
          ))}
        </code>
      </pre>
      {foldLines > 0 && lines.length > foldLines ? (
        <button
          type="button"
          className="code-block__fold"
          aria-expanded={expanded}
          onClick={() => {
            setExpanded((current) => !current);
          }}
          data-testid="code-block-fold"
        >
          {expanded
            ? t('component.codeBlock.collapse')
            : t('component.codeBlock.showAll', { count: lines.length })}
        </button>
      ) : null}
    </figure>
  );
}
