import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, Subject } from 'rxjs';
import { ApiError, NotificationService } from '../../../core/http';
import { fakeCalendar, fakeEvent, fakeOccurrence } from '../views/testing';
import { EventDialog } from './event-dialog';
import { EventEditService } from './event-edit.service';
import { EventEditor, movePatch, timesOf } from './event-editor';
import { ScopePrompt } from './scope-dialog';

const OCCURRENCE = '2026-03-30T07:00:00Z';
const MOVED = { allDay: false, start: '2026-03-23T09:00:00.000Z', end: '2026-03-23T10:00:00.000Z' };
const context = { calendars: [fakeCalendar('work')], timeZone: 'Europe/Zurich' };

describe('EventEditor', () => {
  let editor: EventEditor;
  let dialogResult: unknown;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snackActions: Subject<void>;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let notifications: { error: ReturnType<typeof vi.fn> };
  let events: { update: ReturnType<typeof vi.fn> };
  let scopes: { ask: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    dialogResult = undefined;
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    snackActions = new Subject<void>();
    snackBar = { open: vi.fn(() => ({ onAction: () => snackActions.asObservable() })) };
    notifications = { error: vi.fn() };
    events = { update: vi.fn(() => Promise.resolve({})) };
    scopes = { ask: vi.fn(() => Promise.resolve('THIS')) };
    TestBed.configureTestingModule({
      providers: [
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: NotificationService, useValue: notifications },
        { provide: EventEditService, useValue: events },
        { provide: ScopePrompt, useValue: scopes },
      ],
    });
    editor = TestBed.inject(EventEditor);
  });

  describe('move', () => {
    it('saves a moved single event without asking for a scope', async () => {
      const occurrence = fakeOccurrence('Dentist');
      await expect(editor.move(occurrence, MOVED, vi.fn())).resolves.toBe(true);
      expect(scopes.ask).not.toHaveBeenCalled();
      expect(events.update).toHaveBeenCalledWith(occurrence, undefined, {
        start: MOVED.start,
        end: MOVED.end,
      });
    });

    it('asks which occurrences of a series the move is for and saves with that scope', async () => {
      scopes.ask.mockResolvedValue('FOLLOWING');
      const occurrence = fakeOccurrence('Swim', {
        eventId: 'm1',
        seriesId: 'm1',
        occurrence: OCCURRENCE,
      });
      await expect(editor.move(occurrence, MOVED, vi.fn())).resolves.toBe(true);
      expect(scopes.ask).toHaveBeenCalledWith('change');
      expect(events.update).toHaveBeenCalledWith(occurrence, 'FOLLOWING', expect.any(Object));
    });

    it('saves nothing when the scope prompt is cancelled', async () => {
      scopes.ask.mockResolvedValue(undefined);
      const occurrence = fakeOccurrence('Swim', { seriesId: 'm1', occurrence: OCCURRENCE });
      await expect(editor.move(occurrence, MOVED, vi.fn())).resolves.toBe(false);
      expect(events.update).not.toHaveBeenCalled();
      expect(snackBar.open).not.toHaveBeenCalled();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    for (const status of [409, 412]) {
      it(`shows a ${status} conflict with a Reload action that calls reload`, async () => {
        events.update.mockRejectedValue(new ApiError(status, 'Conflict'));
        const reload = vi.fn();
        await expect(editor.move(fakeOccurrence('Dentist'), MOVED, reload)).resolves.toBe(false);
        expect(snackBar.open).toHaveBeenCalledWith(
          '"Dentist" was changed elsewhere. Reload to see the current version, then move it again.',
          'Reload',
          expect.objectContaining({ politeness: 'assertive' }),
        );
        expect(notifications.error).not.toHaveBeenCalled();
        expect(reload).not.toHaveBeenCalled();
        snackActions.next();
        expect(reload).toHaveBeenCalledOnce();
      });
    }

    it('shows a conflict of a series move with the chosen scope and saves nothing else', async () => {
      scopes.ask.mockResolvedValue('ALL');
      events.update.mockRejectedValue(new ApiError(412, 'Precondition Failed'));
      const occurrence = fakeOccurrence('Swim', {
        eventId: 'm1',
        seriesId: 'm1',
        occurrence: OCCURRENCE,
      });
      const reload = vi.fn();
      await expect(editor.move(occurrence, MOVED, reload)).resolves.toBe(false);
      expect(scopes.ask).toHaveBeenCalledExactlyOnceWith('change');
      expect(events.update).toHaveBeenCalledExactlyOnceWith(occurrence, 'ALL', {
        start: MOVED.start,
        end: MOVED.end,
      });
      expect(snackBar.open).toHaveBeenCalledOnce();
      expect(snackBar.open.mock.calls[0][0]).toContain('"Swim" was changed elsewhere');
      expect(notifications.error).not.toHaveBeenCalled();
      snackActions.next();
      expect(reload).toHaveBeenCalledOnce();
    });

    it('reports other API errors with their message', async () => {
      events.update.mockRejectedValue(
        new ApiError(400, 'Bad Request', 'The end is before the start.'),
      );
      await expect(editor.move(fakeOccurrence('Dentist'), MOVED, vi.fn())).resolves.toBe(false);
      expect(notifications.error).toHaveBeenCalledWith('The end is before the start.');
      expect(snackBar.open).not.toHaveBeenCalled();
    });

    it('reports unexpected failures generically', async () => {
      events.update.mockRejectedValue(new TypeError('boom'));
      await expect(editor.move(fakeOccurrence('Dentist'), MOVED, vi.fn())).resolves.toBe(false);
      expect(notifications.error).toHaveBeenCalledWith('The event could not be moved.');
    });
  });

  describe('create', () => {
    const draft = { allDay: false, start: MOVED.start, end: MOVED.end };

    it('opens the event dialog for a new event and reports whether it was saved', async () => {
      dialogResult = 'saved';
      await expect(editor.create(draft, context)).resolves.toBe(true);
      expect(dialog.open).toHaveBeenCalledWith(
        EventDialog,
        expect.objectContaining({ data: { mode: 'create', draft, ...context } }),
      );
      dialogResult = undefined;
      await expect(editor.create(draft, context)).resolves.toBe(false);
    });

    it('asks for a calendar first when every calendar is read-only', async () => {
      const readOnly = { ...context, calendars: [fakeCalendar('feed', { readOnly: true })] };
      await expect(editor.create(draft, readOnly)).resolves.toBe(false);
      expect(dialog.open).not.toHaveBeenCalled();
      expect(notifications.error).toHaveBeenCalledWith(
        'Add a calendar in the settings to create events.',
      );
      await expect(editor.create(draft, { ...context, calendars: [] })).resolves.toBe(false);
      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  describe('open', () => {
    it('opens the dialog of an occurrence and reports a save or delete', async () => {
      const occurrence = fakeOccurrence('Dentist');
      dialogResult = 'deleted';
      await expect(editor.open(occurrence, context)).resolves.toBe(true);
      expect(dialog.open).toHaveBeenCalledWith(
        EventDialog,
        expect.objectContaining({ data: { mode: 'edit', occurrence, ...context } }),
      );
      dialogResult = 'saved';
      await expect(editor.open(occurrence, context)).resolves.toBe(true);
      dialogResult = undefined;
      await expect(editor.open(occurrence, context)).resolves.toBe(false);
    });
  });
});

describe('EventEditor with the real dialog', () => {
  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    document.querySelectorAll('.cdk-overlay-container').forEach((o) => o.remove());
  });

  it('reports no change when the event dialog is cancelled', async () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: NotificationService, useValue: { error: vi.fn() } },
        {
          provide: EventEditService,
          useValue: { get: vi.fn(() => Promise.resolve(fakeEvent('e1', { title: 'Dentist' }))) },
        },
      ],
    });
    const changed = TestBed.inject(EventEditor).open(
      fakeOccurrence('Dentist', { eventId: 'e1' }),
      context,
    );
    TestBed.inject(ApplicationRef).tick();
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.inject(ApplicationRef).tick();
    const cancel = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent!.trim() === 'Cancel',
    )!;
    cancel.click();
    TestBed.inject(ApplicationRef).tick();
    // `true` would make the calendar refetch its events although nothing changed.
    await expect(changed).resolves.toBe(false);
  });
});

