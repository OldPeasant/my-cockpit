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
      placeholder(SETTINGS_SECTIONS.gtd),
      placeholder(SETTINGS_SECTIONS.security),
      placeholder(SETTINGS_SECTIONS.calendars),
      placeholder(SETTINGS_SECTIONS.integrations),
      placeholder(SETTINGS_SECTIONS.mail),
      placeholder(SETTINGS_SECTIONS.backup),
    ],
  },
] satisfies Routes;
