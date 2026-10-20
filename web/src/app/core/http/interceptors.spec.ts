import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom, Observable, throwError } from 'rxjs';
import {
  Api,
  ApiConfiguration,
  RequestBuilder,
  type StrictHttpResponse,
} from '../../api/generated';
import { ApiError } from './api-error';
import {
  loginRedirectInterceptor,
  problemInterceptor,
  SILENT_ERRORS,
  SKIP_LOGIN_REDIRECT,
} from './interceptors';
import { NotificationService } from './notification.service';
import { provideHttp } from './provide-http';

@Component({ template: '' })
class Blank {}

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let router: Router;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: Blank },
          { path: 'setup', component: Blank },
          { path: 'gtd/next', component: Blank },
          { path: 'old-inbox', redirectTo: 'inbox' },
          {
            path: 'inbox',
            component: Blank,
            resolve: { items: () => inject(HttpClient).get('/api/inbox') },
          },
        ]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    await router.navigateByUrl('/gtd/next?context=home');
  });

  afterEach(() => backend.verify());

  function fail(
    status: number,
    body: string | object | null,
    context?: HttpContext,
    headers: Record<string, string> = { 'Content-Type': 'application/problem+json' },
  ): Promise<unknown> {
    const result = firstValueFrom(http.get('/api/tasks', { context })).catch((e: unknown) => e);
    backend.expectOne('/api/tasks').flush(body, { status, statusText: 'Error', headers });
    return result;
  }

  describe('401', () => {
    it('navigates to /login with the current URL as returnUrl', async () => {
      const error = await fail(401, { status: 401, title: 'Unauthorized' });
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Fgtd%2Fnext%3Fcontext%3Dhome');
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(401);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('does not redirect when already on the login page', async () => {
      await router.navigateByUrl('/login?returnUrl=%2Fgtd');
      await fail(401, { status: 401, title: 'Unauthorized' });
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Fgtd');
    });

    it('does not redirect from the setup page', async () => {
      await router.navigateByUrl('/setup');
      await fail(401, { status: 401, title: 'Unauthorized' });
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/setup');
    });

    it('does not redirect for SKIP_LOGIN_REDIRECT requests', async () => {
      await fail(401, null, new HttpContext().set(SKIP_LOGIN_REDIRECT, true));
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/gtd/next?context=home');
    });

    it('redirects on a 401 without a problem body', async () => {
      const error = (await fail(401, null, undefined, {})) as ApiError;
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Fgtd%2Fnext%3Fcontext%3Dhome');
      expect(error.status).toBe(401);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('still redirects for SILENT_ERRORS requests', async () => {
      await fail(401, null, new HttpContext().set(SILENT_ERRORS, true));
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Fgtd%2Fnext%3Fcontext%3Dhome');
    });

    it('does not redirect on 403 but shows the problem', async () => {
      await fail(403, { status: 403, title: 'Forbidden', detail: 'Not allowed.' });
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/gtd/next?context=home');
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith('Not allowed.');
    });

    it('keeps the original returnUrl when several requests fail with 401 at once', async () => {
      const first = firstValueFrom(http.get('/api/tasks')).catch((e: unknown) => e);
      const second = firstValueFrom(http.get('/api/projects')).catch((e: unknown) => e);
      const unauthorized = { status: 401, statusText: 'Unauthorized' };
      backend.expectOne('/api/tasks').flush(null, unauthorized);
      backend.expectOne('/api/projects').flush(null, unauthorized);
      await Promise.all([first, second]);
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Fgtd%2Fnext%3Fcontext%3Dhome');
    });

    it('uses the URL being navigated to when a 401 arrives during a navigation', async () => {
      const navigation = router.navigateByUrl('/inbox?filter=today').catch((e: unknown) => e);
      await new Promise((resolve) => setTimeout(resolve));
      backend.expectOne('/api/inbox').flush(null, { status: 401, statusText: 'Unauthorized' });
      await navigation;
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Finbox%3Ffilter%3Dtoday');
    });

    it('uses the redirect target as returnUrl when the navigation was redirected', async () => {
      const navigation = router.navigateByUrl('/old-inbox').catch((e: unknown) => e);
      await new Promise((resolve) => setTimeout(resolve));
      backend.expectOne('/api/inbox').flush(null, { status: 401, statusText: 'Unauthorized' });
      await navigation;
      await new Promise((resolve) => setTimeout(resolve));

      expect(router.url).toBe('/login?returnUrl=%2Finbox');
    });
  });

  describe('non-HTTP errors', () => {
    const request = new HttpRequest('GET', '/api/tasks');
    const failing: HttpHandlerFn = () => throwError(() => new TypeError('fictitious failure'));

    it('passes them through the problem interceptor unchanged and without a toast', async () => {
      const result = TestBed.runInInjectionContext(() => problemInterceptor(request, failing));
      const error = await firstValueFrom(result).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(TypeError);
      expect(error).not.toBeInstanceOf(ApiError);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('passes them through the login redirect interceptor without navigating', async () => {
      const result = TestBed.runInInjectionContext(() =>
        loginRedirectInterceptor(request, failing),
      );
      const error = await firstValueFrom(result).catch((e: unknown) => e);
      await new Promise((resolve) => setTimeout(resolve));

      expect(error).toBeInstanceOf(TypeError);
      expect(router.url).toBe('/gtd/next?context=home');
    });

    it('lets the login redirect interceptor rethrow the raw HTTP error', async () => {
      const raw = new HttpErrorResponse({ status: 500, url: '/api/tasks' });
      const result = TestBed.runInInjectionContext(() =>
        loginRedirectInterceptor(request, () => throwError(() => raw)),
      );

      expect(await firstValueFrom(result).catch((e: unknown) => e)).toBe(raw);
    });
  });

  describe('generated API client', () => {
    it('is configured with an empty root URL so calls go to /api on the same origin', async () => {
      expect(TestBed.inject(ApiConfiguration).rootUrl).toBe('');

      const listTasks = (
        client: HttpClient,
        rootUrl: string,
      ): Observable<StrictHttpResponse<unknown>> =>
        client.request(new RequestBuilder(rootUrl, '/api/tasks', 'get').build()) as Observable<
          StrictHttpResponse<unknown>
        >;
      const result = TestBed.inject(Api).invoke(listTasks);
      backend.expectOne('/api/tasks').flush({ items: [] });

      expect(await result).toEqual({ items: [] });
    });
  });

  describe('XSRF', () => {
    afterEach(() => {
      document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    });

    it('sends the XSRF-TOKEN cookie as X-XSRF-TOKEN header on mutating requests', () => {
      document.cookie = 'XSRF-TOKEN=fictitious-token; path=/';
      http.post('/api/tasks', {}).subscribe();
      http.get('/api/tasks').subscribe();

      const post = backend.expectOne({ method: 'POST', url: '/api/tasks' });
      const get = backend.expectOne({ method: 'GET', url: '/api/tasks' });
      expect(post.request.headers.get('X-XSRF-TOKEN')).toBe('fictitious-token');
      expect(get.request.headers.has('X-XSRF-TOKEN')).toBe(false);
      post.flush({});
      get.flush({});
    });
  });

  describe('Problem Details', () => {
    it('maps a validation problem to a typed ApiError and shows its detail', async () => {
      const error = await fail(400, {
        type: 'about:blank',
        title: 'Bad Request',
        status: 400,
        detail: 'Validation failed.',
        instance: '/api/tasks',
        errors: [{ field: 'title', message: 'must not be blank' }],
      });

      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.status).toBe(400);
      expect(apiError.title).toBe('Bad Request');
      expect(apiError.detail).toBe('Validation failed.');
      expect(apiError.type).toBe('about:blank');
      expect(apiError.instance).toBe('/api/tasks');
      expect(apiError.errors).toEqual([{ field: 'title', message: 'must not be blank' }]);
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith('Validation failed.');
    });

    it('falls back to the title when the problem has no detail', async () => {
      const error = (await fail(409, { status: 409, title: 'Conflict' })) as ApiError;

      expect(error.userMessage).toBe('Conflict');
      expect(error.errors).toEqual([]);
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith('Conflict');
    });

    it('parses a problem delivered as a text body', async () => {
      const error = (await fail(
        412,
        JSON.stringify({ status: 412, title: 'Precondition Failed', detail: 'Stale version.' }),
      )) as ApiError;

      expect(error.status).toBe(412);
      expect(error.detail).toBe('Stale version.');
    });

    it('does not toast SILENT_ERRORS requests but still maps the error', async () => {
      const error = await fail(
        412,
        { status: 412, title: 'Precondition Failed' },
        new HttpContext().set(SILENT_ERRORS, true),
      );

      expect(error).toBeInstanceOf(ApiError);
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('maps a non-problem server error to a generic ApiError', async () => {
      const error = (await fail(502, '<html>Bad Gateway</html>', undefined, {
        'Content-Type': 'text/html',
      })) as ApiError;

      expect(error.status).toBe(502);
      expect(error.title).toBe('The server failed to process the request.');
      expect(error.detail).toBeUndefined();
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        'The server failed to process the request.',
      );
    });

    it('maps a non-problem client error to a generic ApiError with the status', async () => {
      const error = (await fail(404, null)) as ApiError;

      expect(error.title).toBe('The request failed (HTTP 404).');
    });

    it('maps a network error (status 0) to an unreachable ApiError', async () => {
      const result = firstValueFrom(http.get('/api/tasks')).catch((e: unknown) => e);
      backend.expectOne('/api/tasks').error(new ProgressEvent('error'));
      const error = (await result) as ApiError;

      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(0);
      expect(error.userMessage).toBe('The server cannot be reached.');
      expect(notifications.error).toHaveBeenCalledOnce();
    });

    it('passes successful responses through untouched', async () => {
      const result = firstValueFrom(http.get('/api/tasks'));
      backend.expectOne('/api/tasks').flush({ items: [], nextCursor: null });

      expect(await result).toEqual({ items: [], nextCursor: null });
      expect(notifications.error).not.toHaveBeenCalled();
    });
  });
});
