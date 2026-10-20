import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Settings, SettingsPatch } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { ReminderNotificationsToggle } from '../../../core/notifications';
import { THEME_MODES } from '../../../shell/theme.service';
import { SettingsService } from '../settings.service';

export const WEEK_DAYS = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const satisfies readonly Settings['weekStart'][];

export const ENERGY_LEVELS = ['LOW', 'MEDIUM', 'HIGH'] as const;
export const CREATIVITY_LEVELS = ['ROUTINE', 'MIXED', 'CREATIVE'] as const;

/** Server limit of an energy/creativity label. */
const LABEL_MAX_LENGTH = 40;

/** The rows of this section; each saves on its own. */
export type GeneralField = 'timeZone' | 'weekStart' | 'theme' | 'energyLabels' | 'creativityLabels';

type SaveState = 'saving' | 'saved' | 'failed';

/** IANA zones known to this browser, plus UTC (which `supportedValuesOf` may leave out). */
export function timeZones(): string[] {
  const zones = new Set(Intl.supportedValuesOf('timeZone'));
  zones.add('UTC');
  return [...zones].sort();
}

/**
 * Settings → General (SPEC.md §4.7): time zone, week start, theme and the energy/creativity labels.
 * Each row saves as soon as it changes (labels and time zone when the field is left); the change
 * shows at once and is undone when the server refuses it.
 */
