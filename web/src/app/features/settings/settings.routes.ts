import { Route, Routes } from '@angular/router';
import { ComingSoon } from '../../shell/coming-soon';
import { SETTINGS_SECTIONS, SettingsSection } from './settings-sections';

/** Placeholder of a section that is not built yet. */
function placeholder({ path, label }: SettingsSection): Route {
  return { path, title: label, component: ComingSoon };
}

/**
 * One child route per section (`settings-sections.ts`). Section tasks replace their placeholder
 * with a `loadComponent` from their own sub-folder (`settings/<section>/`).
 */
export default [
  {
    path: '',
    loadComponent: () => import('./settings-layout'),
    children: [
      {
        path: SETTINGS_SECTIONS.general.path,
        pathMatch: 'full',
        title: $localize`:@@nav.settings:Settings`,
        loadComponent: () => import('./general/general-settings'),
      },
      {
        path: SETTINGS_SECTIONS.gtd.path,
        title: SETTINGS_SECTIONS.gtd.label,
        loadComponent: () => import('./gtd/gtd-settings'),
      },
      {
        path: SETTINGS_SECTIONS.security.path,
        title: SETTINGS_SECTIONS.security.label,
        loadComponent: () => import('./security/security-settings'),
      },
      {
        path: SETTINGS_SECTIONS.calendars.path,
        title: SETTINGS_SECTIONS.calendars.label,
        loadComponent: () => import('./calendars/calendars-settings'),
      },
      {
        path: SETTINGS_SECTIONS.integrations.path,
        title: SETTINGS_SECTIONS.integrations.label,
        loadComponent: () => import('./integrations/integrations-settings'),
      },
      placeholder(SETTINGS_SECTIONS.mail),
      {
        path: SETTINGS_SECTIONS.backup.path,
        title: SETTINGS_SECTIONS.backup.label,
        loadComponent: () => import('./backup/backup-settings'),
      },
    ],
  },
] satisfies Routes;
