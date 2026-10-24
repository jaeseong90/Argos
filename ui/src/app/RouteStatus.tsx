// SPDX-License-Identifier: Apache-2.0
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

/** Shown while a lazy route loads. */
export function RouteLoading() {
  const { t } = useTranslation();
  return (
    <p className="app" role="status">
      {t('route.loading')}
    </p>
  );
}

/** Unknown path: says so and offers the next step (docs/15 §6.7). */
export function NotFound() {
  const { t } = useTranslation();
  return (
    <main className="app" data-testid="route-not-found">
      <h1>{t('route.notFound.title')}</h1>
      <p>{t('route.notFound.description')}</p>
      <Link to="/">{t('route.notFound.home')}</Link>
    </main>
  );
}
