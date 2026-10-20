import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ChangeEvent } from '../../api/generated';
import { AuthService } from '../auth';
import { NotificationService, provideHttp } from '../http';
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

/** The real HTTP stack (interceptors included) against a testing backend, toasts recorded. */
function httpProviders(
  notifications: Pick<NotificationService, 'error'>,
): (Provider | EnvironmentProviders)[] {
  return [
    provideHttp(),
    provideHttpClientTesting(),
    provideRouter([]),
    { provide: NotificationService, useValue: notifications },
  ];
}

const ME_PATH = '/api/v1/auth/me';

function change(type: string, id = '0190a5c8-0000-7000-8000-000000000001'): ChangeEvent {
  return { type, id, op: 'UPDATED', at: '2026-10-20T10:00:00Z' };
}

describe('ChangeEventsService', () => {
  let sources: FakeEventSource[];
  let loggedIn: ReturnType<typeof signal<boolean>>;
  /** `AuthService.checkSession`: by default the session is still valid. */
  let checkSession: ReturnType<typeof vi.fn<(refresh?: boolean) => Promise<boolean>>>;
  let notifications: { error: ReturnType<typeof vi.fn<(message: string) => void>> };
  let backend: HttpTestingController;

  const latest = () => sources[sources.length - 1];

  function create(initiallyLoggedIn = true): ChangeEventsService {
    sources = [];
    loggedIn = signal(initiallyLoggedIn);
    checkSession = vi.fn(async () => loggedIn());
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        ...httpProviders(notifications),
        {
          provide: AuthService,
          useValue: {
            loggedIn: loggedIn.asReadonly(),
            checkSession: (refresh?: boolean) => checkSession(refresh),
          },
        },
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
    backend = TestBed.inject(HttpTestingController);
    const service = TestBed.inject(ChangeEventsService);
    TestBed.tick();
    return service;
  }

  /** Fails the current stream and answers the session probe it sends. */
  async function failWithProbe(status: number | 'network'): Promise<void> {
    latest().fail();
    await vi.advanceTimersByTimeAsync(0);
    const probe = backend.expectOne(ME_PATH);
    expect(probe.request.method).toBe('GET');
    if (status === 'network') probe.error(new ProgressEvent('error'), { status: 0 });
    else if (status === 200) probe.flush({ username: 'demo' });
    else probe.flush({ status, title: 'Error' }, { status, statusText: 'Error' });
    await vi.advanceTimersByTimeAsync(0);
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

  it('completes the event and resync streams when the app is destroyed', () => {
    const service = create();
    const eventsDone = vi.fn();
    const resyncDone = vi.fn();
    service.events$('TASK').subscribe({ complete: eventsDone });
    service.resync$.subscribe({ complete: resyncDone });

    TestBed.resetTestingModule();

    expect(eventsDone).toHaveBeenCalledTimes(1);
    expect(resyncDone).toHaveBeenCalledTimes(1);
  });

  it('ignores events without a string type', () => {
    const service = create();
    const received: ChangeEvent[] = [];
    service.events$('TASK').subscribe((event) => received.push(event));
    service.events$('1').subscribe((event) => received.push(event));
    latest().open();

    latest().emit(CHANGE_EVENT_NAME, '{}');
    latest().emit(
      CHANGE_EVENT_NAME,
      '{"type":1,"id":"x","op":"UPDATED","at":"2026-10-20T10:00:00Z"}',
    );
    latest().emit(CHANGE_EVENT_NAME, '"TASK"');
    latest().emit(CHANGE_EVENT_NAME, '');

    expect(received).toEqual([]);
  });

  it('ignores SSE messages with another event name', () => {
    const service = create();
    const received: ChangeEvent[] = [];
    service.events$('TASK').subscribe((event) => received.push(event));
    latest().open();

    latest().emit('message', JSON.stringify(change('TASK')));
    latest().emit('heartbeat', JSON.stringify(change('TASK')));

    expect(received).toEqual([]);
  });

  it('keeps dispatching to existing subscribers after a reconnect', () => {
    const service = create();
    const received: ChangeEvent[] = [];
    service.events$('TASK').subscribe((event) => received.push(event));
    latest().open();
    latest().fail();
    vi.advanceTimersByTime(1000);
    latest().open();

    latest().emit(CHANGE_EVENT_NAME, JSON.stringify(change('TASK')));

    expect(sources).toHaveLength(2);
    expect(received).toEqual([change('TASK')]);
  });

  it('emits resync once per reconnect', () => {
    const service = create();
    const resync = vi.fn();
    service.resync$.subscribe(resync);
    latest().open();

    for (let i = 1; i <= 3; i++) {
      latest().fail(); // e.g. the server ends the stream after 15 min
      vi.advanceTimersByTime(1000);
      latest().open();
      expect(resync).toHaveBeenCalledTimes(i);
    }
  });

  it('does not resync when the first connect of a login only succeeds after retries', () => {
    const service = create();
    const resync = vi.fn();
    service.resync$.subscribe(resync);

    latest().fail(); // e.g. 401 or server down before the stream ever opened
    vi.advanceTimersByTime(1000);
    latest().open();
    expect(service.connected()).toBe(true);
    expect(resync).not.toHaveBeenCalled();

    latest().fail();
    vi.advanceTimersByTime(1000);
    latest().open();
    expect(resync).toHaveBeenCalledTimes(1);
  });

  it('connects at once on a new login during a pending reconnect, with a fresh backoff', () => {
    create();
    latest().fail();
    vi.advanceTimersByTime(1000);
    latest().fail(); // next retry would be in 2 s

    loggedIn.set(false);
    TestBed.tick();
    loggedIn.set(true);
    TestBed.tick();
    expect(sources).toHaveLength(3);

    // The old pending retry does not open a second stream.
    vi.advanceTimersByTime(60_000);
    expect(sources).toHaveLength(3);

    latest().fail();
    vi.advanceTimersByTime(999);
    expect(sources).toHaveLength(3);
    vi.advanceTimersByTime(1);
    expect(sources).toHaveLength(4);
  });

  it('detaches its handlers from a stream it closed', () => {
    create();
    const first = latest();
    first.open();
    loggedIn.set(false);
    TestBed.tick();

    expect(first.closed).toBe(true);
    expect(first.onopen).toBeNull();
    expect(first.onerror).toBeNull();
  });

  it('probes the session after a failed connection attempt, and reconnects while it is valid', async () => {
    create();
    await failWithProbe(200);

    expect(checkSession).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1000);
    expect(sources).toHaveLength(2);
  });

  it('logs out and stops reconnecting when the probe answers 401', async () => {
    create();
    checkSession.mockImplementation(async () => {
      loggedIn.set(false); // what AuthService does on a 401
      return false;
    });

    await failWithProbe(401);
    TestBed.tick();

    expect(checkSession).toHaveBeenCalledExactlyOnceWith(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sources).toHaveLength(1);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('keeps the backoff going without toasts while the server is unavailable or unreachable', async () => {
    create();

    for (const [status, delay] of [
      [503, 1000],
      ['network', 2000],
      [502, 4000],
    ] as const) {
      const before = sources.length;
      await failWithProbe(status);
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(sources).toHaveLength(before);
      await vi.advanceTimersByTimeAsync(1);
      expect(sources).toHaveLength(before + 1);
    }

    expect(notifications.error).not.toHaveBeenCalled();
    expect(checkSession).not.toHaveBeenCalled();
  });

  it('keeps reconnecting when the session re-check after a 401 fails', async () => {
    create();
    checkSession.mockRejectedValue(new Error('network down'));

    await failWithProbe(401);
    await vi.advanceTimersByTimeAsync(1000);

    expect(checkSession).toHaveBeenCalledTimes(1);
    expect(sources).toHaveLength(2);
  });
});

describe('ChangeEventsService defaults', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('backs off from 1 s up to 30 s by default', () => {
    expect(TestBed.inject(RECONNECT_BACKOFF)).toEqual({ initialMs: 1000, maxMs: 30_000 });
  });

  it('opens a credentialed EventSource to the events endpoint by default', () => {
    const opened: { url: string; init?: EventSourceInit }[] = [];
    vi.stubGlobal(
      'EventSource',
      class {
        constructor(url: string, init?: EventSourceInit) {
          opened.push({ url, init });
        }
      },
    );
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { loggedIn: signal(true).asReadonly() } }],
    });

    TestBed.inject(EVENT_SOURCE_FACTORY)(EVENTS_PATH);

    expect(opened).toEqual([{ url: EVENTS_PATH, init: { withCredentials: true } }]);
  });

  it('caps the default backoff at 30 s', () => {
    vi.useFakeTimers();
    const sources: FakeEventSource[] = [];
    TestBed.configureTestingModule({
      providers: [
        ...httpProviders({ error: vi.fn() }),
        {
          provide: AuthService,
          useValue: { loggedIn: signal(true).asReadonly(), checkSession: async () => true },
        },
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            const source = new FakeEventSource(url);
            sources.push(source);
            return source as unknown as EventSource;
          },
        },
      ],
    });
    TestBed.inject(ChangeEventsService);
    TestBed.tick();

    // 1, 2, 4, 8, 16 s, then 30 s (not 32 s).
    for (const delay of [1000, 2000, 4000, 8000, 16_000, 30_000, 30_000]) {
      sources[sources.length - 1].fail();
      const before = sources.length;
      vi.advanceTimersByTime(delay - 1);
      expect(sources).toHaveLength(before);
      vi.advanceTimersByTime(1);
      expect(sources).toHaveLength(before + 1);
    }
  });
});
