/** A settings section: its path below `/settings` and its label (also the page title). */
export interface SettingsSection {
  readonly path: string;
  readonly label: string;
}

/** Settings sections in navigation order; General opens at `/settings` itself. */
export const SETTINGS_SECTIONS = {
  general: { path: '', label: $localize`:@@settings.general:General` },
  gtd: { path: 'gtd', label: $localize`:@@settings.gtd:Tasks & projects` },
  security: { path: 'security', label: $localize`:@@settings.security:Security` },
  calendars: { path: 'calendars', label: $localize`:@@settings.calendars:Calendars` },
  integrations: { path: 'integrations', label: $localize`:@@settings.integrations:Integrations` },
  mail: { path: 'mail', label: $localize`:@@settings.mail:Mail` },
  backup: { path: 'backup', label: $localize`:@@settings.backup:Backup & restore` },
} as const satisfies Record<string, SettingsSection>;
