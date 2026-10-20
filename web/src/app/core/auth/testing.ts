import { Provider, signal } from '@angular/core';
import { AuthService } from './auth.service';

/** Test double: setup done and a session for `username`, so guarded shell routes open. */
export function provideFakeSession(username = 'demo'): Provider {
  const user = signal({ username });
  const fake: Partial<AuthService> = {
    user: user.asReadonly(),
    loggedIn: signal(true).asReadonly(),
    setupRequired: () => Promise.resolve(false),
    checkSession: () => Promise.resolve(true),
    logout: () => Promise.resolve(),
  };
  return { provide: AuthService, useValue: fake };
}
