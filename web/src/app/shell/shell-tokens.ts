import { inject, InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import { ThemeMode, ThemeService } from './theme.service';

/**
 * Opens the quick capture dialog (SPEC.md §4.5). The shell calls it from the '+' button and the
 * capture hotkeys; `features/capture/capture.providers.ts` provides the implementation.
 */
export interface CaptureDialog {
  open(): void;
}

export const CAPTURE_DIALOG = new InjectionToken<CaptureDialog>('CAPTURE_DIALOG');

/**
 * Saves the theme chosen in the top bar. Without a provider it applies only in this browser;
 * `features/settings/settings.providers.ts` also saves it as the user's setting, so it survives the
 * server theme that is loaded after each login and reload.
 */
export interface ThemePreference {
  set(mode: ThemeMode): void;
}

export const THEME_PREFERENCE = new InjectionToken<ThemePreference>('THEME_PREFERENCE', {
  providedIn: 'root',
  factory: () => {
    const theme = inject(ThemeService);
    return { set: (mode) => theme.setMode(mode) };
  },
});

/** One selectable row of the command palette. Rows are shown grouped by `group`, in order. */
export interface PaletteItem {
  readonly id: string;
  readonly group: string;
  readonly label: string;
  /** Secondary text, e.g. the item type or a shortcut. */
  readonly hint?: string;
  /** Runs when the row is chosen; the palette closes afterwards. */
  run(): void;
}

/**
 * Contributes rows to the command palette (SPEC.md §4.4) for the current query. The palette
 * subscribes once per query and drops the previous subscription, so results of an older query never
 * replace newer ones. Debouncing remote calls is the provider's job.
 */
export interface PaletteProvider {
  items(query: string): Observable<readonly PaletteItem[]>;
}

/** Multi token: every provider adds rows; the shell's navigation commands are one of them. */
export const PALETTE_PROVIDERS = new InjectionToken<readonly PaletteProvider[]>(
  'PALETTE_PROVIDERS',
  { providedIn: 'root', factory: () => [] },
);
