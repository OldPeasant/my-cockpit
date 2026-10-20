import { inject, Injectable, Injector, Provider } from '@angular/core';
import { defer, Observable, switchMap } from 'rxjs';
import { PALETTE_PROVIDERS, PaletteItem, PaletteProvider } from '../../shell/shell-tokens';
import { CommandsPaletteProvider } from './commands-palette.provider';

/**
 * Loads the global search provider on first use, so its code stays out of the initial bundle
 * (`app.routes.ts` imports this file).
 */
@Injectable()
export class LazySearchPaletteProvider implements PaletteProvider {
  private readonly injector = inject(Injector);
  private provider?: Promise<PaletteProvider>;

  items(query: string): Observable<readonly PaletteItem[]> {
    return defer(() => (this.provider ??= this.load())).pipe(
      switchMap((provider) => provider.items(query)),
    );
  }

  private async load(): Promise<PaletteProvider> {
    try {
      const { SearchPaletteProvider } = await import('./search-palette.provider');
      return Injector.create({ providers: [SearchPaletteProvider], parent: this.injector }).get(
        SearchPaletteProvider,
      );
    } catch (error) {
      // E.g. offline after a deployment: try again with the next query.
      this.provider = undefined;
      throw error;
    }
  }
}

/**
 * Adds the action commands ("New task") and the global search (SPEC.md §4.4) to the command
 * palette (`PALETTE_PROVIDERS`, multi); the shell adds the "Go to" commands.
 */
export function provideSearch(): Provider[] {
  return [
    { provide: PALETTE_PROVIDERS, useClass: CommandsPaletteProvider, multi: true },
    { provide: PALETTE_PROVIDERS, useClass: LazySearchPaletteProvider, multi: true },
  ];
}
