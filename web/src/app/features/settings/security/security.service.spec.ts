import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { SecurityService } from './security.service';

describe('SecurityService', () => {
  let service: SecurityService;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(SecurityService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the devices without toasting errors', async () => {
    const devices = service.devices();
    const req = http.expectOne({ method: 'GET', url: '/api/v1/auth/devices' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush([
      {
        id: 'd1',
        name: 'Pixel 9',
        createdAt: '2026-10-01T08:00:00Z',
        lastSeenAt: '2026-10-19T17:30:00Z',
      },
    ]);

    expect((await devices).map((d) => d.name)).toEqual(['Pixel 9']);
  });

  it('revokes a device with DELETE', async () => {
    const done = service.revoke('0192f0c4-0000-7000-8000-000000000001');
    http
      .expectOne({
        method: 'DELETE',
        url: '/api/v1/auth/devices/0192f0c4-0000-7000-8000-000000000001',
      })
      .flush(null, { status: 204, statusText: 'No Content' });

    await expect(done).resolves.toBeUndefined();
  });

  it('regenerates recovery codes with the TOTP code and returns them', async () => {
    const codes = service.regenerateRecoveryCodes('123456');
    const req = http.expectOne({ method: 'POST', url: '/api/v1/auth/recovery-codes' });
    expect(req.request.body).toEqual({ totp: '123456' });
    req.flush({ recoveryCodes: ['abcde-fghjk'] });

    expect(await codes).toEqual(['abcde-fghjk']);
  });

  it('rejects a failed device list or revoke with an ApiError and does not toast it', async () => {
    const devices = service.devices();
    http
      .expectOne('/api/v1/auth/devices')
      .flush(
        { title: 'Server error', status: 500, detail: 'Database down' },
        { status: 500, statusText: 'Error' },
      );
    const listError = await devices.catch((e: unknown) => e);
    expect(listError).toBeInstanceOf(ApiError);
    expect((listError as ApiError).status).toBe(500);

    const revoked = service.revoke('0192f0c4-0000-7000-8000-000000000002');
    const req = http.expectOne('/api/v1/auth/devices/0192f0c4-0000-7000-8000-000000000002');
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(
      { title: 'Not Found', status: 404, detail: 'Device not found' },
      { status: 404, statusText: 'Not Found' },
    );
    const revokeError = await revoked.catch((e: unknown) => e);
    expect(revokeError).toBeInstanceOf(ApiError);
    expect((revokeError as ApiError).status).toBe(404);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('rejects with an ApiError and does not toast it', async () => {
    const codes = service.regenerateRecoveryCodes('000000');
    http
      .expectOne('/api/v1/auth/recovery-codes')
      .flush({ title: 'Bad Request', status: 400 }, { status: 400, statusText: 'Bad Request' });

    const error = await codes.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
