import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogRef } from '@angular/material/dialog';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';
import { OccurrenceResponse } from '../../../api/generated';
import { ApiError, NotificationService } from '../../../core/http';
import { LinkedCreate, LinkedItems } from '../../../shared/links';
import { eventRef, EventLinksService, LinkFailedError } from './event-links.service';

type Creating = 'task' | 'note';

/**
 * The linked items of an event in its dialog (SPEC.md §6.3), e.g. the meeting notes and follow-up
 * tasks, with "Create task from event" and "Create note for event". Following a link closes the
 * dialog, so the linked item isn't hidden behind it.
 */
@Component({
  selector: 'app-calendar-event-links',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LinkedItems, LinkedCreate, MatButtonModule],
  template: `
    <app-linked-items #links [item]="ref()">
      <button
        mat-stroked-button
        appLinkedCreate
        type="button"
        [disabled]="creating() !== null"
        (click)="createTask()"
        i18n="@@calendar.links.createTask"
      >
        Create task from event
      </button>
      <button
        mat-stroked-button
        appLinkedCreate
        type="button"
        [disabled]="creating() !== null"
        (click)="createNote()"
        i18n="@@calendar.links.createNote"
      >
        Create note for event
      </button>
    </app-linked-items>
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
      padding-top: var(--app-space-3);
      border-top: 1px solid var(--app-border);
    }

    .error {
      margin: var(--app-space-2) 0 0;
      color: var(--mat-sys-error);
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class EventLinks {
  private readonly service = inject(EventLinksService);
  private readonly notifications = inject(NotificationService);

  /** The occurrence the dialog shows. */
  readonly occurrence = input.required<OccurrenceResponse>();
  /** The zone of the settings (the note title's date). */
  readonly timeZone = input.required<string>();

  private readonly links = viewChild<{ reload(): Promise<void> }>('links');

  protected readonly ref = computed(() => eventRef(this.occurrence()));
  protected readonly creating = signal<Creating | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const dialog = inject(MatDialogRef, { optional: true });
    if (!dialog) return;
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(() => dialog.close(undefined));
  }

  protected createTask(): Promise<void> {
    return this.create('task', async () => {
      const task = await this.service.createTask(this.occurrence());
      this.notifications.info(
        $localize`:@@calendar.links.taskCreated:Added to Inbox: ${task.title}:title:`,
      );
    });
  }

  protected createNote(): Promise<void> {
    return this.create('note', async () => {
      const note = await this.service.createNote(this.occurrence(), this.timeZone());
      this.notifications.info(
        $localize`:@@calendar.links.noteCreated:Note created: ${note.title}:title:`,
      );
    });
  }

  private async create(kind: Creating, action: () => Promise<void>): Promise<void> {
    if (this.creating()) return;
    this.creating.set(kind);
    this.error.set(null);
    try {
      await action();
    } catch (error) {
      this.error.set(messageOf(kind, error));
    } finally {
      this.creating.set(null);
    }
    // Also after a failed link: the panel shows what the server has.
    await this.links()?.reload();
  }
}

function messageOf(kind: Creating, error: unknown): string {
  if (error instanceof LinkFailedError) {
    return kind === 'task'
      ? $localize`:@@calendar.links.taskNotLinked:The task was added to the Inbox, but it could not be linked to the event.`
      : $localize`:@@calendar.links.noteNotLinked:The note was created, but it could not be linked to the event.`;
  }
  if (error instanceof ApiError) return error.userMessage;
  return kind === 'task'
    ? $localize`:@@calendar.links.taskFailed:The task could not be created.`
    : $localize`:@@calendar.links.noteFailed:The note could not be created.`;
}
