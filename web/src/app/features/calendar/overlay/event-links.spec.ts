import { HttpContext } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import {
  Api,
  createNote,
  createTask,
  LinkEndResponse,
  LinkResponse,
  NoteResponse,
  OccurrenceResponse,
  TaskResponse,
} from '../../../api/generated';
import { ApiError, NotificationService, SILENT_ERRORS } from '../../../core/http';
import { LinksService } from '../../../shared/links';
import { fakeEvent, fakeOccurrence } from '../views/testing';
import { EventLinks } from './event-links';
import { eventRef, occurrenceDate, occurrenceOfEvent } from './event-links.service';

const settle = () => new Promise((resolve) => setTimeout(resolve));

const MEETING = fakeOccurrence('Planning', { eventId: 'e1' });

function fakeTask(id: string, title: string): TaskResponse {
  return {
    id,
    title,
    list: 'INBOX',
    contextIds: [],
    position: 0,
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    version: 1,
  };
}

function fakeNote(id: string, title: string): NoteResponse {
  return { id, title } as NoteResponse;
}

/** A link to the event from `type`/`id` (the new item is the source), as the panel lists it. */
function linkTo(type: 'TASK' | 'NOTE', id: string, title: string): LinkResponse {
  const event: LinkEndResponse = {
    type: 'EVENT',
    id: 'e1',
    title: 'Planning',
    available: true,
  } as LinkEndResponse;
  const item = {
    type,
    id,
    title,
    available: true,
    deepLink: `/${type.toLowerCase()}s/${id}`,
  } as LinkEndResponse;
  return {
    id: `link-${id}`,
    direction: 'INCOMING',
    origin: 'MANUAL',
    createdAt: '2026-03-01T08:00:00Z',
    source: item,
    target: event,
  };
}

describe('event links helpers', () => {
  it('links to the event that holds the occurrence', () => {
    expect(eventRef(fakeOccurrence('Swim', { eventId: 'override', seriesId: 'master' }))).toEqual({
      type: 'EVENT',
      id: 'override',
    });
  });

  it('dates an occurrence in the settings zone, an all-day one by its start date', () => {
    // 23:30 UTC is already the next day in Zurich.
    const late = fakeOccurrence('Late', { start: '2026-03-23T23:30:00Z' });
    expect(occurrenceDate(late, 'Europe/Zurich')).toBe('2026-03-24');
    expect(occurrenceDate(late, 'UTC')).toBe('2026-03-23');
    const allDay = fakeOccurrence('Trip', {
      allDay: true,
      start: undefined,
      startDate: '2026-04-02',
    });
    expect(occurrenceDate(allDay, 'Europe/Zurich')).toBe('2026-04-02');
  });

  it('has no date for an occurrence missing its start', () => {
    expect(occurrenceDate(fakeOccurrence('Timed', { start: undefined }), 'UTC')).toBe('');
    expect(
      occurrenceDate(
        fakeOccurrence('Trip', { allDay: true, start: undefined, startDate: undefined }),
        'UTC',
      ),
    ).toBe('');
  });

  it('opens a linked event as itself, a series as a whole', () => {
    const occurrence = occurrenceOfEvent(
      fakeEvent('m1', { title: 'Swim', rrule: 'FREQ=WEEKLY', version: 4 }),
    );
    expect(occurrence).toMatchObject({ eventId: 'm1', title: 'Swim', version: 4 });
    expect(occurrence.seriesId).toBeUndefined();
    expect(occurrence.occurrence).toBeUndefined();
  });
});

