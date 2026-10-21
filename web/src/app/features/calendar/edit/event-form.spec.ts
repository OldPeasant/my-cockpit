import { fakeEvent, fakeOccurrence } from '../views/testing';
import {
  createRequest,
  editForm,
  endsAfterStart,
  EventFormValue,
  newForm,
  patchRequest,
} from './event-form';

/** A timed form: Monday 2026-03-23, 09:00–10:00 Zurich time. */
function timedForm(fields: Partial<EventFormValue> = {}): EventFormValue {
  return {
    title: 'Dentist',
    calendarId: 'family',
    allDay: false,
    startDate: '2026-03-23',
    startTime: '09:00',
    endDate: '2026-03-23',
    endTime: '10:00',
    timeZone: 'Europe/Zurich',
    repeat: 'none',
    rrule: '',
    location: '',
    description: '',
    reminderMinutes: [],
    attendees: [],
    ...fields,
  };
}

describe('event form', () => {
  describe('newForm', () => {
    it('shows the times of a timed draft in the zone of the settings', () => {
      const form = newForm(
        { allDay: false, start: '2026-03-23T08:00:00Z', end: '2026-03-23T09:30:00Z' },
        'family',
        'Europe/Zurich',
      );
      expect(form).toEqual(timedForm({ title: '', endTime: '10:30' }));
    });

    it('uses the start as end of a draft without one', () => {
      const form = newForm({ allDay: false, start: '2026-03-23T08:00:00Z' }, 'family', 'UTC');
      expect(form).toMatchObject({ startTime: '08:00', endTime: '08:00' });
    });

    it('shows the last day of an all-day draft, not its exclusive end', () => {
      const form = newForm(
        { allDay: true, startDate: '2026-03-23', endDate: '2026-03-25' },
        'family',
        'Europe/Zurich',
      );
      expect(form).toMatchObject({
        allDay: true,
        startDate: '2026-03-23',
        endDate: '2026-03-24',
        startTime: '09:00',
        endTime: '10:00',
      });
    });

    it('ends an all-day draft without or with an empty range on its start day', () => {
      expect(newForm({ allDay: true, startDate: '2026-03-23' }, 'family', 'UTC').endDate).toBe(
        '2026-03-23',
      );
      expect(
        newForm({ allDay: true, startDate: '2026-03-23', endDate: '2026-03-23' }, 'family', 'UTC')
          .endDate,
      ).toBe('2026-03-23');
    });
  });

  describe('editForm', () => {
    it('shows the data of the event and the times of the occurrence', () => {
      const occurrence = fakeOccurrence('Dentist', {
        start: '2026-03-30T07:00:00Z',
        end: '2026-03-30T08:00:00Z',
      });
      const event = fakeEvent('e1', {
        title: 'Dentist',
        calendarId: 'family',
        location: 'Bahnhofstrasse 1',
        description: 'Bring the card',
        reminderMinutes: [60, 10],
        attendees: [{ email: 'anna@example.test', name: 'Anna', status: 'ACCEPTED' }],
      });
      const form = editForm(occurrence, event, undefined, 'UTC');
      expect(form).toEqual(
        timedForm({
          startDate: '2026-03-30',
          endDate: '2026-03-30',
          location: 'Bahnhofstrasse 1',
          description: 'Bring the card',
          reminderMinutes: [10, 60],
          attendees: [{ email: 'anna@example.test', name: 'Anna', status: 'ACCEPTED' }],
        }),
      );
      // A copy: editing the form doesn't change the event.
      expect(form.attendees[0]).not.toBe(event.attendees[0]);
    });

    it('takes the repeat rule of the series master', () => {
      const occurrence = fakeOccurrence('Swim', {
        seriesId: 'm1',
        occurrence: '2026-03-23T08:00:00Z',
      });
      const override = fakeEvent('o1', { seriesId: 'm1' });
      const master = fakeEvent('m1', { rrule: 'RRULE:FREQ=WEEKLY' });
      expect(editForm(occurrence, override, master, 'UTC')).toMatchObject({
        repeat: 'WEEKLY',
        rrule: 'FREQ=WEEKLY',
      });
      const custom = fakeEvent('m1', { rrule: 'FREQ=WEEKLY;BYDAY=MO,TH' });
      expect(editForm(occurrence, custom, undefined, 'UTC')).toMatchObject({
        repeat: 'custom',
        rrule: 'FREQ=WEEKLY;BYDAY=MO,TH',
      });
    });

    it('falls back to the zone of the occurrence, then of the settings', () => {
      const occurrence = fakeOccurrence('Call', { timeZone: 'Asia/Tokyo' });
      expect(
        editForm(occurrence, fakeEvent('e1', { timeZone: undefined }), undefined, 'UTC'),
      ).toMatchObject({ timeZone: 'Asia/Tokyo', startTime: '17:00' });
      const bare = fakeOccurrence('Call', { timeZone: undefined });
      expect(
        editForm(bare, fakeEvent('e1', { timeZone: undefined }), undefined, 'UTC'),
      ).toMatchObject({ timeZone: 'UTC', startTime: '08:00' });
    });

    it('shows an all-day occurrence with its inclusive last day', () => {
      const occurrence = fakeOccurrence('Holiday', {
        allDay: true,
        start: undefined,
        end: undefined,
        startDate: '2026-04-06',
        endDate: '2026-04-08',
      });
      expect(
        editForm(occurrence, fakeEvent('e1', { allDay: true }), undefined, 'UTC'),
      ).toMatchObject({ allDay: true, startDate: '2026-04-06', endDate: '2026-04-07' });
    });
  });

  it('checks that an event ends after it starts', () => {
    expect(endsAfterStart(timedForm())).toBe(true);
    expect(endsAfterStart(timedForm({ endTime: '09:00' }))).toBe(false);
    expect(endsAfterStart(timedForm({ endTime: '08:59' }))).toBe(false);
    expect(endsAfterStart(timedForm({ endDate: '2026-03-24', endTime: '08:00' }))).toBe(true);
    const allDay = timedForm({ allDay: true, endTime: '08:00' });
    expect(endsAfterStart(allDay)).toBe(true);
    expect(endsAfterStart({ ...allDay, endDate: '2026-03-22' })).toBe(false);
  });

  describe('createRequest', () => {
    it('sends a timed event as instants with its zone and only filled fields', () => {
      expect(createRequest(timedForm({ title: '  Dentist ', description: '  ' }), true)).toEqual({
        title: 'Dentist',
        calendarId: 'family',
        allDay: false,
        start: '2026-03-23T08:00:00.000Z',
        end: '2026-03-23T09:00:00.000Z',
        timeZone: 'Europe/Zurich',
        reminderMinutes: [],
      });
    });

    it('sends an all-day event with an exclusive end date and no zone', () => {
      const request = createRequest(
        timedForm({
          allDay: true,
          startDate: '2026-04-06',
          endDate: '2026-04-07',
          repeat: 'YEARLY',
        }),
        true,
      );
      expect(request).toEqual({
        title: 'Dentist',
        calendarId: 'family',
        allDay: true,
        startDate: '2026-04-06',
        endDate: '2026-04-08',
        rrule: 'FREQ=YEARLY',
        reminderMinutes: [],
      });
    });

    it('sends location, description, reminders, attendees and a custom rule', () => {
      const attendees = [{ email: 'anna@example.test', status: 'NEEDS_ACTION' as const }];
      expect(
        createRequest(
          timedForm({
            location: ' Room 4 ',
            description: 'Agenda\n- budget',
            reminderMinutes: [10, 60],
            attendees,
            repeat: 'custom',
            rrule: 'FREQ=WEEKLY;BYDAY=MO',
          }),
          true,
        ),
      ).toMatchObject({
        location: 'Room 4',
        description: 'Agenda\n- budget',
        reminderMinutes: [10, 60],
        attendees,
        rrule: 'FREQ=WEEKLY;BYDAY=MO',
      });
    });

    it('sends no attendees for a calendar whose provider manages them', () => {
      const attendees = [{ email: 'anna@example.test', status: 'NEEDS_ACTION' as const }];
      expect(createRequest(timedForm({ attendees }), false)).not.toHaveProperty('attendees');
    });
  });

  describe('patchRequest', () => {
    it('sends nothing when nothing changed', () => {
      expect(patchRequest(timedForm(), timedForm(), true)).toEqual({});
      // Surrounding blanks are no change.
      expect(patchRequest(timedForm(), timedForm({ title: ' Dentist  ' }), true)).toEqual({});
    });

    it('sends only the changed fields', () => {
      const initial = timedForm({
        location: 'Room 4',
        description: 'Agenda',
        reminderMinutes: [10],
      });
      expect(patchRequest(initial, { ...initial, title: ' Dentist (moved) ' }, false)).toEqual({
        title: 'Dentist (moved)',
      });
      expect(patchRequest(initial, { ...initial, reminderMinutes: [10, 60] }, false)).toEqual({
        reminderMinutes: [10, 60],
      });
      expect(patchRequest(initial, { ...initial, calendarId: 'work' }, false)).toEqual({
        calendarId: 'work',
      });
    });

    it('sends a new description as typed and a new location trimmed', () => {
      const initial = timedForm({ location: 'Room 4', description: 'Agenda' });
      expect(
        patchRequest(
          initial,
          { ...initial, location: ' Room 5 ', description: 'Agenda\n- budget\n' },
          false,
        ),
      ).toEqual({ location: 'Room 5', description: 'Agenda\n- budget\n' });
    });

    it('clears an emptied location or description with null', () => {
      const initial = timedForm({ location: 'Room 4', description: 'Agenda' });
      expect(
        patchRequest(initial, { ...initial, location: '  ', description: ' ' }, false),
      ).toEqual({
        location: null,
        description: null,
      });
    });

    it('sends the new rule, or null when the event stops repeating', () => {
      expect(patchRequest(timedForm(), timedForm({ repeat: 'WEEKLY' }), false)).toEqual({
        rrule: 'FREQ=WEEKLY',
      });
      expect(
        patchRequest(timedForm({ repeat: 'WEEKLY', rrule: 'FREQ=WEEKLY' }), timedForm(), false),
      ).toEqual({ rrule: null });
      const custom = timedForm({ repeat: 'custom', rrule: 'FREQ=WEEKLY;BYDAY=MO' });
      expect(patchRequest(custom, { ...custom }, false)).toEqual({});
    });

    it('sends attendees only for calendars that take them', () => {
      const initial = timedForm();
      const current = timedForm({
        attendees: [{ email: 'anna@example.test', status: 'NEEDS_ACTION' }],
      });
      expect(patchRequest(initial, current, false)).toEqual({});
      expect(patchRequest(initial, current, true)).toEqual({ attendees: current.attendees });
      // A missing and an empty name are the same.
      const named = timedForm({
        attendees: [{ email: 'anna@example.test', name: '', status: 'ACCEPTED' }],
      });
      expect(
        patchRequest(
          named,
          timedForm({ attendees: [{ email: 'anna@example.test', status: 'ACCEPTED' }] }),
          true,
        ),
      ).toEqual({});
      expect(
        patchRequest(
          named,
          timedForm({ attendees: [{ email: 'anna@example.test', status: 'DECLINED' }] }),
          true,
        ),
      ).toHaveProperty('attendees');
    });

    it('sends start and end together when one time changed, without an unchanged zone', () => {
      expect(patchRequest(timedForm(), timedForm({ endTime: '11:00' }), false)).toEqual({
        start: '2026-03-23T08:00:00.000Z',
        end: '2026-03-23T10:00:00.000Z',
      });
    });

    it('sends the zone when it changed, with the times in the new zone', () => {
      expect(patchRequest(timedForm(), timedForm({ timeZone: 'Asia/Tokyo' }), false)).toEqual({
        start: '2026-03-23T00:00:00.000Z',
        end: '2026-03-23T01:00:00.000Z',
        timeZone: 'Asia/Tokyo',
      });
    });

    it('turns a timed event into an all-day one', () => {
      expect(patchRequest(timedForm(), timedForm({ allDay: true }), false)).toEqual({
        allDay: true,
        startDate: '2026-03-23',
        endDate: '2026-03-24',
      });
    });

    it('turns an all-day event into a timed one with its zone', () => {
      const allDay = timedForm({ allDay: true });
      expect(patchRequest(allDay, timedForm(), false)).toEqual({
        allDay: false,
        start: '2026-03-23T08:00:00.000Z',
        end: '2026-03-23T09:00:00.000Z',
        timeZone: 'Europe/Zurich',
      });
    });

    it('ignores the hidden times of an all-day event', () => {
      const allDay = timedForm({ allDay: true });
      expect(
        patchRequest(allDay, { ...allDay, startTime: '11:00', timeZone: 'UTC' }, false),
      ).toEqual({});
      expect(patchRequest(allDay, { ...allDay, endDate: '2026-03-24' }, false)).toEqual({
        startDate: '2026-03-23',
        endDate: '2026-03-25',
      });
    });
  });
});
