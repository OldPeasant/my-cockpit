import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { LOGIN_PATH, RETURN_URL_PARAM } from '../http';
import { AuthService } from './auth.service';

export const SETUP_PATH = '/setup';
/** Forced TOTP re-enrollment after a restore without secrets (SPEC.md §10.2). */
export const TOTP_ENROLLMENT_PATH = '/enroll-totp';

/**
 * The URL to go to after login: only an absolute path inside the app (no `//host` or scheme),
 * so a crafted login link cannot send the user to another site. Falls back to the dashboard.
 */
export function safeReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return '/';
  }
  const path = value.split(/[?#]/, 1)[0];
  if (path === LOGIN_PATH || path === SETUP_PATH || path === TOTP_ENROLLMENT_PATH) return '/';
  return value;
}

/** The enrollment page, keeping where to go once TOTP is set up again. */
export function totpEnrollmentUrl(router: Router, returnUrl: string): UrlTree {
  const target = safeReturnUrl(returnUrl);
  return router.createUrlTree([TOTP_ENROLLMENT_PATH], {
    queryParams: target === '/' ? {} : { [RETURN_URL_PARAM]: target },
  });
}

/** Shell routes: setup first while no user exists, then a session; else login with the return URL. */
export const authGuard: CanActivateFn = async (_route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toLogin = router.createUrlTree([LOGIN_PATH], {
    queryParams: { [RETURN_URL_PARAM]: state.url },
  });
  try {
    if (await auth.setupRequired()) return router.parseUrl(SETUP_PATH);
    if (await auth.checkSession()) {
      return auth.totpEnrollmentRequired() ? totpEnrollmentUrl(router, state.url) : true;
    }
  } catch {
    // Server unreachable or failing (the interceptor already showed a toast): the login page
    // stays usable and leads back here, instead of a blank page from a rejected navigation.
  }
  return toLogin;
};

/** `/setup`: only reachable while setup is needed. */
export const setupGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  try {
    return (await auth.setupRequired()) || router.parseUrl(LOGIN_PATH);
  } catch {
    // Status unknown (server failing, toast shown): show the form; the server answers 409
    // if setup was already done.
    return true;
  }
};

/** `/login`: setup first while no user exists; a logged-in user goes straight to the return URL. */
export const loginGuard: CanActivateFn = async (route): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  try {
    if (await auth.setupRequired()) return router.parseUrl(SETUP_PATH);
    // Always ask the server: a 401 elsewhere (expired session) leads here while the cache says yes.
    if (await auth.checkSession(true)) {
      const returnUrl = safeReturnUrl(route.queryParamMap.get(RETURN_URL_PARAM));
      return auth.totpEnrollmentRequired()
        ? totpEnrollmentUrl(router, returnUrl)
        : router.parseUrl(returnUrl);
    }
  } catch {
    // Server unreachable or failing (toast shown): show the login form rather than a blank page.
  }
  return true;
};

/** `/enroll-totp`: only for a session that must set up TOTP again; others go where they belong. */
export const totpEnrollmentGuard: CanActivateFn = async (route): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const returnUrl = safeReturnUrl(route.queryParamMap.get(RETURN_URL_PARAM));
  try {
    if (await auth.setupRequired()) return router.parseUrl(SETUP_PATH);
    if (!(await auth.checkSession(true))) {
      return router.createUrlTree([LOGIN_PATH], {
        queryParams: returnUrl === '/' ? {} : { [RETURN_URL_PARAM]: returnUrl },
      });
    }
    return auth.totpEnrollmentRequired() || router.parseUrl(returnUrl);
  } catch {
    // Server failing (toast shown): the login page stays usable and leads back here.
    return router.parseUrl(LOGIN_PATH);
  }
};