describe('EventLinks', () => {
  let fixture: ComponentFixture<EventLinks>;
  let root: HTMLElement;
  let linked: LinkResponse[];
  let links: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    search: ReturnType<typeof vi.fn>;
  };
  let invoke: ReturnType<typeof vi.fn>;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  const button = (text: string) =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent!.trim() === text,
    );
  const alerts = () =>
    [...root.querySelectorAll<HTMLElement>('[role="alert"]')].map((a) => a.textContent!.trim());

  async function render(
    occurrence: OccurrenceResponse = MEETING,
    dialog?: { close: ReturnType<typeof vi.fn> },
  ): Promise<void> {
    TestBed.configureTestingModule({
      imports: [EventLinks],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        ...(dialog ? [{ provide: MatDialogRef, useValue: dialog }] : []),
        { provide: Api, useValue: { invoke } },
        { provide: LinksService, useValue: links },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    fixture = TestBed.createComponent(EventLinks);
    fixture.componentRef.setInput('occurrence', occurrence);
    fixture.componentRef.setInput('timeZone', 'Europe/Zurich');
    root = fixture.nativeElement as HTMLElement;
    await stable();
  }

  async function stable(): Promise<void> {
    await settle();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    linked = [];
    links = {
      list: vi.fn(() => Promise.resolve([...linked])),
      create: vi.fn(() => Promise.resolve({})),
      remove: vi.fn(() => Promise.resolve()),
      search: vi.fn(() => Promise.resolve([])),
    };
    invoke = vi.fn((fn: unknown, params: { body: { title: string } }) => {
      if (fn === createTask) return Promise.resolve(fakeTask('t1', params.body.title));
      if (fn === createNote) return Promise.resolve(fakeNote('n1', params.body.title));
      return Promise.reject(new Error('unexpected call'));
    });
    notifications = { info: vi.fn(), error: vi.fn() };
  });

  it('lists the links of the event with both create actions', async () => {
    linked = [linkTo('NOTE', 'n0', 'Agenda')];
    await render();
    expect(links.list).toHaveBeenCalledWith({ type: 'EVENT', id: 'e1' });
    expect(root.textContent).toContain('Agenda');
    expect(button('Create task from event')).toBeDefined();
    expect(button('Create note for event')).toBeDefined();
  });

  it('creates an Inbox task from the event titled with its date, links it and shows it', async () => {
    await render();
    linked = [linkTo('TASK', 't1', 'Planning (2026-03-23)')];
    button('Create task from event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createTask,
      { body: { title: 'Planning (2026-03-23)', list: 'INBOX' } },
      expect.anything(),
    );
    expect(links.create).toHaveBeenCalledWith(
      { type: 'TASK', id: 't1' },
      { type: 'EVENT', id: 'e1' },
    );
    expect(notifications.info).toHaveBeenCalledWith('Added to Inbox: Planning (2026-03-23)');
    // The panel reloaded and lists the new task.
    expect(links.list).toHaveBeenCalledTimes(2);
    expect(root.querySelector('a[href="/tasks/t1"]')).not.toBeNull();
  });

  it('creates a note for the event titled with its date and links it', async () => {
    await render();
    linked = [linkTo('NOTE', 'n1', 'Planning (2026-03-23)')];
    button('Create note for event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createNote,
      { body: { title: 'Planning (2026-03-23)' } },
      expect.anything(),
    );
    expect(links.create).toHaveBeenCalledWith(
      { type: 'NOTE', id: 'n1' },
      { type: 'EVENT', id: 'e1' },
    );
    expect(notifications.info).toHaveBeenCalledWith('Note created: Planning (2026-03-23)');
    expect(root.querySelector('a[href="/notes/n1"]')).not.toBeNull();
  });

  it('disables the create actions while one runs', async () => {
    let finish!: (task: TaskResponse) => void;
    invoke.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)));
    await render();
    button('Create task from event')!.click();
    fixture.detectChanges();
    expect(button('Create task from event')!.disabled).toBe(true);
    expect(button('Create note for event')!.disabled).toBe(true);
    finish(fakeTask('t1', 'Planning'));
    await stable();
    expect(button('Create note for event')!.disabled).toBe(false);
  });

  it('says so when the task cannot be created and links nothing', async () => {
    invoke.mockRejectedValueOnce(new ApiError(500, 'Internal Server Error'));
    await render();
    button('Create task from event')!.click();
    await stable();
    expect(links.create).not.toHaveBeenCalled();
    expect(notifications.info).not.toHaveBeenCalled();
    expect(alerts().join(' ')).not.toBe('');
  });

  it('says so when the note was created but could not be linked', async () => {
    links.create.mockRejectedValueOnce(new ApiError(500, 'Internal Server Error'));
    await render();
    button('Create note for event')!.click();
    await stable();
    expect(alerts()).toContain('The note was created, but it could not be linked to the event.');
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('says so when a non-API error stops the note', async () => {
    invoke.mockRejectedValueOnce(new Error('offline'));
    await render();
    button('Create note for event')!.click();
    await stable();
    expect(alerts()).toContain('The note could not be created.');
  });

  it('says so when the task was added to the Inbox but could not be linked, and reloads the panel', async () => {
    links.create.mockRejectedValueOnce(new ApiError(500, 'Internal Server Error'));
    await render();
    button('Create task from event')!.click();
    await stable();
    expect(alerts()).toContain(
      'The task was added to the Inbox, but it could not be linked to the event.',
    );
    expect(notifications.info).not.toHaveBeenCalled();
    // The panel shows what the server has, also after a failed link.
    expect(links.list).toHaveBeenCalledTimes(2);
    expect(button('Create task from event')!.disabled).toBe(false);
  });

  it('says so when a non-API error stops the task', async () => {
    invoke.mockRejectedValueOnce(new Error('offline'));
    await render();
    button('Create task from event')!.click();
    await stable();
    expect(alerts()).toContain('The task could not be created.');
  });

  it('clears the previous error when a new create starts', async () => {
    invoke.mockRejectedValueOnce(new Error('offline'));
    await render();
    button('Create note for event')!.click();
    await stable();
    expect(alerts()).toContain('The note could not be created.');

    button('Create note for event')!.click();
    await stable();
    expect(alerts()).toEqual([]);
    expect(notifications.info).toHaveBeenCalledWith('Note created: Planning (2026-03-23)');
  });

  it('creates one item for a double click before the buttons are disabled', async () => {
    let finish!: (task: TaskResponse) => void;
    invoke.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)));
    await render();
    // Both clicks land before change detection disables the buttons.
    button('Create task from event')!.click();
    button('Create task from event')!.click();
    button('Create note for event')!.click();
    expect(invoke).toHaveBeenCalledTimes(1);

    finish(fakeTask('t1', 'Planning'));
    await stable();
    expect(links.create).toHaveBeenCalledTimes(1);
    expect(notifications.info).toHaveBeenCalledTimes(1);
  });

  it('titles a note without a date when the occurrence has none', async () => {
    await render(fakeOccurrence('Planning', { eventId: 'e1', start: undefined }));
    button('Create note for event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createNote,
      { body: { title: 'Planning' } },
      expect.anything(),
    );
  });

  it('titles a task without a date when the occurrence has none', async () => {
    await render(fakeOccurrence('Planning', { eventId: 'e1', start: undefined }));
    button('Create task from event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createTask,
      { body: { title: 'Planning', list: 'INBOX' } },
      expect.anything(),
    );
  });

  it('dates the task and note titles in the zone of its timeZone input, not in UTC', async () => {
    // 23:30 UTC on the 23rd is already the 24th in Zurich (the panel's zone).
    await render(fakeOccurrence('Late call', { eventId: 'e1', start: '2026-03-23T23:30:00Z' }));
    button('Create task from event')!.click();
    await stable();
    button('Create note for event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createTask,
      { body: { title: 'Late call (2026-03-24)', list: 'INBOX' } },
      expect.anything(),
    );
    expect(invoke).toHaveBeenCalledWith(
      createNote,
      { body: { title: 'Late call (2026-03-24)' } },
      expect.anything(),
    );
  });

  it('dates an all-day event by its start date', async () => {
    await render(
      fakeOccurrence('Ski trip', {
        eventId: 'e1',
        allDay: true,
        start: undefined,
        end: undefined,
        startDate: '2026-04-10',
        endDate: '2026-04-12',
      }),
    );
    button('Create task from event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createTask,
      { body: { title: 'Ski trip (2026-04-10)', list: 'INBOX' } },
      expect.anything(),
    );
  });

  it('links a generated occurrence of a series to the event that holds it', async () => {
    await render(fakeOccurrence('Standup', { eventId: 'master', seriesId: 'master' }));
    button('Create note for event')!.click();
    await stable();
    expect(links.list).toHaveBeenCalledWith({ type: 'EVENT', id: 'master' });
    expect(links.create).toHaveBeenCalledWith(
      { type: 'NOTE', id: 'n1' },
      { type: 'EVENT', id: 'master' },
    );
  });

  it('sends the create requests silently, since the panel shows their errors itself', async () => {
    await render();
    button('Create task from event')!.click();
    await stable();
    button('Create note for event')!.click();
    await stable();
    const contexts = invoke.mock.calls
      .filter(([fn]) => fn === createTask || fn === createNote)
      .map(([, , context]) => context as HttpContext);
    expect(contexts).toHaveLength(2);
    for (const context of contexts) expect(context.get(SILENT_ERRORS)).toBe(true);
  });

  it('shows the server message of a refused create', async () => {
    const refused = new ApiError(400, 'Bad Request');
    invoke.mockRejectedValueOnce(refused);
    await render();
    button('Create note for event')!.click();
    await stable();
    expect(alerts()).toContain(refused.userMessage);
    expect(links.create).not.toHaveBeenCalled();
  });

  it('closes its dialog when a link is followed', async () => {
    const dialog = { close: vi.fn() };
    await render(MEETING, dialog);
    expect(dialog.close).not.toHaveBeenCalled();
    await TestBed.inject(Router).navigateByUrl('/notes/n1');
    expect(dialog.close).toHaveBeenCalledExactlyOnceWith(undefined);
  });

  it('works outside a dialog', async () => {
    await render();
    await TestBed.inject(Router).navigateByUrl('/notes/n1');
    expect(button('Create task from event')).toBeDefined();
  });
});
