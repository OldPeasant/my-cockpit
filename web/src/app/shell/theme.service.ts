import { DOCUMENT, inject, Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark' | 'system';

export const THEME_MODES: readonly ThemeMode[] = ['light', 'dark', 'system'];

/**
 * localStorage key of the theme. `public/theme-init.js` reads the same key before Angular starts,
 * so the stored theme is applied before the first paint.
 */
export const THEME_STORAGE_KEY = 'cockpit.theme';

export function isThemeMode(value: unknown): value is ThemeMode {
  return typeof value === 'string' && (THEME_MODES as readonly string[]).includes(value);
}

/**
 * Light/dark/system theme (SPEC.md §4.7, §9.1). Sets `data-theme` on `<html>` (see
 * `src/theme/_theme.scss`) and remembers the choice in localStorage. The settings feature syncs it
 * with the server setting by calling {@link setMode}.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly modeSignal = signal<ThemeMode>(this.readStored());

  readonly mode = this.modeSignal.asReadonly();

  constructor() {
    this.apply(this.modeSignal());
  }

  setMode(mode: ThemeMode): void {
    this.modeSignal.set(mode);
    this.apply(mode);
    try {
      this.document.defaultView?.localStorage.setItem(THEME_STORAGE_KEY, mode);
    } catch {
      // Storage disabled or full (private mode, quota): the theme still applies for this session.
    }
  }

  private readStored(): ThemeMode {
    try {
      const stored = this.document.defaultView?.localStorage.getItem(THEME_STORAGE_KEY);
      return isThemeMode(stored) ? stored : 'system';
    } catch {
      return 'system';
    }
  }

  private apply(mode: ThemeMode): void {
    this.document.documentElement.setAttribute('data-theme', mode);
  }
}
