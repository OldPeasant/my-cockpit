import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
import { ApiError, NotificationService } from '../../../core/http';
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

  async function render(occurrence: OccurrenceResponse = MEETING): Promise<void> {
    TestBed.configureTestingModule({
      imports: [EventLinks],
      providers: [
        provideRouter([]),
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

  it('creates an Inbox task from the event, links it and shows it', async () => {
    await render();
    linked = [linkTo('TASK', 't1', 'Planning')];
    button('Create task from event')!.click();
    await stable();
    expect(invoke).toHaveBeenCalledWith(
      createTask,
      { body: { title: 'Planning', list: 'INBOX' } },
      expect.anything(),
    );
    expect(links.create).toHaveBeenCalledWith(
      { type: 'TASK', id: 't1' },
      { type: 'EVENT', id: 'e1' },
    );
    expect(notifications.info).toHaveBeenCalledWith('Added to Inbox: Planning');
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
});
