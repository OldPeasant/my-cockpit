import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSelectHarness } from '@angular/material/select/testing';
import { CalendarResponse, EventResponse, OccurrenceResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { fakeCalendar, fakeEvent, fakeOccurrence } from '../views/testing';
import { EventDialog, EventDialogData, scopesFor } from './event-dialog';
import { EventEditService } from './event-edit.service';
import { ScopePrompt } from './scope-dialog';

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

const OCCURRENCE = '2026-03-30T07:00:00Z';
const CALENDARS: readonly CalendarResponse[] = [
  fakeCalendar('feed', { name: 'Holidays', readOnly: true, sourceType: 'ICS_FEED' }),
  fakeCalendar('work', { name: 'Work' }),
  fakeCalendar('family', { name: 'Family', isDefault: true }),
];

/** A promise the test settles. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('EventDialog', () => {
  let fixture: ComponentFixture<EventDialog>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let stored: Map<string, EventResponse>;
  let events: {
    get: ReturnType<typeof vi.fn>;
    reread: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  };
  let scopes: { ask: ReturnType<typeof vi.fn> };

  const el = () => fixture.nativeElement as HTMLElement;
  const input = (name: string) =>
    el().querySelector<HTMLInputElement>(`[formcontrolname="${name}"]`)!;
  const buttons = () => [...el().querySelectorAll<HTMLButtonElement>('button')];
  const button = (text: string) => buttons().find((b) => b.textContent!.trim() === text);
  const alerts = () =>
    [...el().querySelectorAll<HTMLElement>('[role="alert"]')].map((a) => a.textContent!.trim());

  beforeEach(() => {
    stored = new Map();
    events = {
      get: vi.fn((id: string) =>
        stored.has(id)
          ? Promise.resolve(stored.get(id)!)
          : Promise.reject(new ApiError(404, 'Not Found')),
      ),
      // The view's occurrence is current unless a test says otherwise.
      reread: vi.fn((occurrence: OccurrenceResponse) => Promise.resolve(occurrence)),
      create: vi.fn(() => Promise.resolve(fakeEvent('new'))),
      update: vi.fn(() => Promise.resolve(fakeEvent('e1'))),
      delete: vi.fn(() => Promise.resolve()),
    };
    scopes = { ask: vi.fn(() => Promise.resolve('THIS')) };
  });

  async function open(data: EventDialogData): Promise<void> {
    ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
      imports: [EventDialog],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: EventEditService, useValue: events },
        { provide: ScopePrompt, useValue: scopes },
      ],
    });
    fixture = TestBed.createComponent(EventDialog);
    await stable();
  }

  async function stable(): Promise<void> {
    await settle();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function edit(occurrence: OccurrenceResponse, calendars = CALENDARS): Promise<void> {
    return open({ mode: 'edit', occurrence, calendars, timeZone: 'Europe/Zurich' });
  }

  function type(name: string, value: string): void {
    const field = input(name);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function save(): Promise<void> {
    el().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await stable();
  }

  async function chooseRepeat(text: string): Promise<void> {
    const loader = TestbedHarnessEnvironment.loader(fixture);
    const repeat = (await loader.getAllHarnesses(MatSelectHarness))[0];
    await repeat.open();
    await repeat.clickOptions({ text });
    await stable();
  }

  describe('create', () => {
    const draft = { allDay: false, start: '2026-03-23T08:00:00Z', end: '2026-03-23T09:00:00Z' };

    beforeEach(() =>
      open({ mode: 'create', draft, calendars: CALENDARS, timeZone: 'Europe/Zurich' }),
    );

    it('opens with the selected times in the default calendar', async () => {
      expect(el().querySelector('h2')!.textContent!.trim()).toBe('New event');
      expect(input('startDate').value).toBe('2026-03-23');
      expect(input('startTime').value).toBe('09:00');
      expect(input('endTime').value).toBe('10:00');
      expect(input('timeZone').value).toBe('Europe/Zurich');
      const loader = TestbedHarnessEnvironment.loader(fixture);
      const calendar = (await loader.getAllHarnesses(MatSelectHarness))[1];
      expect(await calendar.getValueText()).toBe('Family');
      await calendar.open();
      // Read-only calendars can't take new events.
      expect(await Promise.all((await calendar.getOptions()).map((o) => o.getText()))).toEqual([
        'Work',
        'Family',
      ]);
      expect(button('Delete')).toBeUndefined();
    });

    it('creates the event and closes as saved', async () => {
      type('title', ' Dentist ');
      type('location', 'Bahnhofstrasse 1');
      await chooseRepeat('Every week');
      await save();
      expect(events.create).toHaveBeenCalledExactlyOnceWith({
        title: 'Dentist',
        calendarId: 'family',
        allDay: false,
        start: '2026-03-23T08:00:00.000Z',
        end: '2026-03-23T09:00:00.000Z',
        timeZone: 'Europe/Zurich',
        rrule: 'FREQ=WEEKLY',
        location: 'Bahnhofstrasse 1',
        reminderMinutes: [],
      });
      expect(ref.close).toHaveBeenCalledExactlyOnceWith('saved');
    });

    it('needs a title', async () => {
      await save();
      expect(events.create).not.toHaveBeenCalled();
      expect(el().textContent).toContain('Enter a title.');
    });

    it('needs the end after the start', async () => {
      type('title', 'Dentist');
      type('endTime', '08:30');
      await save();
      expect(events.create).not.toHaveBeenCalled();
      expect(alerts()).toContain('The event must end after it starts.');
    });

    it('needs a known time zone', async () => {
      type('title', 'Dentist');
      type('timeZone', 'Mars/Olympus_Mons');
      await save();
      expect(events.create).not.toHaveBeenCalled();
      expect(el().textContent).toContain('Choose a time zone from the list');
    });

    it('creates an all-day event with its exclusive end date', async () => {
      type('title', 'Holiday');
      el().querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
      await stable();
      expect(input('startTime')).toBeNull();
      await save();
      expect(events.create).toHaveBeenCalledWith(
        expect.objectContaining({ allDay: true, startDate: '2026-03-23', endDate: '2026-03-24' }),
      );
    });

    it('shows why the server refused the event and keeps the dialog open', async () => {
      events.create.mockRejectedValueOnce(
        new ApiError(400, 'Bad Request', 'Invalid event', undefined, undefined, [
          { field: 'title', message: 'The title is too long.' },
        ]),
      );
      type('title', 'Dentist');
      await save();
      expect(alerts()).toContain('The title is too long.');
      expect(ref.close).not.toHaveBeenCalled();
      expect(ref.disableClose).toBe(false);

      events.create.mockRejectedValueOnce(new TypeError('boom'));
      await save();
      expect(alerts()).toContain('The event could not be saved.');
    });

    it('cannot be closed while the event is being saved', async () => {
      const pending = deferred<EventResponse>();
      events.create.mockReturnValueOnce(pending.promise);
      type('title', 'Dentist');
      await save();
      expect(ref.disableClose).toBe(true);
      expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
      pending.resolve(fakeEvent('new'));
      await stable();
      expect(ref.disableClose).toBe(false);
      expect(ref.close).toHaveBeenCalledWith('saved');
    });
  });

  describe('edit a single event', () => {
    const occurrence = () =>
      fakeOccurrence('Dentist', { eventId: 'e1', calendarId: 'family', version: 1 });

    beforeEach(() => {
      stored.set(
        'e1',
        fakeEvent('e1', {
          title: 'Dentist',
          calendarId: 'family',
          location: 'Bahnhofstrasse 1',
          reminderMinutes: [10],
          version: 2,
        }),
      );
    });

    it('loads the event and shows its current values', async () => {
      await edit(occurrence());
      expect(events.get).toHaveBeenCalledExactlyOnceWith('e1');
      expect(el().querySelector('h2')!.textContent!.trim()).toBe('Edit event');
      expect(input('title').value).toBe('Dentist');
      expect(input('location').value).toBe('Bahnhofstrasse 1');
      expect(el().textContent).toContain('10 min before');
      expect(button('Delete')).toBeDefined();
    });

    it('sends only the changed fields with the loaded version, without asking a scope', async () => {
      await edit(occurrence());
      type('title', 'Dentist (moved)');
      await save();
      expect(scopes.ask).not.toHaveBeenCalled();
      expect(events.update).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ eventId: 'e1', version: 2 }),
        undefined,
        { title: 'Dentist (moved)' },
      );
      expect(ref.close).toHaveBeenCalledWith('saved');
    });

    it('closes as unchanged on Cancel', async () => {
      await edit(occurrence());
      type('title', 'Dentist (moved)');
      button('Cancel')!.click();
      expect(events.update).not.toHaveBeenCalled();
      expect(ref.close).toHaveBeenCalledTimes(1);
      // EventDialogResult: `undefined` means nothing changed (EventEditor.open relies on it).
      expect(ref.close.mock.calls[0][0]).toBeUndefined();
    });

    it('closes without a request when nothing changed', async () => {
      await edit(occurrence());
      await save();
      expect(events.update).not.toHaveBeenCalled();
      expect(ref.close).toHaveBeenCalledExactlyOnceWith(undefined);
    });

    for (const status of [409, 412]) {
      it(`shows a ${status} conflict with Reload, which loads the current version`, async () => {
        await edit(occurrence());
        events.update.mockRejectedValueOnce(new ApiError(status, 'Conflict'));
        type('title', 'Dentist (moved)');
        await save();
        expect(ref.close).not.toHaveBeenCalled();
        expect(alerts().join(' ')).toContain('This event was changed elsewhere');
        expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);

        // Saving again is refused until the event was reloaded.
        await save();
        expect(events.update).toHaveBeenCalledTimes(1);

        stored.set(
          'e1',
          fakeEvent('e1', { title: 'Dentist at 11', calendarId: 'family', version: 3 }),
        );
        button('Reload')!.click();
        await stable();
        expect(events.get).toHaveBeenCalledTimes(2);
        expect(alerts().join(' ')).not.toContain('changed elsewhere');
        expect(input('title').value).toBe('Dentist at 11');

        type('title', 'Dentist at 12');
        await save();
        expect(events.update).toHaveBeenLastCalledWith(
          expect.objectContaining({ version: 3 }),
          undefined,
          { title: 'Dentist at 12' },
        );
        expect(ref.close).toHaveBeenCalledWith('saved');
      });
    }

    it('shows the current times of the event, not those of the view, also after Reload', async () => {
      // The view still shows 09:00-10:00; the event was moved to 11:00-12:00 elsewhere.
      stored.set(
        'e1',
        fakeEvent('e1', {
          title: 'Dentist',
          calendarId: 'family',
          start: '2026-03-23T10:00:00Z',
          end: '2026-03-23T11:00:00Z',
          version: 2,
        }),
      );
      await edit(occurrence());
      expect(input('startTime').value).toBe('11:00');
      expect(input('endTime').value).toBe('12:00');
      expect(events.reread).not.toHaveBeenCalled();

      events.update.mockRejectedValueOnce(new ApiError(412, 'Precondition Failed'));
      type('title', 'Dentist (moved)');
      await save();
      // Moved again elsewhere, to the next day 14:00-15:30.
      stored.set(
        'e1',
        fakeEvent('e1', {
          title: 'Dentist',
          calendarId: 'family',
          start: '2026-03-24T13:00:00Z',
          end: '2026-03-24T14:30:00Z',
          version: 3,
        }),
      );
      button('Reload')!.click();
      await stable();
      expect(input('startDate').value).toBe('2026-03-24');
      expect(input('startTime').value).toBe('14:00');
      expect(input('endTime').value).toBe('15:30');

      // Changing only the end sends the current start, not the one the view had.
      type('endTime', '16:00');
      await save();
      expect(events.update).toHaveBeenLastCalledWith(
        expect.objectContaining({ eventId: 'e1', version: 3 }),
        undefined,
        { start: '2026-03-24T13:00:00.000Z', end: '2026-03-24T15:00:00.000Z' },
      );
    });

    it('offers a retry when the event cannot be loaded', async () => {
      stored.clear();
      await edit(occurrence());
      expect(alerts().join(' ')).toContain('The event could not be loaded.');
      expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
      expect(button('Delete')).toBeUndefined();

      stored.set('e1', fakeEvent('e1', { title: 'Dentist', calendarId: 'family' }));
      button('Try again')!.click();
      await stable();
      expect(input('title').value).toBe('Dentist');
    });

    it('deletes the event without asking a scope', async () => {
      await edit(occurrence());
      button('Delete')!.click();
      await stable();
      expect(scopes.ask).not.toHaveBeenCalled();
      expect(events.delete).toHaveBeenCalledWith(
        expect.objectContaining({ eventId: 'e1', version: 2 }),
        undefined,
      );
      expect(ref.close).toHaveBeenCalledWith('deleted');
    });

    it('lets guests of a local event be added and removed', async () => {
      await edit(occurrence());
      expect(el().textContent).toContain('No guests.');
      const email = el().querySelector<HTMLInputElement>('input[type="email"]')!;
      email.value = 'anna@example.test';
      email.dispatchEvent(new Event('input'));
      button('Add guest')!.click();
      await stable();
      expect(el().querySelector('.attendees')!.textContent).toContain('anna@example.test');
      await save();
      expect(events.update).toHaveBeenCalledWith(expect.anything(), undefined, {
        attendees: [{ email: 'anna@example.test', status: 'NEEDS_ACTION' }],
      });
    });

    it('adds guests on Enter once per address and refuses an invalid one', async () => {
      stored.set(
        'e1',
        fakeEvent('e1', {
          title: 'Dentist',
          calendarId: 'family',
          attendees: [{ email: 'anna@example.test', name: 'Anna', status: 'DECLINED' }],
          version: 2,
        }),
      );
      await edit(occurrence());
      const email = el().querySelector<HTMLInputElement>('input[type="email"]')!;
      const enter = (value: string) => {
        email.value = value;
        email.dispatchEvent(new Event('input'));
        const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
        email.dispatchEvent(event);
        fixture.detectChanges();
        return event;
      };
      // Enter adds the guest instead of submitting the form.
      expect(enter('not-an-address').defaultPrevented).toBe(true);
      email.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
      expect(el().textContent).toContain('Enter an email address.');
      expect(el().querySelectorAll('.attendees li')).toHaveLength(1);
      enter('ANNA@example.test');
      enter('ben@example.test');
      await stable();
      const rows = [...el().querySelectorAll<HTMLElement>('.attendees li')];
      expect(rows.map((row) => row.querySelector('.attendee-name')!.textContent!.trim())).toEqual([
        'Anna',
        'ben@example.test',
      ]);
      expect(rows.map((row) => row.querySelector('.attendee-status')!.textContent!.trim())).toEqual(
        ['Declined', 'No answer yet'],
      );
    });

    it('removes a guest of a local event', async () => {
      stored.set(
        'e1',
        fakeEvent('e1', {
          title: 'Dentist',
          calendarId: 'family',
          attendees: [
            { email: 'anna@example.test', name: 'Anna', status: 'TENTATIVE' },
            { email: 'ben@example.test', status: 'DELEGATED' },
          ],
          version: 2,
        }),
      );
      await edit(occurrence());
      expect(el().querySelector('.attendees')!.textContent).toContain('Maybe');
      expect(el().querySelector('.attendees')!.textContent).toContain('Delegated');
      el().querySelector<HTMLButtonElement>('button[aria-label="Remove guest Anna"]')!.click();
      await stable();
      await save();
      expect(events.update).toHaveBeenCalledWith(expect.anything(), undefined, {
        attendees: [{ email: 'ben@example.test', status: 'DELEGATED' }],
      });
    });

    it('adds and removes reminders', async () => {
      await edit(occurrence());
      const loader = TestbedHarnessEnvironment.loader(fixture);
      const add = (await loader.getAllHarnesses(MatSelectHarness))[2];
      await add.open();
      const options = await Promise.all((await add.getOptions()).map((o) => o.getText()));
      expect(options).toEqual([
        'At start',
        '5 min before',
        '10 min before',
        '15 min before',
        '30 min before',
        '1 hour(s) before',
        '2 hour(s) before',
        '1 day(s) before',
        '1 week(s) before',
      ]);
      // The reminder the event has can't be added twice.
      expect(await (await add.getOptions({ text: '10 min before' }))[0].isDisabled()).toBe(true);
      await add.clickOptions({ text: '1 day(s) before' });
      await stable();
      el()
        .querySelector<HTMLButtonElement>('button[aria-label="Remove reminder 10 min before"]')!
        .click();
      await stable();
      await save();
      expect(events.update).toHaveBeenCalledWith(expect.anything(), undefined, {
        reminderMinutes: [1440],
      });
    });

    it('says it is loading until the event arrives', async () => {
      const pending = deferred<EventResponse>();
      events.get.mockReturnValueOnce(pending.promise);
      await edit(occurrence());
      expect(el().textContent).toContain('Loading the event…');
      expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
      pending.resolve(stored.get('e1')!);
      await stable();
      expect(el().textContent).not.toContain('Loading the event…');
      expect(input('title').value).toBe('Dentist');
    });

    it('shows a conflict of a delete with Reload', async () => {
      await edit(occurrence());
      events.delete.mockRejectedValueOnce(new ApiError(409, 'Conflict'));
      button('Delete')!.click();
      await stable();
      expect(ref.close).not.toHaveBeenCalled();
      expect(alerts().join(' ')).toContain('This event was changed elsewhere');
      expect(button('Reload')).toBeDefined();
    });
  });

  describe('edit a series', () => {
    /** An occurrence the master `m1` generates. */
    const generated = () =>
      fakeOccurrence('Swim', {
        eventId: 'm1',
        seriesId: 'm1',
        occurrence: OCCURRENCE,
        calendarId: 'family',
        start: OCCURRENCE,
        end: '2026-03-30T08:00:00Z',
        version: 4,
      });

    beforeEach(() => {
      stored.set(
        'm1',
        fakeEvent('m1', { title: 'Swim', calendarId: 'family', rrule: 'FREQ=WEEKLY', version: 4 }),
      );
    });

    it('asks which occurrences a change is for and saves with that scope', async () => {
      scopes.ask.mockResolvedValueOnce('FOLLOWING');
      await edit(generated());
      type('title', 'Swim (pool B)');
      await save();
      expect(scopes.ask).toHaveBeenCalledExactlyOnceWith('change', ['THIS', 'FOLLOWING', 'ALL']);
      expect(events.update).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ eventId: 'm1', occurrence: OCCURRENCE }),
        'FOLLOWING',
        { title: 'Swim (pool B)' },
      );
      expect(ref.close).toHaveBeenCalledWith('saved');
    });

    it('keeps the dialog open with the changes when the scope prompt is cancelled', async () => {
      scopes.ask.mockResolvedValueOnce(undefined);
      await edit(generated());
      type('title', 'Swim (pool B)');
      await save();
      expect(events.update).not.toHaveBeenCalled();
      expect(ref.close).not.toHaveBeenCalled();
      expect(input('title').value).toBe('Swim (pool B)');
      expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(false);
    });

    it('does not offer this occurrence alone for a new repeat rule', async () => {
      scopes.ask.mockResolvedValueOnce('ALL');
      await edit(generated());
      await chooseRepeat('Every day');
      await save();
      expect(scopes.ask).toHaveBeenCalledWith('change', ['FOLLOWING', 'ALL']);
      expect(events.update).toHaveBeenCalledWith(expect.anything(), 'ALL', { rrule: 'FREQ=DAILY' });
    });

    it('keeps a custom repeat rule it cannot edit', async () => {
      stored.set(
        'm1',
        fakeEvent('m1', {
          title: 'Swim',
          calendarId: 'family',
          rrule: 'FREQ=WEEKLY;BYDAY=MO,WE',
          version: 4,
        }),
      );
      await edit(generated());
      const loader = TestbedHarnessEnvironment.loader(fixture);
      const repeat = (await loader.getAllHarnesses(MatSelectHarness))[0];
      expect(await repeat.getValueText()).toBe('Custom (FREQ=WEEKLY;BYDAY=MO,WE)');
      type('title', 'Swim (pool B)');
      await save();
      expect(scopes.ask).toHaveBeenCalledWith('change', ['THIS', 'FOLLOWING', 'ALL']);
      expect(events.update).toHaveBeenCalledWith(expect.anything(), 'THIS', {
        title: 'Swim (pool B)',
      });
    });

    it('asks a scope for a delete and deletes nothing when cancelled', async () => {
      await edit(generated());
      scopes.ask.mockResolvedValueOnce(undefined);
      button('Delete')!.click();
      await stable();
      expect(scopes.ask).toHaveBeenCalledWith('delete');
      expect(events.delete).not.toHaveBeenCalled();
      expect(ref.close).not.toHaveBeenCalled();

      scopes.ask.mockResolvedValueOnce('THIS');
      button('Delete')!.click();
      await stable();
      expect(events.delete).toHaveBeenCalledWith(expect.anything(), 'THIS');
      expect(ref.close).toHaveBeenCalledWith('deleted');
    });

    it('reads a generated occurrence again and shows its current times and version', async () => {
      // The series moved to 10:00 since the view loaded.
      events.reread.mockResolvedValueOnce({
        ...generated(),
        start: '2026-03-30T08:00:00Z',
        end: '2026-03-30T09:00:00Z',
        version: 5,
      });
      stored.set(
        'm1',
        fakeEvent('m1', { title: 'Swim', calendarId: 'family', rrule: 'FREQ=WEEKLY', version: 5 }),
      );
      scopes.ask.mockResolvedValueOnce('THIS');
      await edit(generated());
      expect(events.reread).toHaveBeenCalledExactlyOnceWith(generated());
      expect(input('startTime').value).toBe('10:00');
      expect(input('endTime').value).toBe('11:00');
      type('endTime', '11:30');
      await save();
      expect(events.update).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ eventId: 'm1', occurrence: OCCURRENCE, version: 5 }),
        'THIS',
        { start: '2026-03-30T08:00:00.000Z', end: '2026-03-30T09:30:00.000Z' },
      );
    });

    it('edits the override a generated occurrence became since the view loaded', async () => {
      events.reread.mockResolvedValueOnce(
        fakeOccurrence('Swim (late)', {
          eventId: 'o1',
          seriesId: 'm1',
          occurrence: OCCURRENCE,
          calendarId: 'family',
          start: '2026-03-30T17:00:00Z',
          end: '2026-03-30T18:00:00Z',
          version: 1,
        }),
      );
      stored.set(
        'o1',
        fakeEvent('o1', {
          title: 'Swim (late)',
          calendarId: 'family',
          seriesId: 'm1',
          start: '2026-03-30T17:00:00Z',
          end: '2026-03-30T18:00:00Z',
          version: 1,
        }),
      );
      await edit(generated());
      expect(events.get.mock.calls.map((call) => call[0])).toEqual(['o1', 'm1']);
      expect(input('title').value).toBe('Swim (late)');
      expect(input('startTime').value).toBe('19:00');
      type('title', 'Swim (later)');
      await save();
      expect(events.update).toHaveBeenCalledWith(
        expect.objectContaining({ eventId: 'o1', version: 1 }),
        'THIS',
        { title: 'Swim (later)' },
      );
    });

    it('cannot be edited when the series no longer has the occurrence', async () => {
      events.reread.mockResolvedValueOnce(undefined);
      await edit(generated());
      expect(alerts().join(' ')).toContain('The event could not be loaded.');
      expect(events.get).not.toHaveBeenCalled();
      expect(el().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
    });

    it('loads an override with the rule of its master and saves it with its own version', async () => {
      stored.set(
        'o1',
        fakeEvent('o1', { title: 'Swim (late)', calendarId: 'family', seriesId: 'm1', version: 2 }),
      );
      stored.set(
        'm1',
        fakeEvent('m1', { title: 'Swim', calendarId: 'family', rrule: 'FREQ=WEEKLY', version: 6 }),
      );
      scopes.ask.mockResolvedValueOnce('ALL');
      await edit(
        fakeOccurrence('Swim (late)', {
          eventId: 'o1',
          seriesId: 'm1',
          occurrence: OCCURRENCE,
          calendarId: 'family',
          version: 1,
        }),
      );
      expect(events.get.mock.calls.map((call) => call[0])).toEqual(['o1', 'm1']);
      expect(input('title').value).toBe('Swim (late)');
      expect(el().textContent).toContain('Every week');
      type('location', 'Pool B');
      await save();
      expect(events.update).toHaveBeenCalledWith(
        expect.objectContaining({ eventId: 'o1', version: 2 }),
        'ALL',
        { location: 'Pool B' },
      );
    });

    it('shows a conflict of a series change with Reload', async () => {
      scopes.ask.mockResolvedValueOnce('ALL');
      events.update.mockRejectedValueOnce(new ApiError(412, 'Precondition Failed'));
      await edit(generated());
      type('title', 'Swim (pool B)');
      await save();
      expect(alerts().join(' ')).toContain('This event was changed elsewhere');
      expect(ref.close).not.toHaveBeenCalled();

      stored.set(
        'm1',
        fakeEvent('m1', { title: 'Swim', calendarId: 'family', rrule: 'FREQ=WEEKLY', version: 5 }),
      );
      events.reread.mockResolvedValueOnce({ ...generated(), version: 5 });
      button('Reload')!.click();
      await stable();
      expect(events.reread).toHaveBeenCalledTimes(2);
      scopes.ask.mockResolvedValueOnce('ALL');
      type('title', 'Swim (pool C)');
      await save();
      expect(events.update).toHaveBeenLastCalledWith(
        expect.objectContaining({ version: 5 }),
        'ALL',
        { title: 'Swim (pool C)' },
      );
    });
  });

  describe('read-only and synced calendars', () => {
    it('sends no guests of a new event moved to a synced calendar', async () => {
      const google = fakeCalendar('google', { name: 'Google', sourceType: 'GOOGLE' });
      await open({
        mode: 'create',
        draft: { allDay: false, start: '2026-03-23T08:00:00Z', end: '2026-03-23T09:00:00Z' },
        calendars: [...CALENDARS, google],
        timeZone: 'Europe/Zurich',
      });
      type('title', 'Planning');
      const email = el().querySelector<HTMLInputElement>('input[type="email"]')!;
      email.value = 'anna@example.test';
      email.dispatchEvent(new Event('input'));
      button('Add guest')!.click();
      await stable();
      const loader = TestbedHarnessEnvironment.loader(fixture);
      const calendar = (await loader.getAllHarnesses(MatSelectHarness))[1];
      await calendar.open();
      await calendar.clickOptions({ text: 'Google' });
      await stable();
      expect(el().textContent).toContain("Guests are managed by the event's calendar provider.");
      await save();
      expect(events.create).toHaveBeenCalledOnce();
      expect(events.create.mock.calls[0][0]).toMatchObject({ calendarId: 'google' });
      expect(events.create.mock.calls[0][0]).not.toHaveProperty('attendees');
    });

    it('shows an event of a read-only calendar without editing', async () => {
      stored.set('h1', fakeEvent('h1', { title: 'Easter Monday', calendarId: 'feed' }));
      await edit(fakeOccurrence('Easter Monday', { eventId: 'h1', calendarId: 'feed' }));
      expect(el().querySelector('h2')!.textContent!.trim()).toBe('Event');
      expect(el().textContent).toContain('This calendar is read-only');
      expect(input('title').disabled).toBe(true);
      expect(el().querySelector('button[type="submit"]')).toBeNull();
      expect(button('Delete')).toBeUndefined();
      expect(button('Close')).toBeDefined();
      expect(el().textContent).not.toContain('Add a reminder');
    });

    it('shows guests of a synced event read-only and keeps it in its calendar', async () => {
      const google = fakeCalendar('google', { name: 'Google', sourceType: 'GOOGLE' });
      stored.set(
        'g1',
        fakeEvent('g1', {
          title: 'Planning',
          calendarId: 'google',
          attendees: [{ email: 'ben@example.test', name: 'Ben', status: 'ACCEPTED' }],
        }),
      );
      await edit(fakeOccurrence('Planning', { eventId: 'g1', calendarId: 'google' }), [
        ...CALENDARS,
        google,
      ]);
      const guests = el().querySelector('.attendees')!.textContent!;
      expect(guests).toContain('Ben');
      expect(guests).toContain('Accepted');
      expect(el().textContent).toContain("Guests are managed by the event's calendar provider.");
      expect(button('Add guest')).toBeUndefined();
      expect(el().querySelector('.attendees button')).toBeNull();
      const loader = TestbedHarnessEnvironment.loader(fixture);
      const calendar = (await loader.getAllHarnesses(MatSelectHarness))[1];
      expect(await calendar.isDisabled()).toBe(true);

      // Other fields can still change; attendees are not sent.
      type('title', 'Planning Q2');
      await save();
      expect(events.update).toHaveBeenCalledWith(expect.anything(), undefined, {
        title: 'Planning Q2',
      });
    });
  });
});

describe('scopesFor', () => {
  it('offers every scope for a plain change', () => {
    expect(scopesFor({ title: 'Swim' })).toEqual(['THIS', 'FOLLOWING', 'ALL']);
    expect(scopesFor({ start: OCCURRENCE, end: OCCURRENCE })).toEqual(['THIS', 'FOLLOWING', 'ALL']);
  });

  it('offers no single occurrence for a new repeat rule or calendar', () => {
    expect(scopesFor({ rrule: 'FREQ=DAILY' })).toEqual(['FOLLOWING', 'ALL']);
    expect(scopesFor({ rrule: null })).toEqual(['FOLLOWING', 'ALL']);
    expect(scopesFor({ calendarId: 'work', title: 'Swim' })).toEqual(['FOLLOWING', 'ALL']);
  });
});
