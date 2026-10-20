import { HttpContext } from '@angular/common/http';
import {
  effect,
  EnvironmentProviders,
  inject,
  provideEnvironmentInitializer,
  untracked,
} from '@angular/core';
import { AuthService } from '../../core/auth';
import { SILENT_ERRORS } from '../../core/http';
import { SettingsService } from './settings.service';

/**
 * Loads the settings whenever a user logs in (`app.routes.ts` provides this to the shell), so the
 * server's theme wins over the one stored in this browser. When that fails, the local theme stays
 * and the settings page loads them again.
 */
export function provideSettingsSync(): EnvironmentProviders {
  return provideEnvironmentInitializer(() => {
    const auth = inject(AuthService);
    const settings = inject(SettingsService);
    effect(() => {
      const username = auth.user()?.username;
      if (!username) return;
      untracked(() => {
        settings.load(new HttpContext().set(SILENT_ERRORS, true)).catch(() => undefined);
      });
    });
  });
}