describe('movePatch', () => {
  it('sends the new instants of a timed occurrence', () => {
    expect(movePatch(fakeOccurrence('Dentist'), MOVED)).toEqual({
      start: MOVED.start,
      end: MOVED.end,
    });
  });

  it('sends the new dates of an all-day occurrence', () => {
    const allDay = fakeOccurrence('Holiday', { allDay: true });
    expect(
      movePatch(allDay, { allDay: true, startDate: '2026-04-07', endDate: '2026-04-09' }),
    ).toEqual({ startDate: '2026-04-07', endDate: '2026-04-09' });
  });

  it('sends allDay when the drag changed it', () => {
    expect(
      movePatch(fakeOccurrence('Dentist'), {
        allDay: true,
        startDate: '2026-03-23',
        endDate: '2026-03-24',
      }),
    ).toEqual({ allDay: true, startDate: '2026-03-23', endDate: '2026-03-24' });
    expect(movePatch(fakeOccurrence('Holiday', { allDay: true }), MOVED)).toEqual({
      allDay: false,
      start: MOVED.start,
      end: MOVED.end,
    });
  });
});

describe('timesOf', () => {
  it('takes the instants of a timed range', () => {
    expect(
      timesOf({
        allDay: false,
        start: new Date('2026-03-23T09:00:00Z'),
        end: new Date('2026-03-23T10:30:00Z'),
        startStr: '2026-03-23T10:00:00+01:00',
        endStr: '2026-03-23T11:30:00+01:00',
      }),
    ).toEqual({
      allDay: false,
      start: '2026-03-23T09:00:00.000Z',
      end: '2026-03-23T10:30:00.000Z',
    });
  });

  it('gives a timed range without an end one hour', () => {
    expect(
      timesOf({
        allDay: false,
        start: new Date('2026-03-23T09:00:00Z'),
        end: null,
        startStr: '2026-03-23T10:00:00+01:00',
        endStr: '',
      }),
    ).toMatchObject({ end: '2026-03-23T10:00:00.000Z' });
  });

  it('takes the dates of an all-day range, one day without an end', () => {
    expect(
      timesOf({
        allDay: true,
        start: new Date('2026-03-23T00:00:00Z'),
        end: new Date('2026-03-25T00:00:00Z'),
        startStr: '2026-03-23',
        endStr: '2026-03-25',
      }),
    ).toEqual({ allDay: true, startDate: '2026-03-23', endDate: '2026-03-25' });
    expect(
      timesOf({ allDay: true, start: null, end: null, startStr: '2026-03-23', endStr: '' }),
    ).toEqual({ allDay: true, startDate: '2026-03-23', endDate: '2026-03-24' });
  });
});
