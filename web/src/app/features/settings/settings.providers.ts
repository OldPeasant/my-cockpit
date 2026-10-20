import { HttpContext } from '@angular/common/http';
import {
  effect,
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideEnvironmentInitializer,
  untracked,
} from '@angular/core';
import { AuthService } from '../../core/auth';
import { SILENT_ERRORS } from '../../core/http';
import { THEME_PREFERENCE, ThemePreference } from '../../shell/shell-tokens';
import { SettingsService } from './settings.service';

/**
 * Loads the settings whenever a user logs in (`app.routes.ts` provides this to the shell), so the
 * server's theme wins over the one stored in this browser. When that fails, the local theme stays
 * and the settings page loads them again. The theme chosen in the top bar is saved as the setting
 * (`THEME_PREFERENCE`), so that load keeps it.
 */
export function provideSettingsSync(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: THEME_PREFERENCE, useFactory: settingsThemePreference },
    provideEnvironmentInitializer(syncOnLogin),
  ]);
}

/** Applies the theme at once and saves it; a failed save rolls it back and shows the error. */
function settingsThemePreference(): ThemePreference {
  const settings = inject(SettingsService);
  return { set: (mode) => void settings.update({ theme: mode }).catch(() => undefined) };
}

function syncOnLogin(): void {
  const auth = inject(AuthService);
  const settings = inject(SettingsService);
  effect(() => {
    const username = auth.user()?.username;
    if (!username) return;
    untracked(() => {
      settings.load(new HttpContext().set(SILENT_ERRORS, true)).catch(() => undefined);
    });
  });
}
