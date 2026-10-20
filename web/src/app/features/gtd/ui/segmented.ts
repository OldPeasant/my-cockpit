import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** One choice of a {@link Segmented} group. */
export interface SegmentedOption<T> {
  readonly value: T;
  readonly label: string;
}

/**
 * A row of toggle buttons choosing at most one value; pressing the chosen one again clears it.
 * The base of the energy, creativity and estimate pickers.
 */
@Component({
  selector: 'app-segmented',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="group" role="group" [attr.aria-label]="label()">
      @for (option of options(); track option.value) {
        <button
          type="button"
          class="option"
          [class.selected]="option.value === value()"
          [attr.aria-pressed]="option.value === value()"
          [disabled]="disabled()"
          (click)="choose(option.value)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styleUrl: './segmented.scss',
})
export class Segmented<T> {
  readonly options = input.required<readonly SegmentedOption<T>[]>();
  /** Accessible name of the group. */
  readonly label = input.required<string>();
  readonly disabled = input(false);
  /** The chosen value; `undefined` when none is. */
  readonly value = model<T | undefined>();

  protected choose(value: T): void {
    this.value.set(value === this.value() ? undefined : value);
  }
}
