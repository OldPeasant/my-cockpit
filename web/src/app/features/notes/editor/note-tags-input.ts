import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

/** Characters that end a tag while typing (a comma, as in the composer). */
const SEPARATOR = /[,\n]/;

/**
 * The tags of a note as removable chips, with an input that adds a tag on Enter or comma and
 * suggests the tags already in use (a native `datalist`). Tags are compared regardless of case, as
 * the tag filter does; a leading `#` is dropped.
 */
@Component({
  selector: 'app-note-tags-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="chips" i18n-aria-label="@@notes.noteTags" aria-label="Tags">
      @for (t of tags(); track t) {
        <li class="chip">
          <span>#{{ t }}</span>
          <button
            type="button"
            class="remove"
            [attr.aria-label]="removeLabel(t)"
            [title]="removeLabel(t)"
            [disabled]="disabled()"
            (click)="remove(t)"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
              />
            </svg>
          </button>
        </li>
      }
      <li class="entry">
        <input
          class="tag-input"
          type="text"
          [attr.list]="listId"
          [value]="text()"
          [disabled]="disabled()"
          (input)="onInput($event)"
          (keydown.enter)="$event.preventDefault(); commit()"
          (keydown.backspace)="onBackspace()"
          (blur)="commit()"
          i18n-placeholder="@@notes.editor.addTag"
          placeholder="Add a tag"
          i18n-aria-label="@@notes.editor.addTag"
          aria-label="Add a tag"
        />
        <datalist [id]="listId">
          @for (s of suggestions(); track s) {
            <option [value]="s"></option>
          }
        </datalist>
      </li>
    </ul>
  `,
  styles: `
    :host {
      display: block;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      padding: 2px 4px 2px 10px;
      border-radius: 999px;
      background: var(--mat-sys-surface-container-high);
      color: var(--app-fg);
      font: var(--mat-sys-label-medium);
    }
    .remove {
      display: grid;
      place-items: center;
      width: 20px;
      height: 20px;
      padding: 0;
      border: 0;
      border-radius: 50%;
      background: none;
      color: var(--app-fg-muted);
      cursor: pointer;
      svg {
        width: 12px;
        height: 12px;
        fill: currentColor;
      }
      &:hover:not(:disabled) {
        color: var(--app-fg);
      }
      &:focus-visible {
        outline: 2px solid var(--app-accent);
      }
    }
    .tag-input {
      min-width: 8em;
      padding: 4px 2px;
      border: 0;
      border-bottom: 1px solid transparent;
      background: none;
      color: inherit;
      font: var(--mat-sys-label-medium);
      &:focus {
        outline: none;
        border-bottom-color: var(--app-accent);
      }
    }
  `,
})
export class NoteTagsInput {
  private static nextId = 0;

  /** The note's tags. */
  readonly tags = input.required<readonly string[]>();
  /** Every tag in use, for the suggestions. */
  readonly known = input<readonly string[]>([]);
  readonly disabled = input(false);
  /** The new list of tags after a tag was added or removed. */
  readonly tagsChange = output<string[]>();

  protected readonly listId = `note-tag-suggestions-${NoteTagsInput.nextId++}`;
  protected readonly text = signal('');

  /** The known tags not on the note yet. */
  protected readonly suggestions = computed(() => {
    const have = new Set(this.tags().map((t) => t.toLocaleLowerCase()));
    return this.known().filter((t) => !have.has(t.toLocaleLowerCase()));
  });

  protected removeLabel(tag: string): string {
    return $localize`:@@notes.editor.removeTag:Remove tag ${tag}:tag:`;
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    if (SEPARATOR.test(value)) {
      const parts = value.split(SEPARATOR);
      const rest = parts.pop() ?? '';
      this.add(parts);
      this.text.set(rest);
      (event.target as HTMLInputElement).value = rest;
    } else {
      this.text.set(value);
    }
  }

  /** Adds what was typed as a tag. */
  protected commit(): void {
    const value = this.text();
    this.text.set('');
    this.add([value]);
  }

  /** Backspace in an empty input removes the last tag. */
  protected onBackspace(): void {
    const tags = this.tags();
    if (!this.text() && tags.length) this.remove(tags[tags.length - 1]);
  }

  protected remove(tag: string): void {
    if (this.disabled()) return;
    this.tagsChange.emit(this.tags().filter((t) => t !== tag));
  }

  private add(values: string[]): void {
    if (this.disabled()) return;
    const next = [...this.tags()];
    const have = new Set(next.map((t) => t.toLocaleLowerCase()));
    for (const value of values) {
      const tag = value.trim().replace(/^#+/, '').trim();
      if (!tag || have.has(tag.toLocaleLowerCase())) continue;
      have.add(tag.toLocaleLowerCase());
      next.push(tag);
    }
    if (next.length !== this.tags().length) this.tagsChange.emit(next);
  }
}
