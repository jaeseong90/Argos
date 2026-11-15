// SPDX-License-Identifier: Apache-2.0
//
// Element of every screen route: checks the permission of the screen (a 403 state instead of the
// screen; the server enforces anyway, docs/15 §11) and renders the screen's component loaded by
// its registry entry (`load`, screens.ts), or its placeholder until its work order adds one
// (WO-31..39). It also titles the document with the screen's label (useDocumentTitle), so screen
// components do not set the title themselves. Lazy-loaded per route (docs/15 §14).
import type { ComponentType } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { useDocumentTitle } from '../app/useDocumentTitle';
import { ErrorState } from '../components/ErrorState';
import { useCan } from '../state/permissions';

import { useCurrentScreen } from './navigation';
import { RoutePending } from './RoutePending';
import type { ScreenDef } from './screens';

interface GuardedProps {
  readonly screen: ScreenDef;
  readonly body: ComponentType | null;
}

function Guarded({ screen, body: Body }: GuardedProps) {
  const allowed = useCan(screen.permission);
  const navigate = useNavigate();
  const { t } = useTranslation(['common', 'shell']);
  const title = t(`shell:screen.${screen.label}`);
  // Every screen (built, placeholder or refused) is titled by its registry label (WCAG 2.4.2).
  useDocumentTitle(title);
  if (!allowed) {
    return (
      <section data-testid="route-forbidden">
        <h1 className="route-pending__title">{title}</h1>
        <ErrorState
          error={{ status: 403, code: 'forbidden' }}
          onRetry={() => {
            void navigate(-1);
          }}
        />
      </section>
    );
  }
  return Body === null ? <RoutePending screen={screen} /> : <Body />;
}

export interface ScreenRouteProps {
  /** The screen's component, or null for the placeholder. */
  readonly body?: ComponentType | null;
}

export function ScreenRoute({ body = null }: ScreenRouteProps) {
  const screen = useCurrentScreen();
  return screen === null ? null : <Guarded screen={screen} body={body} />;
}
