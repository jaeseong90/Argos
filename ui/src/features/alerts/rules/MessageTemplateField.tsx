// SPDX-License-Identifier: Apache-2.0
//
// Message template input of the rule editor (docs/15 §9.9 "허용 변수만 자동완성, 금지 변수 입력 시
// 즉시 오류", docs/16 §6.11, SEC-39): typing `{{` opens the list of allowed variables filtered by
// what follows (arrow keys + Enter or click insert `{{name}}`), the variable buttons below insert at
// the caret, and a forbidden reference is reported as soon as it is typed. The text is plain input
// (a textarea value); nothing of it is rendered as markup.
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';

import {
  forbiddenReferences,
  insertVariable,
  openReference,
  suggestVariables,
  TEMPLATE_MAX_CHARS,
  TEMPLATE_VARIABLES,
  type TemplateVariable,
} from './messageTemplate';

export interface MessageTemplateFieldProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  /** Error text to show (forbidden variable from the client check or the server's 422). */
  readonly error: string | null;
  readonly labelId: string;
}

export function MessageTemplateField({
  value,
  onChange,
  error,
  labelId,
}: MessageTemplateFieldProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const listId = useId();
  const errorId = useId();
  const ref = useRef<HTMLTextAreaElement>(null);
  const [caret, setCaret] = useState(0);
  const [active, setActive] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const open = dismissed ? null : openReference(value, caret);
  const suggestions = open === null ? [] : suggestVariables(open.partial);
  const forbidden = forbiddenReferences(value);

  const insert = (variable: TemplateVariable): void => {
    const element = ref.current;
    const at = element?.selectionStart ?? caret;
    const next = insertVariable(value, at, variable);
    onChange(next.text);
    setCaret(next.caret);
    setActive(0);
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(next.caret, next.caret);
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (suggestions.length === 0) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((index) => (index + 1) % suggestions.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((index) => (index - 1 + suggestions.length) % suggestions.length);
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      const chosen = suggestions[Math.min(active, suggestions.length - 1)];
      if (chosen !== undefined) {
        event.preventDefault();
        insert(chosen);
      }
    } else if (event.key === 'Escape') {
      event.preventDefault();
      setDismissed(true);
    }
  };

  return (
    <div className="alert-rule-template">
      <textarea
        ref={ref}
        className="alert-rule-input alert-rule-template__input"
        rows={3}
        maxLength={TEMPLATE_MAX_CHARS}
        value={value}
        aria-labelledby={labelId}
        aria-invalid={error !== null}
        aria-describedby={error === null ? undefined : errorId}
        aria-autocomplete="list"
        aria-controls={suggestions.length > 0 ? listId : undefined}
        data-testid="alert-rule-field-message_template"
        onChange={(event) => {
          onChange(event.target.value);
          setCaret(event.target.selectionStart);
          setDismissed(false);
          setActive(0);
        }}
        onSelect={(event) => {
          setCaret(event.currentTarget.selectionStart);
        }}
        onKeyDown={onKeyDown}
      />
      {suggestions.length > 0 ? (
        <ul
          id={listId}
          className="alert-rule-template__suggestions"
          aria-label={t('alerts:rules.template.suggestions')}
          data-testid="alert-rule-template-suggestions"
        >
          {suggestions.map((variable, index) => (
            <li key={variable}>
              <button
                type="button"
                tabIndex={-1}
                className={`alert-rule-template__suggestion${index === active ? ' alert-rule-template__suggestion--active' : ''}`}
                data-active={index === active ? 'true' : undefined}
                data-testid={`alert-rule-template-suggestion-${variable}`}
                onMouseDown={(event) => {
                  // Keep the focus (and caret) in the textarea.
                  event.preventDefault();
                  insert(variable);
                }}
              >
                {`{{${variable}}}`}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="alert-rule-template__variables">
        <span className="alert-rule-template__hint">{t('alerts:rules.template.variables')}</span>
        {TEMPLATE_VARIABLES.map((variable) => (
          <button
            key={variable}
            type="button"
            className="alert-rule-chip"
            data-testid={`alert-rule-template-variable-${variable}`}
            onClick={() => {
              insert(variable);
            }}
          >
            {`{{${variable}}}`}
          </button>
        ))}
      </div>
      {error !== null ? (
        <p
          id={errorId}
          className="alert-rule-field__error"
          role="alert"
          data-testid="alert-rule-template-error"
          data-references={forbidden.length}
        >
          {error}
          {forbidden.length > 0 ? (
            <>
              {' '}
              {t('alerts:rules.errors.templateForbiddenRefs')}{' '}
              <code data-testid="alert-rule-template-forbidden">{forbidden.join(', ')}</code>
            </>
          ) : null}
        </p>
      ) : null}
    </div>
  );
}
