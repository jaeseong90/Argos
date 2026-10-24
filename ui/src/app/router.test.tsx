// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout } from '../../test/canvasMock';
import { createI18n } from '../i18n';
import common from '../locales/en/common.json';

import { createRoutes } from './router';

function renderAt(path: string) {
  const router = createMemoryRouter(createRoutes('1.2.3'), { initialEntries: [path] });
  return render(
    <I18nextProvider i18n={createI18n('en')}>
      <RouterProvider router={router} />
    </I18nextProvider>,
  );
}

beforeEach(() => {
  installCanvasMock();
  mockLayout(1000, 420);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('routes', () => {
  it('renders the start page at /', async () => {
    renderAt('/');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(common.app.name);
  });

  it('lazy-loads the XLog PoC at /xlog-poc', async () => {
    renderAt('/xlog-poc?n=100');
    expect(await screen.findByTestId('xlog-poc-page')).toBeInTheDocument();
    expect(await screen.findByTestId('xlog-canvas')).toBeInTheDocument();
  });

  it('says that an unknown path does not exist and links to the start page', async () => {
    renderAt('/no/such/page');
    expect(await screen.findByTestId('route-not-found')).toHaveTextContent(
      common.route.notFound.title,
    );
    expect(screen.getByRole('link', { name: common.route.notFound.home })).toHaveAttribute(
      'href',
      '/',
    );
  });
});
