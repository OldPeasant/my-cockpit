import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import {
  AttendeeDto,
  CalendarResponse,
  EventPatchRequest,
  OccurrenceResponse,
} from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { EditScope, EventEditService, isConflict, isSeries } from './event-edit.service';
import {
  createRequest,
  editForm,
  endsAfterStart,
  EventDraft,
  EventFormValue,
  newForm,
  patchRequest,
} from './event-form';
import { isTimeZone, timeZones } from './event-times';
import { Repeat, REPEAT_PRESETS } from './recurrence';
import { ScopePrompt } from './scope-dialog';

interface DialogBase {
  /** All calendars of the user (new events can go to the writable ones). */
  readonly calendars: readonly CalendarResponse[];
  /** The zone of the settings, for new events. */
  readonly timeZone: string;
}

export type EventDialogData =
  | (DialogBase & { readonly mode: 'create'; readonly draft: EventDraft })
  | (DialogBase & { readonly mode: 'edit'; readonly occurrence: OccurrenceResponse });

/** How the dialog closed: the event was saved or deleted (`undefined`: nothing changed). */
export type EventDialogResult = 'saved' | 'deleted' | undefined;

/** The reminder choices, in minutes before the start. */
export const REMINDER_PRESETS: readonly number[] = [0, 5, 10, 15, 30, 60, 120, 1440, 10080];

/**
 * Creates or edits an event (SPEC.md §6.3): title, calendar, time or all-day with its time zone,
 * repeat rule, location, description, reminders and attendees (editable for local calendars only,
 * synced calendars show them read-only).
 *
 * A change of a repeating event asks which occurrences it applies to (THIS / FOLLOWING / ALL;
 * a new repeat rule or calendar can't apply to one occurrence alone). Only changed fields are sent.
 * When the event changed elsewhere or at its provider since it was opened, the dialog says so and
 * offers to reload it (SPEC.md §6.2).
 */
@Component({
  selector: 'app-calendar-event-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatChipsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './event-dialog.html',
  styleUrl: './event-dialog.scss',
})
export class EventDialog {
  // Not readonly: a reload replaces the occurrence with its current version.
  protected data = inject<EventDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<EventDialog, EventDialogResult>>(MatDialogRef);
  private readonly events = inject(EventEditService);
  private readonly scopes = inject(ScopePrompt);

  protected readonly repeatPresets = REPEAT_PRESETS;
  protected readonly reminderPresets = REMINDER_PRESETS;
  protected readonly zones = timeZones();

