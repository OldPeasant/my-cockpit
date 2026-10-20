import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ChangeEvent } from '../../api/generated';
import { AuthService } from '../auth';
import {
  CHANGE_EVENT_NAME,
  ChangeEventsService,
  EVENT_SOURCE_FACTORY,
  EVENTS_PATH,
  RECONNECT_BACKOFF,
} from './change-events.service';

/** Minimal EventSource double: tests drive open, messages and errors by hand. */
class FakeEventSource {
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((event: MessageEvent) => void)[]>();

  constructor(readonly url: string) {}

  addEventListener(name: string, listener: (event: MessageEvent) => void): void {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
  }

  open(): void {
    this.onopen?.(new Event('open'));
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }

  emit(name: string, data: string): void {
    for (const listener of this.listeners.get(name) ?? []) {
      listener(new MessageEvent(name, { data }));
    }
  }
}

function change(type: string, id = '0190a5c8-0000-7000-8000-000000000001'): ChangeEvent {
  return { type, id, op: 'UPDATED', at: '2026-10-20T10:00:00Z' };
}

describe('ChangeEventsService', () => {
  let sources: FakeEventSource[];
  let loggedIn: ReturnType<typeof signal<boolean>>;

  const latest = () => sources[sources.length - 1];

  function create(initiallyLoggedIn = true): ChangeEventsService {
    sources = [];
    loggedIn = signal(initiallyLoggedIn);
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { loggedIn: loggedIn.asReadonly() } },
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            const source = new FakeEventSource(url);
            sources.push(source);
            return source as unknown as EventSource;
          },
        },
        { provide: RECONNECT_BACKOFF, useValue: { initialMs: 1000, maxMs: 8000 } },
      ],
    });
    const service = TestBed.inject(ChangeEventsService);
    TestBed.tick();
    return service;
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('connects to the events endpoint when logged in', () => {
    const service = create();
    expect(sources).toHaveLength(1);
    expect(latest().url).toBe(EVENTS_PATH);
    latest().open();
    expect(service.connected()).toBe(true);
  });

  it('waits for the login, and closes the stream on logout', () => {
    const service = create(false);
    expect(sources).toHaveLength(0);

    loggedIn.set(true);
    TestBed.tick();
    expect(sources).toHaveLength(1);
    latest().open();

    loggedIn.set(false);
    TestBed.tick();
    expect(latest().closed).toBe(true);
    expect(service.connected()).toBe(false);
  });

  it('dispatches each event only to the subscribers of its type', () => {
    const service = create();
    const tasks: ChangeEvent[] = [];
    const notes: ChangeEvent[] = [];
    service.events$('TASK').subscribe((event) => tasks.push(event));
    service.events$('NOTE').subscribe((event) => notes.push(event));
    latest().open();

    latest().emit(CHANGE_EVENT_NAME, JSON.stringify(change('TASK')));
    latest().emit(CHANGE_EVENT_NAME, JSON.stringify(change('NOTE')));
    latest().emit(CHANGE_EVENT_NAME, JSON.stringify(change('TASK', 'other')));

    expect(tasks.map((event) => event.id)).toEqual([
      '0190a5c8-0000-7000-8000-000000000001',
      'other',
    ]);
    expect(notes).toEqual([change('NOTE')]);
  });

  it('ignores malformed event data', () => {
    const service = create();
    const received: ChangeEvent[] = [];
    service.events$('TASK').subscribe((event) => received.push(event));
    latest().open();

    latest().emit(CHANGE_EVENT_NAME, 'not json');
    latest().emit(CHANGE_EVENT_NAME, 'null');
    latest().emit(CHANGE_EVENT_NAME, JSON.stringify(change('TASK')));

    expect(received).toEqual([change('TASK')]);
  });

  it('reconnects with exponential backoff up to the maximum', () => {
    create();
    const first = latest();
    first.fail();
    expect(first.closed).toBe(true);

    // 1 s, 2 s, 4 s, 8 s, then capped at 8 s.
    for (const delay of [1000, 2000, 4000, 8000, 8000]) {
      const before = sources.length;
      vi.advanceTimersByTime(delay - 1);
      expect(sources).toHaveLength(before);
      vi.advanceTimersByTime(1);
      expect(sources).toHaveLength(before + 1);
      latest().fail();
    }
  });

  it('starts the backoff over after a successful reconnect', () => {
    create();
    latest().fail();
    vi.advanceTimersByTime(1000);
    latest().fail();
    vi.advanceTimersByTime(2000);
    latest().open();

    latest().fail();
    const before = sources.length;
    vi.advanceTimersByTime(1000);
    expect(sources).toHaveLength(before + 1);
  });

  it('emits resync after a reconnect, not on the first connect', () => {
    const service = create();
    const resync = vi.fn();
    service.resync$.subscribe(resync);

    latest().open();
    expect(resync).not.toHaveBeenCalled();

    latest().fail();
    expect(service.connected()).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(resync).not.toHaveBeenCalled();
    latest().open();
    expect(resync).toHaveBeenCalledTimes(1);
  });

  it('does not resync on the first connect of a new login', () => {
    const service = create();
    const resync = vi.fn();
    service.resync$.subscribe(resync);
    latest().open();

    loggedIn.set(false);
    TestBed.tick();
    loggedIn.set(true);
    TestBed.tick();
    latest().open();

    expect(resync).not.toHaveBeenCalled();
  });

  it('stops reconnecting on logout', () => {
    create();
    latest().fail();
    loggedIn.set(false);
    TestBed.tick();

    vi.advanceTimersByTime(60_000);
    expect(sources).toHaveLength(1);
  });

  it('closes the stream when the app is destroyed', () => {
    create();
    TestBed.resetTestingModule();
    expect(latest().closed).toBe(true);
  });
});
