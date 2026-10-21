import { Routes } from '@angular/router';
import { SETTINGS_SECTIONS } from './settings-sections';

/**
 * One child route per section (`settings-sections.ts`), each a `loadComponent` from its own
 * sub-folder (`settings/<section>/`).
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
      {
        path: SETTINGS_SECTIONS.mail.path,
        title: SETTINGS_SECTIONS.mail.label,
        loadComponent: () => import('./mail/mail-settings'),
      },
      {
        path: SETTINGS_SECTIONS.backup.path,
        title: SETTINGS_SECTIONS.backup.label,
        loadComponent: () => import('./backup/backup-settings'),
      },
    ],
  },
] satisfies Routes;
