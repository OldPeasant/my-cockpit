import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { FolderSummary } from '../../../api/generated';
import { DriveService, rootName } from './drive.service';

/** Key of the drive root in the tree's maps. */
const ROOT = '';

/**
 * The folder tree of the drive: the root and every expanded folder show their subfolders, loaded
 * when first expanded. The folders on the way to the shown one expand by themselves. A disclosure
 * list of links rather than an ARIA tree: each folder is a link, each toggle a button.
 */
@Component({
  selector: 'app-folder-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, RouterLink],
  template: `
    <nav class="tree" i18n-aria-label="@@files.tree" aria-label="Folders">
      <ul>
        <li>
          <div class="row" [class.current]="current() === null">
            <span class="spacer" aria-hidden="true"></span>
            <a
              routerLink="/files"
              [attr.aria-current]="current() === null ? 'page' : null"
              class="name"
              >{{ rootName }}</a
            >
          </div>
          <ng-container
            [ngTemplateOutlet]="branch"
            [ngTemplateOutletContext]="{ $implicit: '', depth: 1 }"
          />
        </li>
      </ul>
    </nav>

    <ng-template #branch let-parent let-depth="depth">
      @if (children()[parent]; as folders) {
        @if (folders.length) {
          <ul>
            @for (folder of folders; track folder.id) {
              <li>
                <div class="row" [class.current]="current() === folder.id" [style.--depth]="depth">
                  <button
                    type="button"
                    class="toggle"
                    [attr.aria-expanded]="isExpanded(folder.id)"
                    [attr.aria-label]="toggleLabel(folder)"
                    (click)="toggle(folder.id)"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <path d="M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" />
                    </svg>
                  </button>
                  <a
                    class="name"
                    [routerLink]="['/files', folder.id]"
                    [attr.aria-current]="current() === folder.id ? 'page' : null"
                    >{{ folder.name }}</a
                  >
                </div>
                @if (isExpanded(folder.id)) {
                  <ng-container
                    [ngTemplateOutlet]="branch"
                    [ngTemplateOutletContext]="{ $implicit: folder.id, depth: depth + 1 }"
                  />
                }
              </li>
            }
          </ul>
        }
      } @else if (failed().has(parent)) {
        <p class="note error" [style.--depth]="depth" i18n="@@files.tree.failed">
          Folders could not be loaded.
        </p>
      } @else {
        <p class="note" [style.--depth]="depth" i18n="@@files.tree.loading">Loading…</p>
      }
    </ng-template>
  `,
  styleUrl: './folder-tree.scss',
})
export class FolderTree {
  private readonly drive = inject(DriveService);

  /** The shown folder (`null`: the drive root). */
  readonly current = input<string | null>(null);
  /** The ids of the shown folder's ancestors (and itself): they are expanded. */
  readonly path = input<readonly string[]>([]);

  protected readonly rootName = rootName();
  /** Subfolders per loaded folder id (`''`: the root). */
  protected readonly children = signal<Readonly<Record<string, FolderSummary[]>>>({});
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());
  protected readonly failed = signal<ReadonlySet<string>>(new Set());
  /** Folders whose subfolders are being fetched. */
  private readonly loading = new Set<string>();

  constructor() {
    effect(() => {
      const path = this.path();
      untracked(() => {
        void this.load(ROOT);
        if (!path.length) return;
        this.expanded.update((ids) => new Set([...ids, ...path]));
        for (const id of path) void this.load(id);
      });
    });
  }

  /** Reloads every loaded folder, after folders were created, renamed, moved or deleted. */
  refresh(): void {
    for (const id of Object.keys(this.children())) void this.load(id, true);
  }

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
  }

  protected toggle(id: string): void {
    const open = !this.isExpanded(id);
    this.expanded.update((ids) => {
      const next = new Set(ids);
      if (open) next.add(id);
      else next.delete(id);
      return next;
    });
    if (open) void this.load(id);
  }

  protected toggleLabel(folder: FolderSummary): string {
    const name = folder.name;
    return this.isExpanded(folder.id)
      ? $localize`:@@files.tree.collapse:Collapse ${name}:name:`
      : $localize`:@@files.tree.expand:Expand ${name}:name:`;
  }

  private async load(id: string, force = false): Promise<void> {
    if (!force && (id in this.children() || this.loading.has(id))) return;
    this.loading.add(id);
    this.failed.update((ids) => without(ids, id));
    try {
      const folders = await this.drive.subfolders(id === ROOT ? null : id);
      this.children.update((all) => ({ ...all, [id]: folders }));
    } catch {
      this.children.update((all) => {
        const rest = { ...all };
        delete rest[id];
        return rest;
      });
      this.failed.update((ids) => new Set(ids).add(id));
    } finally {
      this.loading.delete(id);
    }
  }
}

function without(ids: ReadonlySet<string>, id: string): ReadonlySet<string> {
  if (!ids.has(id)) return ids;
  const rest = new Set(ids);
  rest.delete(id);
  return rest;
}
