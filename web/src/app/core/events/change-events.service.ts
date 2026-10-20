import { HttpContext } from '@angular/common/http';
import { DestroyRef, effect, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { filter, Observable, Subject } from 'rxjs';
import { Api, ChangeEvent, getCurrentUser } from '../../api/generated';
import { AuthService } from '../auth';
import { ApiError, SILENT_ERRORS, SKIP_LOGIN_REDIRECT } from '../http';

/** The server's SSE stream of change notifications (SPEC.md §2). */
export const EVENTS_PATH = '/api/v1/events';
/** The SSE `event:` name of a {@link ChangeEvent} (server: `SseEventHub.EVENT_NAME`). */
export const CHANGE_EVENT_NAME = 'change';

/** Reconnect delays: doubles from the first to the max, back to the first after a successful connect. */
export interface ReconnectBackoff {
  initialMs: number;
  maxMs: number;
}

export const RECONNECT_BACKOFF = new InjectionToken<ReconnectBackoff>('RECONNECT_BACKOFF', {
  providedIn: 'root',
  factory: () => ({ initialMs: 1000, maxMs: 30_000 }),
});

/** Opens an EventSource; replaced in tests (jsdom has none). */
export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSource>(
  'EVENT_SOURCE_FACTORY',
  {
    providedIn: 'root',
    factory: () => (url: string) => new EventSource(url, { withCredentials: true }),
  },
);

/**
 * The one SSE connection of the app to `GET /api/v1/events`, shared by all feature stores.
 *
 * - Connected while logged in ({@link AuthService.loggedIn}), closed on logout.
 * - Reconnects itself with exponential backoff instead of EventSource's fixed retry, which also
 *   gives up for good on a non-200 answer. Each failure also re-checks the session, so an expired
 *   one logs out instead of retrying forever.
 * - The server does not replay missed events, so after every reconnect (not the first connect)
 *   {@link resync$} emits: stores refetch what they show.
 *
 * Stores use {@link events$} with their item type, e.g. `events$('TASK')`; other named events
 * (e.g. `reminder`) come from {@link messages$}.
 */
@Injectable({ providedIn: 'root' })
export class ChangeEventsService {
  private readonly auth = inject(AuthService);
  private readonly api = inject(Api);
  private readonly openEventSource = inject(EVENT_SOURCE_FACTORY);
  private readonly backoff = inject(RECONNECT_BACKOFF);

  private readonly changes = new Subject<ChangeEvent>();
  private readonly resyncs = new Subject<void>();
  /** Other named SSE events (e.g. `reminder`), by `event:` name; listened to on every connection. */
  private readonly named = new Map<string, Subject<unknown>>();

  private source: EventSource | undefined;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  /** Failed attempts since the last successful connect; sets the next delay. */
  private failures = 0;
  /** Whether a connection was open before in this login: the next open is a reconnect. */
  private wasOpen = false;

  /** Whether the stream is open right now. */
  readonly connected = signal(false);
  /** Emits after a reconnect: events may have been missed, refetch. */
  readonly resync$: Observable<void> = this.resyncs.asObservable();

  constructor() {
    effect(() => (this.auth.loggedIn() ? this.connect() : this.disconnect()));
    inject(DestroyRef).onDestroy(() => {
      this.disconnect();
      this.changes.complete();
      this.resyncs.complete();
      this.named.forEach((subject) => subject.complete());
    });
  }

  /** Change events of one item type (an `ItemType` name such as `TASK`, or e.g. `REMINDER`). */
  events$(type: string): Observable<ChangeEvent> {
    return this.changes.pipe(filter((event) => event.type === type));
  }

  /**
   * The JSON data of the SSE events named `name` other than {@link CHANGE_EVENT_NAME}, e.g. the
   * `reminder` event with a `ReminderResponse`. Unparsable data is dropped.
   */
  messages$<T>(name: string): Observable<T> {
    let subject = this.named.get(name);
    if (!subject) {
      subject = new Subject<unknown>();
      this.named.set(name, subject);
      this.source?.addEventListener(name, (message) => this.forward(name, message as MessageEvent));
    }
    return subject.asObservable() as Observable<T>;
  }

  private connect(): void {
    if (this.source || this.reconnectTimer !== undefined) return;
    const source = this.openEventSource(EVENTS_PATH);
    this.source = source;
    source.onopen = () => {
      this.failures = 0;
      this.connected.set(true);
      if (this.wasOpen) this.resyncs.next();
      this.wasOpen = true;
    };
    source.addEventListener(CHANGE_EVENT_NAME, (message) => this.dispatch(message as MessageEvent));
    for (const name of this.named.keys()) {
      source.addEventListener(name, (message) => this.forward(name, message as MessageEvent));
    }
    source.onerror = () => {
      // Our own backoff replaces EventSource's automatic retry.
      this.close();
      this.scheduleReconnect();
      this.checkSession();
    };
  }

  private dispatch(message: MessageEvent): void {
    let event: ChangeEvent;
    try {
      event = JSON.parse(message.data as string) as ChangeEvent;
    } catch {
      return;
    }
    if (event && typeof event.type === 'string') this.changes.next(event);
  }

  private forward(name: string, message: MessageEvent): void {
    let data: unknown;
    try {
      data = JSON.parse(message.data as string);
    } catch {
      return;
    }
    this.named.get(name)?.next(data);
  }

  /**
   * EventSource can't see status codes, so a failure may be an expired or revoked session (401).
   * A silent probe (no toast, no login redirect) asks the server; only on a 401 does AuthService
   * re-check and end the login, and the effect then stops the stream and the pending retry.
   * Any other failure (network down, 502/503 during a restart) is ignored: the backoff keeps
   * retrying without a toast per attempt.
   */
  private checkSession(): void {
    const context = new HttpContext().set(SKIP_LOGIN_REDIRECT, true).set(SILENT_ERRORS, true);
    this.api.invoke(getCurrentUser, undefined, context).then(
      () => undefined,
      (error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          this.auth.checkSession(true).catch(() => undefined);
        }
      },
    );
  }

  private scheduleReconnect(): void {
    const delay = Math.min(this.backoff.initialMs * 2 ** this.failures, this.backoff.maxMs);
    this.failures++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  private close(): void {
    if (this.source) {
      this.source.onopen = null;
      this.source.onerror = null;
      this.source.close();
      this.source = undefined;
    }
    this.connected.set(false);
  }

  private disconnect(): void {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.close();
    this.failures = 0;
    this.wasOpen = false;
  }
}
