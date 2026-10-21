import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { AreaResponse, ContextResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { DeleteDialog, DeleteDialogData, DeleteDialogResult } from './delete-dialog';
import { GtdSettingsService, ReassignError } from './gtd-settings.service';

/** The two lists of this section. */
export type GtdListKind = 'context' | 'area';

/** A context or area as one row of its list. */
interface Item {
  readonly id: string;
  readonly version: number;
  readonly position: number;
}

/** The color a context without one shows (and the color picker starts with). */
const NO_COLOR = '#8a8f98';

/**
 * Settings → Tasks & projects (SPEC.md §5.1): the contexts (with an optional color) and the areas
 * of focus, each list created, renamed, reordered (drag or the arrow buttons) and deleted here.
 * Deleting one that tasks or projects use asks whether they move to another one or lose it.
 */
@Component({
  selector: 'app-gtd-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  templateUrl: './gtd-settings.html',
  styleUrl: './gtd-settings.scss',
})
export default class GtdSettings implements OnInit {
  private readonly service = inject(GtdSettingsService);
  private readonly dialog = inject(MatDialog);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly noColor = NO_COLOR;

  protected readonly contexts = signal<ContextResponse[] | null>(null);
  protected readonly areas = signal<AreaResponse[] | null>(null);
  protected readonly loadError = signal<Record<GtdListKind, string | null>>({
    context: null,
    area: null,
  });
  /** The last failed change per list. */
  protected readonly error = signal<Record<GtdListKind, string | null>>({
    context: null,
    area: null,
  });
  /** Lists with a reorder, create or delete running; their controls wait for it. */
  protected readonly busy = signal<ReadonlySet<GtdListKind>>(new Set());

  protected readonly newContext = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100), notBlank]],
    color: [''],
  });
  protected readonly newArea = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(200), notBlank]],
  });

  ngOnInit(): void {
    void this.load('context');
    void this.load('area');
  }

  protected async load(kind: GtdListKind): Promise<void> {
    this.setIn(this.loadError, kind, null);
    try {
      if (kind === 'context') this.contexts.set(await this.service.contexts());
      else this.areas.set(await this.service.areas());
    } catch (error) {
      this.setIn(this.loadError, kind, message(kind, error));
    }
  }

  protected async addContext(): Promise<void> {
    if (this.newContext.invalid) {
      this.newContext.markAllAsTouched();
      return;
    }
    const { name, color } = this.newContext.getRawValue();
    const created = await this.run('context', () =>
      this.service.createContext(name.trim(), color || undefined),
    );
    if (!created) return;
    this.contexts.update((all) => [...(all ?? []), created]);
    this.newContext.reset();
  }

  protected async addArea(): Promise<void> {
    if (this.newArea.invalid) {
      this.newArea.markAllAsTouched();
      return;
    }
    const created = await this.run('area', () =>
      this.service.createArea(this.newArea.getRawValue().title.trim()),
    );
    if (!created) return;
    this.areas.update((all) => [...(all ?? []), created]);
    this.newArea.reset();
  }

  /** Saves a new name typed into a row; a blank or unchanged one puts the old name back. */
  protected async renameContext(context: ContextResponse, input: HTMLInputElement): Promise<void> {
    const name = input.value.trim();
    if (!name || name === context.name) {
      input.value = context.name;
      return;
    }
    const saved = await this.save('context', () => this.service.updateContext(context, { name }));
    if (saved) this.replace('context', saved);
    else input.value = context.name;
  }

  protected async renameArea(area: AreaResponse, input: HTMLInputElement): Promise<void> {
    const title = input.value.trim();
    if (!title || title === area.title) {
      input.value = area.title;
      return;
    }
    const saved = await this.save('area', () => this.service.renameArea(area, title));
    if (saved) this.replace('area', saved);
    else input.value = area.title;
  }

  /** Sets (or with `null` clears) the color of a context. */
  protected async setColor(context: ContextResponse, color: string | null): Promise<void> {
    if ((color ?? undefined) === context.color) return;
    const saved = await this.save('context', () => this.service.updateContext(context, { color }));
    if (saved) this.replace('context', saved);
  }

  protected drop(kind: GtdListKind, event: CdkDragDrop<unknown>): void {
    void this.reorder(kind, event.previousIndex, event.currentIndex);
  }

  /**
   * Moves the item at `from` to `to` at once and saves the new order. A failure restores the old
   * order, keeping a rename or color change saved meanwhile (with its new version).
   */
  protected async reorder(kind: GtdListKind, from: number, to: number): Promise<void> {
    const list = this.list(kind);
    if (!list || from === to || to < 0 || to >= list.length || this.busy().has(kind)) return;
    const moved = [...list];
    moved.splice(to, 0, ...moved.splice(from, 1));
    this.setList(kind, moved);
    const ids = moved.map((item) => item.id);
    const saved = await this.run<readonly (ContextResponse | AreaResponse)[]>(kind, () =>
      kind === 'context' ? this.service.orderContexts(ids) : this.service.orderAreas(ids),
    );
    if (saved) this.setList(kind, saved);
    else this.restoreOrder(kind, list);
  }

  protected async removeContext(context: ContextResponse): Promise<void> {
    await this.remove(
      'context',
      context,
      context.name,
      () => this.service.tasksWithContext(context.id),
      (moveTo) => this.service.deleteContext(context, moveTo),
    );
  }

  protected async removeArea(area: AreaResponse): Promise<void> {
    await this.remove(
      'area',
      area,
      area.title,
      () => this.service.projectsInArea(area.id),
      (moveTo) => this.service.deleteArea(area, moveTo),
    );
  }

  protected contextLabel(context: ContextResponse): string {
    return $localize`:@@gtdSettings.contexts.nameAria:Name of ${context.name}:name:`;
  }

  protected areaLabel(area: AreaResponse): string {
    return $localize`:@@gtdSettings.areas.nameAria:Name of ${area.title}:name:`;
  }

  protected colorLabel(context: ContextResponse): string {
    return $localize`:@@gtdSettings.contexts.colorAria:Color of ${context.name}:name:`;
  }

  protected clearColorLabel(context: ContextResponse): string {
    return $localize`:@@gtdSettings.contexts.clearColorAria:Remove the color of ${context.name}:name:`;
  }

  protected upLabel(name: string): string {
    return $localize`:@@gtdSettings.upAria:Move ${name}:name: up`;
  }

  protected downLabel(name: string): string {
    return $localize`:@@gtdSettings.downAria:Move ${name}:name: down`;
  }

  protected deleteLabel(name: string): string {
    return $localize`:@@gtdSettings.deleteAria:Delete ${name}:name:`;
  }

  /** The value of a color input: `#rrggbb` (the server also accepts `#rgb`). */
  protected pickerColor(color: string | undefined): string {
    if (!color) return NO_COLOR;
    return /^#[0-9a-f]{3}$/i.test(color)
      ? `#${[...color.slice(1)].map((c) => c + c).join('')}`
      : color;
  }

  private async remove(
    kind: GtdListKind,
    item: Item,
    name: string,
    assigned: () => Promise<unknown[]>,
    del: (moveTo: string | null) => Promise<void>,
  ): Promise<void> {
    if (this.busy().has(kind)) return;
    const count = await this.run(kind, async () => (await assigned()).length);
    if (count === undefined) return;
    const others = (this.list(kind) ?? [])
      .filter((other) => other.id !== item.id)
      .map((other) => ({ id: other.id, name: displayName(other) }));
    const result = await firstValueFrom(
      this.dialog
        .open<DeleteDialog, DeleteDialogData, DeleteDialogResult>(DeleteDialog, {
          data: { kind, name, count, others },
        })
        .afterClosed(),
    );
    if (!result) return;
    const done = await this.run(kind, async () => {
      await del(result.moveTo);
      return true;
    });
    if (done)
      this.setList(
        kind,
        (this.list(kind) ?? []).filter((other) => other.id !== item.id),
      );
  }

  /** Runs a change of one list with its controls waiting; an error shows below the list. */
  private async run<R>(kind: GtdListKind, action: () => Promise<R>): Promise<R | undefined> {
    this.busy.update((all) => new Set(all).add(kind));
    try {
      return await this.save(kind, action);
    } finally {
      this.busy.update((all) => {
        const rest = new Set(all);
        rest.delete(kind);
        return rest;
      });
    }
  }

  /** Runs a change of one list; an error shows below the list and resolves `undefined`. */
  private async save<R>(kind: GtdListKind, action: () => Promise<R>): Promise<R | undefined> {
    this.setIn(this.error, kind, null);
    try {
      return await action();
    } catch (error) {
      this.setIn(this.error, kind, message(kind, error));
      // The context or area was changed or deleted elsewhere meanwhile: show the current state.
      // (A failed reassignment left the list as it was, so there is nothing to reload.)
      if (isStale(error)) {
        void this.load(kind);
      }
      return undefined;
    }
  }

  private list(kind: GtdListKind): readonly (ContextResponse | AreaResponse)[] | null {
    return kind === 'context' ? this.contexts() : this.areas();
  }

  private setList(kind: GtdListKind, items: readonly (ContextResponse | AreaResponse)[]): void {
    if (kind === 'context') this.contexts.set(items as ContextResponse[]);
    else this.areas.set(items as AreaResponse[]);
  }

  /** Puts the current items back in the order of `old` (items not in it go last). */
  private restoreOrder(kind: GtdListKind, old: readonly Item[]): void {
    const rank = new Map(old.map((item, index) => [item.id, index]));
    const at = (item: Item): number => rank.get(item.id) ?? old.length;
    this.setList(
      kind,
      [...(this.list(kind) ?? [])].sort((a, b) => at(a) - at(b)),
    );
  }

  private replace(kind: GtdListKind, saved: ContextResponse | AreaResponse): void {
    this.setList(
      kind,
      (this.list(kind) ?? []).map((item) => (item.id === saved.id ? saved : item)),
    );
  }

  private setIn(target: typeof this.error, kind: GtdListKind, value: string | null): void {
    target.update((all) => ({ ...all, [kind]: value }));
  }
}

function displayName(item: ContextResponse | AreaResponse): string {
  return 'name' in item ? item.name : item.title;
}

function notBlank(control: { value: string }): { blank: true } | null {
  return control.value.trim() ? null : { blank: true };
}

function isStale(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 412);
}

function message(kind: GtdListKind, error: unknown): string {
  if (error instanceof ReassignError) {
    if (!isStale(error.cause)) return message(kind, error.cause);
    return kind === 'context'
      ? $localize`:@@gtdSettings.reassignStale.context:Some tasks were changed elsewhere in the meantime, so nothing was deleted. Please try again.`
      : $localize`:@@gtdSettings.reassignStale.area:Some projects were changed elsewhere in the meantime, so nothing was deleted. Please try again.`;
  }
  if (isStale(error)) {
    return $localize`:@@gtdSettings.stale:It was changed elsewhere in the meantime; the list now shows the current state.`;
  }
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@gtdSettings.error:Something went wrong.`;
}
