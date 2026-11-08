import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { ShortcutInfo } from './hotkeys';

/** The keys of one page (e.g. the mail list), shown under its title in the `?` overview. */
export interface ShortcutSection {
  readonly title: string;
  readonly shortcuts: readonly ShortcutInfo[];
}

/**
 * The shortcuts of the pages that are shown, for the `?` overview next to the shell's own. A page
 * registers its section while it lives.
 */
@Injectable({ providedIn: 'root' })
export class PageShortcuts {
  private readonly current = signal<readonly ShortcutSection[]>([]);
  readonly sections = this.current.asReadonly();

  /**
   * Shows `section` until `destroyRef` (by default the caller's, so call it in an injection
   * context) is destroyed.
   */
  register(section: ShortcutSection, destroyRef: DestroyRef = inject(DestroyRef)): void {
    this.current.update((sections) => [...sections, section]);
    destroyRef.onDestroy(() =>
      this.current.update((sections) => sections.filter((entry) => entry !== section)),
    );
  }
}
