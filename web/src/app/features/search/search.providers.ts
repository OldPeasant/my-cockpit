import { Provider } from '@angular/core';
import { PALETTE_PROVIDERS } from '../../shell/shell-tokens';
import { CommandsPaletteProvider } from './commands-palette.provider';
import { SearchPaletteProvider } from './search-palette.provider';

/**
 * Adds the action commands ("New task") and the global search (SPEC.md §4.4) to the command
 * palette (`PALETTE_PROVIDERS`, multi); the shell adds the "Go to" commands.
 */
export function provideSearch(): Provider[] {
  return [
    { provide: PALETTE_PROVIDERS, useClass: CommandsPaletteProvider, multi: true },
    { provide: PALETTE_PROVIDERS, useClass: SearchPaletteProvider, multi: true },
  ];
}