@Component({
  selector: 'app-general-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatAutocompleteModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    NgTemplateOutlet,
    ReactiveFormsModule,
    ReminderNotificationsToggle,
  ],
  templateUrl: './general-settings.html',
  styleUrl: './general-settings.scss',
})
export default class GeneralSettings {
  private readonly service = inject(SettingsService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly zones = timeZones();
  protected readonly weekDays = WEEK_DAYS;
  protected readonly themeModes = THEME_MODES;
  protected readonly energyLevels = ENERGY_LEVELS;
  protected readonly creativityLevels = CREATIVITY_LEVELS;

  protected readonly weekDayLabels: Record<Settings['weekStart'], string> = {
    MONDAY: $localize`:@@settings.weekStart.monday:Monday`,
    TUESDAY: $localize`:@@settings.weekStart.tuesday:Tuesday`,
    WEDNESDAY: $localize`:@@settings.weekStart.wednesday:Wednesday`,
    THURSDAY: $localize`:@@settings.weekStart.thursday:Thursday`,
    FRIDAY: $localize`:@@settings.weekStart.friday:Friday`,
    SATURDAY: $localize`:@@settings.weekStart.saturday:Saturday`,
    SUNDAY: $localize`:@@settings.weekStart.sunday:Sunday`,
  };
  protected readonly themeLabels: Record<Settings['theme'], string> = {
    light: $localize`:@@theme.light:Light`,
    dark: $localize`:@@theme.dark:Dark`,
    system: $localize`:@@theme.system:System`,
  };

  protected readonly energyAria: Record<(typeof ENERGY_LEVELS)[number], string> = {
    LOW: $localize`:@@settings.energy.low:Label for low energy`,
    MEDIUM: $localize`:@@settings.energy.medium:Label for medium energy`,
    HIGH: $localize`:@@settings.energy.high:Label for high energy`,
  };
  protected readonly creativityAria: Record<(typeof CREATIVITY_LEVELS)[number], string> = {
    ROUTINE: $localize`:@@settings.creativity.routine:Label for routine work`,
    MIXED: $localize`:@@settings.creativity.mixed:Label for mixed work`,
    CREATIVE: $localize`:@@settings.creativity.creative:Label for creative work`,
  };

  private readonly labelValidators = [
    Validators.required,
    Validators.maxLength(LABEL_MAX_LENGTH),
    notBlank,
  ];

  protected readonly form = this.fb.group({
    timeZone: ['', [Validators.required, oneOf(this.zones)]],
    weekStart: this.fb.control<Settings['weekStart']>('MONDAY'),
    theme: this.fb.control<Settings['theme']>('system'),
    energyLabels: this.fb.group({
      LOW: ['', this.labelValidators],
      MEDIUM: ['', this.labelValidators],
      HIGH: ['', this.labelValidators],
    }),
    creativityLabels: this.fb.group({
      ROUTINE: ['', this.labelValidators],
      MIXED: ['', this.labelValidators],
      CREATIVE: ['', this.labelValidators],
    }),
  });

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly state = signal<Partial<Record<GeneralField, SaveState>>>({});

  /** Per input (`timeZone`, `energyLabels.LOW`, ...): the value it got from the server or sent. */
  private readonly baseline = new Map<string, unknown>();
  /** Saves per row that have not answered yet. */
  private readonly pending = new Map<GeneralField, number>();
  /** Per row: the value of its latest save, which the server holds once its pending saves answer. */
  private readonly lastSent = new Map<GeneralField, unknown>();

  private readonly zoneQuery = toSignal(this.form.controls.timeZone.valueChanges, {
    initialValue: '',
  });
  protected readonly zoneOptions = computed(() => {
    const query = this.zoneQuery().trim().toLowerCase().replaceAll(' ', '_');
    if (this.zones.some((zone) => zone.toLowerCase() === query)) return this.zones;
    const matches = query ? this.zones.filter((zone) => zone.toLowerCase().includes(query)) : [];
    return matches.length ? matches : this.zones;
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.show(await this.service.load());
    } catch (error) {
      this.loadError.set(error instanceof ApiError ? error.userMessage : String(error));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Saves one row when its value is valid and differs from the saved one, or, while saves of the
   * row are pending, from the last one sent (the settings only show the save currently running).
   */
  protected async save(field: GeneralField): Promise<void> {
    const control = this.form.controls[field];
    const saved = this.service.settings();
    if (!saved || control.invalid) return;
    const value = trimmed(control.getRawValue());
    const latest = this.pending.get(field) ? this.lastSent.get(field) : saved[field];
    if (sameValue(value, latest)) return;

    this.setState(field, 'saving');
    this.lastSent.set(field, value);
    for (const [path, input] of this.inputs(field)) this.baseline.set(path, input.getRawValue());
    this.pending.set(field, (this.pending.get(field) ?? 0) + 1);
    try {
      await this.service.update({ [field]: value } as SettingsPatch);
      this.setState(field, 'saved');
    } catch {
      // The service rolled back (and reloaded after a 412); the interceptor showed the error.
      this.setState(field, 'failed');
    }
    this.pending.set(field, (this.pending.get(field) ?? 1) - 1);
    const current = this.service.settings();
    if (current) this.refresh(current);
  }

  /** Leaving the time zone field with a partial name puts the saved zone back. */
  protected timeZoneBlur(): void {
    const control = this.form.controls.timeZone;
    if (control.invalid) {
      const saved = this.service.settings();
      if (saved) this.showField('timeZone', saved);
      return;
    }
    void this.save('timeZone');
  }

  protected stateOf(field: GeneralField): SaveState | undefined {
    return this.state()[field];
  }

  private setState(field: GeneralField, state: SaveState): void {
    this.state.update((states) => ({ ...states, [field]: state }));
  }

  private show(settings: Settings): void {
    for (const field of this.fields()) this.showField(field, settings);
  }

  /**
   * Shows `settings` in every input the user has not changed since it was filled or sent, except
   * in rows a later save is about to answer for: typing in one label while another of the same
   * group saves, or a second theme click, is kept.
   */
  private refresh(settings: Settings): void {
    for (const field of this.fields()) {
      if (this.pending.get(field)) continue;
      for (const [path, input, value] of this.inputs(field, settings)) {
        if (input.getRawValue() === this.baseline.get(path)) this.fill(path, input, value);
      }
    }
  }

  private showField(field: GeneralField, settings: Settings): void {
    for (const [path, input, value] of this.inputs(field, settings)) this.fill(path, input, value);
  }

  private fill(path: string, input: AbstractControl, value: unknown): void {
    input.reset(value);
    this.baseline.set(path, value);
  }

  private fields(): GeneralField[] {
    return Object.keys(this.form.controls) as GeneralField[];
  }

  /** The inputs of a row (each label of a group on its own), with their value in `settings`. */
  private inputs(
    field: GeneralField,
    settings?: Settings,
  ): [path: string, input: AbstractControl, value: unknown][] {
    const control: AbstractControl = this.form.controls[field];
    const value = settings?.[field];
    if (!(control instanceof FormGroup)) return [[field, control, value]];
    return Object.entries(control.controls).map(([key, input]) => [
      `${field}.${key}`,
      input,
      (value as Record<string, unknown> | undefined)?.[key],
    ]);
  }
}

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value && !control.value.trim() ? { required: true } : null;
}

function oneOf(
  values: readonly string[],
): (control: AbstractControl<string>) => ValidationErrors | null {
  const allowed = new Set(values);
  return (control) => (!control.value || allowed.has(control.value) ? null : { unknownZone: true });
}

/** Labels are saved without surrounding spaces. */
function trimmed<T>(value: T): T {
  if (typeof value !== 'object' || value === null) return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, label]) => [key, String(label).trim()]),
  ) as T;
}

/** Compares a row's value with the saved one; label maps regardless of key order. */
function sameValue(a: unknown, b: unknown): boolean {
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return a === b;
  const entries = Object.entries(a);
  return (
    entries.length === Object.keys(b).length &&
    entries.every(([key, value]) => (b as Record<string, unknown>)[key] === value)
  );
}
