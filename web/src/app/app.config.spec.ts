import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ApiConfiguration } from './api/generated';
import { appConfig } from './app.config';
import { ApiError, NotificationService } from './core/http';

describe('appConfig', () => {
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('provides the HTTP infrastructure for the generated client', async () => {
    document.cookie = 'XSRF-TOKEN=fictitious-app-token; path=/';
    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);

    const result = firstValueFrom(http.post('/api/tasks', {})).catch((e: unknown) => e);
    const request = backend.expectOne('/api/tasks');
    request.flush(
      { status: 403, title: 'Forbidden', detail: 'Not allowed.' },
      { status: 403, statusText: 'Forbidden' },
    );

    expect(request.request.headers.get('X-XSRF-TOKEN')).toBe('fictitious-app-token');
    expect(await result).toBeInstanceOf(ApiError);
    expect(notifications.error).toHaveBeenCalledExactlyOnceWith('Not allowed.');
    expect(TestBed.inject(ApiConfiguration).rootUrl).toBe('');
  });
});
