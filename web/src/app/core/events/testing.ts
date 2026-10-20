import { Provider } from '@angular/core';
import { EVENT_SOURCE_FACTORY } from './change-events.service';

/**
 * Test double for the SSE stream: a connection that never opens or sends anything (jsdom has no
 * EventSource). For tests of the whole app, where the shell connects as soon as someone is logged in.
 */
export function provideSilentEventSource(): Provider {
  const silent = (): EventSource =>
    ({
      onopen: null,
      onerror: null,
      addEventListener: () => undefined,
      close: () => undefined,
    }) as unknown as EventSource;
  return { provide: EVENT_SOURCE_FACTORY, useValue: silent };
}
