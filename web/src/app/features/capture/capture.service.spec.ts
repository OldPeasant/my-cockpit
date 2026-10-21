import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TaskResponse } from '../../api/generated';
import { ApiError, NotificationService, provideHttp, SILENT_ERRORS } from '../../core/http';
import { CaptureService } from './capture.service';

const ID = '0192f0c4-1a2b-7c3d-8e4f-000000000001';

describe('CaptureService', () => {
  let service: CaptureService;
  let http: HttpTestingController;
  let notifications: { info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(CaptureService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('preview posts the text and resolves with the parsed capture', async () => {
    const preview = service.preview('Buy milk !tomorrow @errands');
    const req = http.expectOne({ method: 'POST', url: '/api/v1/capture/preview' });
    expect(req.request.body).toEqual({ text: 'Buy milk !tomorrow @errands' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush({
      title: 'Buy milk',
      list: 'INBOX',
      contexts: [{ id: 'c1', name: 'errands' }],
      dueDate: '2026-10-22',
    });
    await expect(preview).resolves.toEqual({
      title: 'Buy milk',
      list: 'INBOX',
      contexts: [{ id: 'c1', name: 'errands' }],
      dueDate: '2026-10-22',
    });
  });

  it('a failed preview rejects without a toast', async () => {
    const preview = service.preview('Buy milk');
    http
      .expectOne('/api/v1/capture/preview')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await expect(preview).rejects.toBeInstanceOf(ApiError);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('save posts the client id, text and notes and resolves with the created task', async () => {
    const created = { id: ID, title: 'Call plumber', list: 'INBOX' } as unknown as TaskResponse;
    const saved = service.save({ id: ID, text: 'Call plumber', notes: 'Leaking tap' });
    const req = http.expectOne({ method: 'POST', url: '/api/v1/capture' });
    expect(req.request.body).toEqual({ id: ID, text: 'Call plumber', notes: 'Leaking tap' });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(false);
    req.flush(created);
    await expect(saved).resolves.toEqual(created);
  });

  it('a failed save rejects and is shown to the user', async () => {
    const saved = service.save({ id: ID, text: 'Call plumber' });
    http
      .expectOne('/api/v1/capture')
      .flush(
        { title: 'Bad Request', status: 400, detail: 'Text must not be blank' },
        { status: 400, statusText: 'Bad Request' },
      );
    await expect(saved).rejects.toBeInstanceOf(ApiError);
    expect(notifications.error).toHaveBeenCalledTimes(1);
  });
});
