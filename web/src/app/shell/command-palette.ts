import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { MatDialogRef } from '@angular/material/dialog';
import { catchError, combineLatest, map, Observable, of, startWith, switchMap } from 'rxjs';
import { PALETTE_PROVIDERS, PaletteItem } from './shell-tokens';

interface PaletteGroup {
  readonly name: string;
  readonly items: readonly { readonly item: PaletteItem; readonly index: number }[];
}

/**
 * Command palette skeleton (SPEC.md §4.4): a query field and the grouped rows of every
 * `PALETTE_PROVIDERS` entry. Arrow keys move the selection, Enter runs it.
 */
@Component({
  selector: 'app-command-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './command-palette.html',
  styleUrl: './command-palette.scss',
})
export class CommandPalette {
  private readonly providers = inject(PALETTE_PROVIDERS);
  private readonly dialogRef = inject(MatDialogRef<CommandPalette>, { optional: true });

  protected readonly query = signal('');
  protected readonly active = signal(0);

  protected readonly items = toSignal(
    toObservable(this.query).pipe(switchMap((query) => this.collect(query))),
    { initialValue: [] as readonly PaletteItem[] },
  );

  protected readonly groups = computed<readonly PaletteGroup[]>(() => {
    const groups: { name: string; items: { item: PaletteItem; index: number }[] }[] = [];
    this.items().forEach((item, index) => {
      let group = groups.find((g) => g.name === item.group);
      if (!group) {
        group = { name: item.group, items: [] };
        groups.push(group);
      }
      group.items.push({ item, index });
    });
    return groups;
  });

  /** Rows in display order (grouped), so arrow keys follow what the user sees. */
  private readonly ordered = computed(() => this.groups().flatMap((g) => g.items));

  protected readonly activeId = computed(() => {
    const row = this.ordered()[this.active()];
    return row ? this.optionId(row.index) : null;
  });

  protected onInput(value: string): void {
    this.query.set(value);
    this.active.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.ordered().length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      this.active.update((i) => (i + 1) % count);
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      this.active.update((i) => (i - 1 + count) % count);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const row = this.ordered()[this.active()];
      if (row) {
        this.choose(row.item);
      }
    }
  }

  protected isActive(index: number): boolean {
    return this.ordered()[this.active()]?.index === index;
  }

  protected choose(item: PaletteItem): void {
    this.dialogRef?.close();
    item.run();
  }

  protected optionId(index: number): string {
    return `app-palette-option-${index}`;
  }

  private collect(query: string): Observable<readonly PaletteItem[]> {
    if (this.providers.length === 0) {
      return of([]);
    }
    return combineLatest(
      this.providers.map((provider) =>
        provider.items(query).pipe(
          startWith([] as readonly PaletteItem[]),
          catchError(() => of([] as readonly PaletteItem[])),
        ),
      ),
    ).pipe(map((lists) => lists.flat()));
  }
}
