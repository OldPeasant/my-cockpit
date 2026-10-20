import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { provideApiConfiguration } from '../../api/generated';
import { loginRedirectInterceptor, problemInterceptor } from './interceptors';

/**
 * HttpClient for the generated API client: same-origin calls (`/api/...`, proxied by `npm start`),
 * CSRF double-submit cookie (SPEC.md §4.1), 401 → login, Problem Details → {@link ApiError} + toast.
 */
export function provideHttp(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideHttpClient(
      // Errors pass the inner interceptor first: the login redirect sees the raw 401 response,
      // then the problem interceptor maps it to an ApiError.
      withInterceptors([problemInterceptor, loginRedirectInterceptor]),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    // The spec's paths already start with /api; the generated default root URL '/' would yield '//api'.
    provideApiConfiguration(''),
  ]);
}
