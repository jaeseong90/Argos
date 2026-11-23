// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';

import { untrusted } from '../api/untrusted';
import type { FilterValueLabel } from '../components/FilterChipBar';
import { installDomStubs } from '../components/testing/dom';
import { withI18n } from '../components/testing/withI18n';

import { FilterLabelProvider, useRegisterFilterLabels } from './filterLabels';
import { UrlFilterChips } from './Notices';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U14' };

installDomStubs();

const SERVICE_KEY = '18446744073709551610';
const label: FilterValueLabel = (f) =>
  f.field === 'service' && f.value === SERVICE_KEY ? untrusted('GET /api/orders/{id}') : null;

function Screen({ named }: { readonly named: boolean }) {
  useRegisterFilterLabels(named ? label : null);
  return <p>screen</p>;
}

function renderAt(path: string, named: boolean): void {
  const Wrapper = withI18n('en');
  const router = createMemoryRouter(
    [
      {
        path: '*',
        element: (
          <FilterLabelProvider>
            <UrlFilterChips />
            <Screen named={named} />
          </FilterLabelProvider>
        ),
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <Wrapper>
      <RouterProvider router={router} />
    </Wrapper>,
  );
}

describe('filter chip names of the current screen (routes/filterLabels.tsx)', () => {
  it('shows the name the screen registered and keeps the key in data-value', async () => {
    renderAt(`/apps/1/xlog?f.service=${SERVICE_KEY}&f.status=500`, true);
    const chips = await screen.findAllByTestId('filter-chip');
    expect(chips).toHaveLength(2);
    expect(await screen.findByText('GET /api/orders/{id}')).toBeInTheDocument();
    expect(chips[0]).toHaveAttribute('data-value', SERVICE_KEY);
    expect(chips[1]?.querySelector('.filter-chip__value')).toHaveTextContent(/^500$/);
  });

  it('shows the keys when the screen registers no names', async () => {
    renderAt(`/apps/1/xlog?f.service=${SERVICE_KEY}`, false);
    const chip = await screen.findByTestId('filter-chip');
    expect(chip.querySelector('.filter-chip__value')).toHaveTextContent(SERVICE_KEY);
    expect(chip).not.toHaveAttribute('data-named');
  });
});
