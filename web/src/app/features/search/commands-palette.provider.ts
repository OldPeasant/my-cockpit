import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { CAPTURE_DIALOG, PaletteItem, PaletteProvider } from '../../shell/shell-tokens';

/**
 * Action commands of the command palette (SPEC.md §4.4), filtered by the query. The shell adds the
 * "Go to <section>" commands itself (`shell/navigation-palette.provider.ts`).
 */
@Injectable()
export class CommandsPaletteProvider implements PaletteProvider {
  private readonly capture = inject(CAPTURE_DIALOG, { optional: true });

  items(query: string): Observable<readonly PaletteItem[]> {
    const needle = query.trim().toLowerCase();
    const group = $localize`:@@palette.group.commands:Commands`;
    const capture = this.capture;
    const commands: PaletteItem[] = [];
    if (capture) {
      commands.push({
        id: 'command-new-task',
        group,
        label: $localize`:@@palette.command.newTask:New task`,
        hint: 'Q',
        run: () => capture.open(),
      });
    }
    return of(commands.filter((command) => command.label.toLowerCase().includes(needle)));
  }
}
