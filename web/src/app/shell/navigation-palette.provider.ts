import { inject, Injectable, Provider } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NAV_SECTIONS } from './nav-sections';
import { PALETTE_PROVIDERS, PaletteItem, PaletteProvider } from './shell-tokens';

/** "Go to <section>" commands for every navigation section, filtered by the query. */
@Injectable()
export class NavigationPaletteProvider implements PaletteProvider {
  private readonly router = inject(Router);

  items(query: string): Observable<readonly PaletteItem[]> {
    const needle = query.trim().toLowerCase();
    const group = $localize`:@@palette.group.goTo:Go to`;
    return of(
      NAV_SECTIONS.filter((section) => section.label.toLowerCase().includes(needle)).map(
        (section) => ({
          id: `go-to-${section.id}`,
          group,
          label: section.label,
          run: () => void this.router.navigateByUrl(section.path),
        }),
      ),
    );
  }
}

export function provideNavigationPalette(): Provider {
  return { provide: PALETTE_PROVIDERS, useClass: NavigationPaletteProvider, multi: true };
}