  protected readonly form = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    calendarId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    allDay: new FormControl(false, { nonNullable: true }),
    startDate: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    startTime: new FormControl('', { nonNullable: true }),
    endDate: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    endTime: new FormControl('', { nonNullable: true }),
    timeZone: new FormControl('', { nonNullable: true }),
    repeat: new FormControl<Repeat>('none', { nonNullable: true }),
    location: new FormControl('', { nonNullable: true }),
    description: new FormControl('', { nonNullable: true }),
  });
  protected readonly allDay = signal(false);
  protected readonly reminders = signal<readonly number[]>([]);
  protected readonly attendees = signal<readonly AttendeeDto[]>([]);
  protected readonly newAttendee = new FormControl('', {
    nonNullable: true,
    validators: [Validators.email],
  });

  /** The values the dialog opened with (edit: the diff base). */
  private initial: EventFormValue | undefined;
  private masterVersion: number | undefined;
  /** The `rrule` a custom repeat keeps. */
  protected readonly customRule = signal('');
  protected readonly calendarId = signal('');

  protected readonly loading = signal(this.data.mode === 'edit');
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The event changed since it was opened: saving needs a reload first. */
  protected readonly conflict = signal(false);
  protected readonly invalidTimes = signal(false);

  protected readonly calendar = computed(() =>
    this.data.calendars.find((c) => c.id === this.calendarId()),
  );
  protected readonly readOnly = computed(
    () => this.data.mode === 'edit' && !!this.calendar()?.readOnly,
  );
  /** Attendees can be changed in local calendars only; providers own them elsewhere. */
  protected readonly attendeesEditable = computed(
    () => !this.readOnly() && (this.calendar()?.sourceType ?? 'LOCAL') === 'LOCAL',
  );
  /** The calendars an event can be saved to. */
  protected readonly targetCalendars = computed(() => {
    const writable = this.data.calendars.filter((c) => !c.readOnly);
    if (this.data.mode === 'create') return writable;
    // A synced event can't move to another calendar; the current one is always listed.
    const current = this.calendar();
    if (!current || current.sourceType !== 'LOCAL') return current ? [current] : [];
    return writable.filter((c) => c.sourceType === 'LOCAL' || c.id === current.id);
  });

  constructor() {
    this.form.controls.allDay.valueChanges.subscribe((value) => this.allDay.set(value));
    this.form.controls.calendarId.valueChanges.subscribe((value) => this.calendarId.set(value));
    if (this.data.mode === 'create') {
      const calendars = this.data.calendars.filter((c) => !c.readOnly);
      const target = calendars.find((c) => c.isDefault) ?? calendars[0];
      this.reset(newForm(this.data.draft, target?.id ?? '', this.data.timeZone));
    } else {
      void this.load();
    }
  }

  protected get isSeries(): boolean {
    return this.data.mode === 'edit' && isSeries(this.data.occurrence);
  }

  /** Loads the event (again, after a conflict) and shows its current values. */
  protected async load(): Promise<void> {
    if (this.data.mode !== 'edit') return;
    const occurrence = this.data.occurrence;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.error.set(null);
    try {
      const event = await this.events.get(occurrence.eventId);
      const master =
        occurrence.seriesId && occurrence.seriesId !== event.id
          ? await this.events.get(occurrence.seriesId)
          : event.rrule
            ? event
            : undefined;
      this.masterVersion = master?.version;
      // The answer is newer than the occurrence the view showed.
      const current: OccurrenceResponse =
        occurrence.eventId === event.id ? { ...occurrence, version: event.version } : occurrence;
      this.data = { ...this.data, occurrence: current };
      this.reset(editForm(current, event, master, this.data.timeZone));
      this.conflict.set(false);
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected addReminder(minutes: number | null): void {
    if (minutes === null) return;
    this.reminders.update((all) =>
      all.includes(minutes) ? all : [...all, minutes].sort((a, b) => a - b),
    );
  }

  protected removeReminder(minutes: number): void {
    this.reminders.update((all) => all.filter((m) => m !== minutes));
  }

  protected addAttendee(): void {
    const email = this.newAttendee.value.trim();
    if (!email || this.newAttendee.invalid) return;
    if (!this.attendees().some((a) => a.email.toLowerCase() === email.toLowerCase())) {
      this.attendees.update((all) => [...all, { email, status: 'NEEDS_ACTION' }]);
    }
    this.newAttendee.reset();
  }

  protected removeAttendee(attendee: AttendeeDto): void {
    this.attendees.update((all) => all.filter((a) => a !== attendee));
  }

  protected reminderLabel(minutes: number): string {
    if (minutes === 0) return $localize`:@@calendar.reminder.atStart:At start`;
    if (minutes % 10080 === 0) {
      return $localize`:@@calendar.reminder.weeks:${minutes / 10080}:count: week(s) before`;
    }
    if (minutes % 1440 === 0) {
      return $localize`:@@calendar.reminder.days:${minutes / 1440}:count: day(s) before`;
    }
    if (minutes % 60 === 0) {
      return $localize`:@@calendar.reminder.hours:${minutes / 60}:count: hour(s) before`;
    }
    return $localize`:@@calendar.reminder.minutes:${minutes}:count: min before`;
  }

  protected removeReminderLabel(minutes: number): string {
    return $localize`:@@calendar.reminder.remove:Remove reminder ${this.reminderLabel(minutes)}:reminder:`;
  }

  protected removeAttendeeLabel(attendee: AttendeeDto): string {
    return $localize`:@@calendar.attendee.remove:Remove guest ${attendee.name || attendee.email}:guest:`;
  }

  protected attendeeStatus(attendee: AttendeeDto): string {
    switch (attendee.status) {
      case 'ACCEPTED':
        return $localize`:@@calendar.attendee.accepted:Accepted`;
      case 'DECLINED':
        return $localize`:@@calendar.attendee.declined:Declined`;
      case 'TENTATIVE':
        return $localize`:@@calendar.attendee.tentative:Maybe`;
      case 'DELEGATED':
        return $localize`:@@calendar.attendee.delegated:Delegated`;
      default:
        return $localize`:@@calendar.attendee.pending:No answer yet`;
    }
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.saving() || this.readOnly() || this.conflict()) return;
    const value = this.value();
    this.invalidTimes.set(!endsAfterStart(value));
    if (this.form.invalid || this.invalidTimes()) return;
    if (!value.allDay && !isTimeZone(value.timeZone)) {
      this.form.controls.timeZone.setErrors({ timeZone: true });
      return;
    }
    if (this.data.mode === 'create') {
      await this.run(async () => {
        await this.events.create(createRequest(value));
        return 'saved';
      });
      return;
    }
    const patch = patchRequest(this.initial!, value, this.attendeesEditable());
    if (Object.keys(patch).length === 0) {
      this.ref.close(undefined);
      return;
    }
    const occurrence = this.data.occurrence;
    let scope: EditScope | undefined;
    if (this.isSeries) {
      scope = await this.scopes.ask('change', scopesFor(patch));
      if (!scope) return;
    }
    await this.run(async () => {
      await this.events.update(occurrence, scope, patch, this.masterVersion);
      return 'saved';
    });
  }

  protected async delete(): Promise<void> {
    if (this.data.mode !== 'edit' || this.saving() || this.readOnly()) return;
    const occurrence = this.data.occurrence;
    let scope: EditScope | undefined;
    if (this.isSeries) {
      scope = await this.scopes.ask('delete');
      if (!scope) return;
    }
    await this.run(async () => {
      await this.events.delete(occurrence, scope, this.masterVersion);
      return 'deleted';
    });
  }

  private async run(action: () => Promise<EventDialogResult>): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    // The change may be committed even if its answer never arrives here: no closing until it has.
    this.ref.disableClose = true;
    try {
      this.ref.close(await action());
    } catch (error) {
      if (isConflict(error)) this.conflict.set(true);
      else
        this.error.set(
          error instanceof ApiError
            ? (error.errors[0]?.message ?? error.userMessage)
            : $localize`:@@calendar.edit.failed:The event could not be saved.`,
        );
    } finally {
      this.ref.disableClose = false;
      this.saving.set(false);
    }
  }

  private value(): EventFormValue {
    return {
      ...this.form.getRawValue(),
      rrule: this.customRule(),
      reminderMinutes: [...this.reminders()],
      attendees: [...this.attendees()],
    };
  }

  private reset(value: EventFormValue): void {
    this.initial = value;
    const { reminderMinutes, attendees, rrule, ...fields } = value;
    this.form.reset(fields);
    this.allDay.set(value.allDay);
    this.calendarId.set(value.calendarId);
    this.customRule.set(value.repeat === 'custom' ? rrule : '');
    this.reminders.set(reminderMinutes);
    this.attendees.set(attendees);
    this.invalidTimes.set(false);
    if (this.readOnly()) this.form.disable();
    else {
      this.form.enable();
      // A synced event stays in its calendar.
      if (this.data.mode === 'edit' && this.calendar()?.sourceType !== 'LOCAL') {
        this.form.controls.calendarId.disable();
      }
    }
  }
}

/** The scopes a change can apply to: a new repeat rule or calendar is for the series only. */
export function scopesFor(patch: EventPatchRequest): EditScope[] {
  const seriesOnly = 'rrule' in patch || 'calendarId' in patch;
  return seriesOnly ? ['FOLLOWING', 'ALL'] : ['THIS', 'FOLLOWING', 'ALL'];
}
