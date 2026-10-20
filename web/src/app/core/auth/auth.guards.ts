import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { LOGIN_PATH, RETURN_URL_PARAM } from '../http';
import { AuthService } from './auth.service';

export const SETUP_PATH = '/setup';

/**
 * The URL to go to after login: only an absolute path inside the app (no `//host` or scheme),
 * so a crafted login link cannot send the user to another site. Falls back to the dashboard.
 */
export function safeReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return '/';
  }
  if (value === LOGIN_PATH || value.startsWith(`${LOGIN_PATH}?`) || value === SETUP_PATH) {
    return '/';
  }
  return value;
}

/** Shell routes: setup first while no user exists, then a session; else login with the return URL. */
export const authGuard: CanActivateFn = async (_route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (await auth.setupRequired()) return router.parseUrl(SETUP_PATH);
  if (await auth.checkSession()) return true;
  return router.createUrlTree([LOGIN_PATH], { queryParams: { [RETURN_URL_PARAM]: state.url } });
};

/** `/setup`: only reachable while setup is needed. */
export const setupGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return (await auth.setupRequired()) || router.parseUrl(LOGIN_PATH);
};

/** `/login`: setup first while no user exists; a logged-in user goes straight to the return URL. */
export const loginGuard: CanActivateFn = async (route): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (await auth.setupRequired()) return router.parseUrl(SETUP_PATH);
  // Always ask the server: a 401 elsewhere (expired session) leads here while the cache says yes.
  if (await auth.checkSession(true)) {
    return router.parseUrl(safeReturnUrl(route.queryParamMap.get(RETURN_URL_PARAM)));
  }
  return true;
};
