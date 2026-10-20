import { Provider } from '@angular/core';

/**
 * Adds the global search to the command palette (`PALETTE_PROVIDERS`, multi). Placeholder: the
 * palette offers only the shell's "Go to" commands until the search task (web-search) adds its
 * provider here.
 */
export function provideSearch(): Provider[] {
  return [];
}
