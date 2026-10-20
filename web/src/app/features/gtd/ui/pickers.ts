import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import {
  Creativity,
  CREATIVITY_OPTIONS,
  Energy,
  ENERGY_OPTIONS,
  ESTIMATE_OPTIONS,
  estimateLabel,
} from './labels';
import { Segmented, SegmentedOption } from './segmented';

/** Chooses a task's energy (or none): `<app-energy-picker [(value)]="energy" />`. */
@Component({
  selector: 'app-energy-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Segmented],
  template: `<app-segmented
    [options]="options"
    [label]="label"
    [disabled]="disabled()"
    [(value)]="value"
  />`,
})
export class EnergyPicker {
  readonly disabled = input(false);
  readonly value = model<Energy | undefined>();
  protected readonly options = ENERGY_OPTIONS;
  protected readonly label = $localize`:@@gtd.energy.label:Energy`;
}

/** Chooses a task's creativity (or none): `<app-creativity-picker [(value)]="creativity" />`. */
@Component({
  selector: 'app-creativity-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Segmented],
  template: `<app-segmented
    [options]="options"
    [label]="label"
    [disabled]="disabled()"
    [(value)]="value"
  />`,
})
export class CreativityPicker {
  readonly disabled = input(false);
  readonly value = model<Creativity | undefined>();
  protected readonly options = CREATIVITY_OPTIONS;
  protected readonly label = $localize`:@@gtd.creativity.label:Creativity`;
}

/**
 * Chooses a task's time estimate in minutes from the quick picks (or none):
 * `<app-estimate-picker [(value)]="minutes" />`. The last pick stands for "that or more".
 */
@Component({
  selector: 'app-estimate-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Segmented],
  template: `<app-segmented
    [options]="options"
    [label]="label"
    [disabled]="disabled()"
    [(value)]="value"
  />`,
})
export class EstimatePicker {
  readonly disabled = input(false);
  readonly value = model<number | undefined>();
  protected readonly options: readonly SegmentedOption<number>[] = ESTIMATE_OPTIONS.map(
    (minutes, index) => ({
      value: minutes,
      label:
        index === ESTIMATE_OPTIONS.length - 1
          ? `${estimateLabel(minutes)}+`
          : estimateLabel(minutes),
    }),
  );
  protected readonly label = $localize`:@@gtd.estimate.label:Time estimate`;
}
