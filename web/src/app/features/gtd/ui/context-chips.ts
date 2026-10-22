import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { ContextResponse } from '../../../api/generated';

/**
 * Toggle chips choosing any number of contexts, in the given order:
 * `<app-context-chips [contexts]="contextStore.items()" [(selected)]="contextIds" />`.
 */
@Component({
  selector: 'app-context-chips',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chips" role="group" i18n-aria-label="@@gtd.contexts.label" aria-label="Contexts">
      @for (context of contexts(); track context.id) {
        <button
          type="button"
          class="chip"
          [class.selected]="selected().includes(context.id)"
          [attr.aria-pressed]="selected().includes(context.id)"
          [disabled]="disabled()"
          (click)="toggle(context.id)"
        >
          <span class="dot" [style.background]="context.color ?? null" aria-hidden="true"></span>
          {{ context.name }}
        </button>
      } @empty {
        <span class="empty" i18n="@@gtd.contexts.none">No contexts yet</span>
      }
    </div>
  `,
  styleUrl: './context-chips.scss',
})
export class ContextChips {
  /** The contexts to choose from. */
  readonly contexts = input.required<readonly ContextResponse[]>();
  readonly disabled = input(false);
  /** Ids of the chosen contexts, in the order they were chosen. */
  readonly selected = model<readonly string[]>([]);

  protected toggle(id: string): void {
    const selected = this.selected();
    this.selected.set(
      selected.includes(id) ? selected.filter((other) => other !== id) : [...selected, id],
    );
  }
}
