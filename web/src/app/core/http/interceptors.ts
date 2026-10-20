import {
  HttpContextToken,
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpStatusCode,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { ApiError } from './api-error';
import { NotificationService } from './notification.service';

/** The login route and its query parameter carrying the URL to return to after login. */
export const LOGIN_PATH = '/login';
export const RETURN_URL_PARAM = 'returnUrl';

/**
 * Set to `true` on requests whose errors the caller shows itself (e.g. form validation or a 412
 * conflict dialog): the error is still mapped to an {@link ApiError}, but no toast is shown.
 */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

/** Set to `true` on requests whose 401 is expected (login, session probe): no redirect to login. */
export const SKIP_LOGIN_REDIRECT = new HttpContextToken<boolean>(() => false);

/** Routes that are reachable without a session, so a 401 there never redirects. */
const PUBLIC_PATHS = [LOGIN_PATH, '/setup'];

/** A 401 (session missing or expired) navigates to the login page, keeping the current URL. */
export const loginRedirectInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === HttpStatusCode.Unauthorized &&
        !req.context.get(SKIP_LOGIN_REDIRECT)
      ) {
        const returnUrl = currentUrl(router);
        if (!PUBLIC_PATHS.some((path) => isPath(returnUrl, path))) {
          void router.navigate([LOGIN_PATH], { queryParams: { [RETURN_URL_PARAM]: returnUrl } });
        }
      }
      return throwError(() => error);
    }),
  );
};

/**
 * Maps every HTTP error to a typed {@link ApiError} (from Problem Details when present) and shows
 * it as a toast, except for 401 (handled by {@link loginRedirectInterceptor}) and
 * {@link SILENT_ERRORS} requests.
 */
export const problemInterceptor: HttpInterceptorFn = (req, next) => {
  const notifications = inject(NotificationService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) return throwError(() => error);
      const apiError = ApiError.from(error);
      if (apiError.status !== HttpStatusCode.Unauthorized && !req.context.get(SILENT_ERRORS)) {
        notifications.error(apiError.userMessage);
      }
      return throwError(() => apiError);
    }),
  );
};

/** The URL being navigated to (when the request runs during a navigation), else the current one. */
function currentUrl(router: Router): string {
  const navigation = router.currentNavigation();
  const target = navigation?.finalUrl ?? navigation?.extractedUrl;
  return target ? router.serializeUrl(target) : router.url;
}

function isPath(url: string, path: string): boolean {
  return url === path || url.startsWith(`${path}?`) || url.startsWith(`${path}/`);
}
