// SPDX-License-Identifier: Apache-2.0
//
// Side menu (docs/15 §3.3): application screens on app routes (dashboard, XLog, active, the four
// statistics, instances, map), global screens (overview, alerts, administration by role, about).
// Items the user's role cannot open are hidden (useCan; the server enforces regardless). Links
// carry the global state (docs/15 §5). The collapsed state is a browser preference
// ('argos.ui.sidebar').
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router';

import { useAuthStore } from '../state/authStore';
import { can } from '../state/permissions';
import { useUiStore } from '../state/uiStore';

import { useAppId, useCarriedNavigation } from './navigation';
import { SCREENS, type MenuGroup, type ScreenDef } from './screens';

const GROUPS: readonly Exclude<MenuGroup, null>[] = [
  'app',
  'stats',
  'global',
  'alerts',
  'admin',
  'about',
];
const GROUP_KEYS = {
  app: 'shell:menu.group.app',
  stats: 'shell:menu.group.stats',
  global: 'shell:menu.group.global',
  alerts: 'shell:menu.group.alerts',
  admin: 'shell:menu.group.admin',
  about: 'shell:menu.group.about',
} as const;

/** Screens of a menu group the user may open (app groups only with an application). */
export function menuItems(
  group: Exclude<MenuGroup, null>,
  appId: number | null,
  allowed: (screen: ScreenDef) => boolean,
): ScreenDef[] {
  return SCREENS.filter(
    (screen) =>
      screen.menu === group && (screen.scope === 'global' || appId !== null) && allowed(screen),
  );
}

export function SideMenu() {
  const { t } = useTranslation(['common', 'shell']);
  const appId = useAppId();
  const role = useAuthStore((s) => s.role);
  const granted = useAuthStore((s) => s.permissions);
  const collapsed = useUiStore((s) => s.sidebarCollapsed);
  const setCollapsed = useUiStore((s) => s.setSidebarCollapsed);
  const { hrefFor } = useCarriedNavigation();
  const allowed = (screen: ScreenDef): boolean => can(screen.permission, role, granted);

  return (
    <nav
      className="argos-shell__menu side-menu"
      aria-label={t('shell:menu.label')}
      data-testid="side-menu"
      data-collapsed={collapsed}
    >
      <button
        type="button"
        className="side-menu__toggle"
        aria-expanded={!collapsed}
        onClick={() => {
          setCollapsed(!collapsed);
        }}
        data-testid="side-menu-toggle"
      >
        <span aria-hidden="true">{collapsed ? '»' : '«'}</span>
        <span className="visually-hidden">
          {collapsed ? t('shell:menu.expand') : t('shell:menu.collapse')}
        </span>
      </button>
      {GROUPS.map((group) => {
        const items = menuItems(group, appId, allowed);
        if (items.length === 0) {
          return null;
        }
        return (
          <section key={group} className="side-menu__group" aria-labelledby={`side-menu-${group}`}>
            <h2 id={`side-menu-${group}`} className="side-menu__heading">
              {t(GROUP_KEYS[group])}
            </h2>
            <ul className="side-menu__list">
              {items.map((screen) => {
                const label = t(`shell:screen.${screen.label}`);
                return (
                  <li key={screen.id}>
                    <NavLink
                      to={hrefFor(screen, appId)}
                      className={({ isActive }) =>
                        isActive ? 'side-menu__link side-menu__link--active' : 'side-menu__link'
                      }
                      title={collapsed ? label : undefined}
                      data-testid={`side-menu-${screen.id}`}
                    >
                      <span className="side-menu__initial" aria-hidden="true">
                        {label.slice(0, 1)}
                      </span>
                      <span className="side-menu__label">{label}</span>
                    </NavLink>
                  </li>
                );
              })}
            </ul>
          </section>
        );
      })}
    </nav>
  );
}
