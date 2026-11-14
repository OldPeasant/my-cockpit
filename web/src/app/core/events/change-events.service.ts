import { HttpContext } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
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

/** The SSE `event:` name of the server's heartbeat (server: `SseEventHub.PING_EVENT_NAME`). */
export const PING_EVENT_NAME = 'ping';

/**
 * When the stream counts as dead although EventSource reports no error (laptop sleep, network
 * change, a half-open TCP connection a middlebox dropped).
 */
export interface StreamWatchdog {
  /** The server's heartbeat interval (25 s): a stream silent for longer is suspect on wake-up. */
  heartbeatMs: number;
  /** Silence (no event, no ping) after which the stream is reconnected: ~2.5 heartbeats. */
  silenceMs: number;
  /** How often the watchdog looks; a check that runs a heartbeat later than planned means a wake-up. */
  checkMs: number;
}

export const STREAM_WATCHDOG = new InjectionToken<StreamWatchdog>('STREAM_WATCHDOG', {
  providedIn: 'root',
  factory: () => ({ heartbeatMs: 25_000, silenceMs: 65_000, checkMs: 5_000 }),
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
 * - A watchdog reconnects a stream that went silent without an error: no event and no `ping`
 *   heartbeat for {@link StreamWatchdog.silenceMs}. When the tab becomes visible again, the browser
 *   goes back online or a timer fires much later than planned (the device slept), a stream silent
 *   for more than one heartbeat is reconnected at once; a healthy one is left alone.
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
  private readonly watchdog = inject(STREAM_WATCHDOG);
  private readonly document = inject(DOCUMENT);

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
  /** `Date.now()` of the last sign of life of the stream (opened, any event, ping). */
  private lastActivity = 0;
  /** `Date.now()` of the last watchdog check, to notice a wake-up. */
  private lastCheck = 0;
  private watchdogTimer: ReturnType<typeof setInterval> | undefined;
  private readonly onWake = (): void => this.wake();

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
    this.startWatchdog();
    const source = this.openEventSource(EVENTS_PATH);
    this.source = source;
    this.lastActivity = Date.now();
    source.onopen = () => {
      this.lastActivity = Date.now();
      this.failures = 0;
      this.connected.set(true);
      if (this.wasOpen) this.resyncs.next();
      this.wasOpen = true;
    };
    source.addEventListener(CHANGE_EVENT_NAME, (message) => this.dispatch(message as MessageEvent));
    source.addEventListener(PING_EVENT_NAME, () => (this.lastActivity = Date.now()));
    for (const name of this.named.keys()) {
      source.addEventListener(name, (message) => this.forward(name, message as MessageEvent));
    }
    source.onerror = () => this.fail();
  }

  /** A failed or dead stream: closed, retried after the backoff, and the session re-checked. */
  private fail(): void {
    // Our own backoff replaces EventSource's automatic retry.
    this.close();
    this.scheduleReconnect();
    this.checkSession();
  }

  /** Watches the stream while logged in: periodic silence check plus wake-up signals of the page. */
  private startWatchdog(): void {
    if (this.watchdogTimer !== undefined) return;
    this.lastCheck = Date.now();
    this.watchdogTimer = setInterval(() => this.check(), this.watchdog.checkMs);
    this.document.addEventListener('visibilitychange', this.onWake);
    this.document.defaultView?.addEventListener('online', this.onWake);
  }

  private stopWatchdog(): void {
    clearInterval(this.watchdogTimer);
    this.watchdogTimer = undefined;
    this.document.removeEventListener('visibilitychange', this.onWake);
    this.document.defaultView?.removeEventListener('online', this.onWake);
  }

  private check(): void {
    const now = Date.now();
    const late = now - this.lastCheck - this.watchdog.checkMs;
    this.lastCheck = now;
    if (!this.source) return; // A reconnect is already scheduled.
    // Hidden tabs get their timers throttled, so a late check there is no sign of a sleep.
    if (late > this.watchdog.heartbeatMs && !this.hidden()) this.wake();
    else if (now - this.lastActivity > this.watchdog.silenceMs) this.fail();
  }

  /**
   * The page is back (visible, online, woke up): a stream silent for more than a heartbeat may be
   * dead, so it is replaced right away instead of after the watchdog's silence or the backoff.
   */
  private wake(): void {
    if (this.hidden()) return;
    if (this.source && Date.now() - this.lastActivity <= this.watchdog.heartbeatMs) return;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.close();
    this.failures = 0;
    this.connect();
  }

  private hidden(): boolean {
    return this.document.visibilityState === 'hidden';
  }

  private dispatch(message: MessageEvent): void {
    this.lastActivity = Date.now();
    let event: ChangeEvent;
    try {
      event = JSON.parse(message.data as string) as ChangeEvent;
    } catch {
      return;
    }
    if (event && typeof event.type === 'string') this.changes.next(event);
  }

  private forward(name: string, message: MessageEvent): void {
    this.lastActivity = Date.now();
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
    this.stopWatchdog();
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.close();
    this.failures = 0;
    this.wasOpen = false;
  }
}
