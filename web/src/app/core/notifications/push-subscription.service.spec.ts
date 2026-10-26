import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp } from '../http';
import { BROWSER_PUSH } from './browser-push';
import { decodeBase64Url, PushSubscriptionService } from './push-subscription.service';
import { FakeBrowserPush } from './testing';

/** A fictitious VAPID public key (65 bytes, unpadded base64url). */
const KEY =
  'BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A';
const NEW_KEY =
  'BEA_Pj08Ozo5ODc2NTQzMjEwLy4tLCsqKSgnJiUkIyIhIB8eHRwbGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgE';
const KEY_URL = '/api/v1/push/vapid-public-key';
const SUBSCRIPTIONS_URL = '/api/v1/push/subscriptions';

describe('PushSubscriptionService', () => {
  let service: PushSubscriptionService;
  let backend: HttpTestingController;
  let push: FakeBrowserPush;
  let notifications: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    push = new FakeBrowserPush();
    notifications = { error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BROWSER_PUSH, useValue: push },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    service = TestBed.inject(PushSubscriptionService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  /** The next request to [url], once the service's promise chain has sent it. */
  async function next(method: string, url: string): Promise<TestRequest> {
    for (let i = 0; i < 50; i++) {
      const found = backend.match({ method, url });
      if (found.length) return found[0];
      await new Promise((resolve) => setTimeout(resolve));
    }
    throw new Error(`no ${method} ${url}`);
  }

  it('decodes unpadded base64url', () => {
    expect(Array.from(decodeBase64Url('-_8'))).toEqual([0xfb, 0xff]);
    expect(decodeBase64Url(KEY)).toHaveLength(65);
  });

  it('subscribes with the server key and registers the subscription', async () => {
    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    const put = await next('PUT', SUBSCRIPTIONS_URL);
    expect(put.request.body).toEqual({
      endpoint: 'https://push.example.test/send/1',
      keys: { p256dh: 'BFakeP256dh', auth: 'fakeAuth' },
    });
    put.flush(null, { status: 204, statusText: 'No Content' });

    expect(await result).toBe(true);
    expect(Array.from(push.subscribed[0])).toEqual(Array.from(decodeBase64Url(KEY)));
    expect(service.active()).toBe(true);
  });

  it('keeps an existing subscription made with the current key and registers it again', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const existing = push.current!;

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    const put = await next('PUT', SUBSCRIPTIONS_URL);
    expect(put.request.body.endpoint).toBe(existing.endpoint);
    put.flush(null, { status: 204, statusText: 'No Content' });

    expect(await result).toBe(true);
    expect(push.subscribed).toHaveLength(1);
    expect(existing.unsubscribed).toBe(false);
  });

  it('replaces a subscription made with an older server key', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const old = push.current!;

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: NEW_KEY });
    const removed = await next('DELETE', SUBSCRIPTIONS_URL);
    expect(removed.request.body).toEqual({ endpoint: old.endpoint });
    removed.flush(null, { status: 204, statusText: 'No Content' });
    const put = await next('PUT', SUBSCRIPTIONS_URL);
    expect(put.request.body.endpoint).toBe('https://push.example.test/send/2');
    put.flush(null, { status: 204, statusText: 'No Content' });

    expect(await result).toBe(true);
    expect(old.unsubscribed).toBe(true);
    expect(Array.from(push.subscribed[1])).toEqual(Array.from(decodeBase64Url(NEW_KEY)));
  });

  it('does nothing without Web Push support', async () => {
    push.isSupported = false;

    expect(await service.sync()).toBe(false);
    await service.unsubscribe();

    expect(service.supported()).toBe(false);
    expect(service.active()).toBe(false);
  });

  it('fails quietly when the browser refuses to subscribe', async () => {
    push.failSubscribe = true;

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });

    expect(await result).toBe(false);
    expect(service.active()).toBe(false);
  });

  it('fails quietly, without a toast, when the server rejects the subscription', async () => {
    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(
      { status: 400, title: 'Bad Request' },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(await result).toBe(false);
    expect(service.active()).toBe(false);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('unsubscribes on the server and in the browser', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const subscription = push.current!;

    const done = service.unsubscribe();
    const removed = await next('DELETE', SUBSCRIPTIONS_URL);
    expect(removed.request.body).toEqual({ endpoint: subscription.endpoint });
    removed.flush(null, { status: 204, statusText: 'No Content' });
    await done;

    expect(subscription.unsubscribed).toBe(true);
    expect(push.current).toBeNull();
    expect(service.active()).toBe(false);
  });

  it('still unsubscribes in the browser when the server is unreachable', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const subscription = push.current!;

    const done = service.unsubscribe();
    (await next('DELETE', SUBSCRIPTIONS_URL)).error(new ProgressEvent('error'));
    await done;

    expect(subscription.unsubscribed).toBe(true);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('fails quietly, without a toast and without subscribing, when the server key is unavailable', async () => {
    const result = service.sync();
    (await next('GET', KEY_URL)).flush(
      { status: 503, title: 'Service Unavailable' },
      { status: 503, statusText: 'Service Unavailable' },
    );

    expect(await result).toBe(false);
    expect(push.subscribed).toHaveLength(0);
    expect(service.active()).toBe(false);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('keeps a subscription whose server key the browser does not report', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const existing = push.current!;
    (existing as { applicationServerKey: ArrayBuffer | null }).applicationServerKey = null;

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: NEW_KEY });
    const put = await next('PUT', SUBSCRIPTIONS_URL);
    expect(put.request.body.endpoint).toBe(existing.endpoint);
    put.flush(null, { status: 204, statusText: 'No Content' });

    expect(await result).toBe(true);
    expect(existing.unsubscribed).toBe(false);
    expect(push.subscribed).toHaveLength(1);
  });

  it('still replaces an old-key subscription when the server cannot remove it', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    const old = push.current!;

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: NEW_KEY });
    (await next('DELETE', SUBSCRIPTIONS_URL)).error(new ProgressEvent('error'));
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, { status: 204, statusText: 'No Content' });

    expect(await result).toBe(true);
    expect(old.unsubscribed).toBe(true);
    expect(push.subscribed).toHaveLength(2);
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('sends empty keys when the browser reports none, and stays inactive when the server rejects them', async () => {
    await push.subscribe(decodeBase64Url(KEY));
    vi.spyOn(push.current!, 'toJSON').mockReturnValue({ endpoint: push.current!.endpoint });

    const result = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    const put = await next('PUT', SUBSCRIPTIONS_URL);
    expect(put.request.body.keys).toEqual({ p256dh: '', auth: '' });
    put.flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });

    expect(await result).toBe(false);
    expect(service.active()).toBe(false);
  });

  it('sends no request when unsubscribing a browser that is not subscribed', async () => {
    await service.unsubscribe();

    backend.expectNone({ method: 'DELETE', url: SUBSCRIPTIONS_URL });
    expect(service.active()).toBe(false);
  });

  it('becomes inactive when unsubscribing after a successful sync', async () => {
    const on = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, { status: 204, statusText: 'No Content' });
    expect(await on).toBe(true);
    expect(service.active()).toBe(true);

    const off = service.unsubscribe();
    expect(service.active()).toBe(true); // queued, not yet run
    (await next('DELETE', SUBSCRIPTIONS_URL)).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await off;

    expect(service.active()).toBe(false);
    expect(push.current).toBeNull();
  });

  it('runs a quick on/off in order, so it ends unsubscribed', async () => {
    const on = service.sync();
    const off = service.unsubscribe();

    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    backend.expectNone({ method: 'DELETE', url: SUBSCRIPTIONS_URL });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, { status: 204, statusText: 'No Content' });
    expect(await on).toBe(true);
    const removed = await next('DELETE', SUBSCRIPTIONS_URL);
    expect(removed.request.body).toEqual({ endpoint: 'https://push.example.test/send/1' });
    removed.flush(null, { status: 204, statusText: 'No Content' });
    await off;

    expect(push.current).toBeNull();
    expect(service.active()).toBe(false);
  });

  it('keeps working after a failed call', async () => {
    push.failSubscribe = true;
    const failed = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    expect(await failed).toBe(false);

    push.failSubscribe = false;
    const retried = service.sync();
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, { status: 204, statusText: 'No Content' });

    expect(await retried).toBe(true);
    expect(service.active()).toBe(true);
  });

  it('decodes base64url whose length needs one or two padding characters', () => {
    expect(Array.from(decodeBase64Url('AQI'))).toEqual([1, 2]);
    expect(Array.from(decodeBase64Url('AQ'))).toEqual([1]);
    expect(Array.from(decodeBase64Url('AQID'))).toEqual([1, 2, 3]);
  });

  it('runs a quick off/on in order', async () => {
    const off = service.unsubscribe();
    const on = service.sync();
    await off;
    (await next('GET', KEY_URL)).flush({ publicKey: KEY });
    (await next('PUT', SUBSCRIPTIONS_URL)).flush(null, { status: 204, statusText: 'No Content' });

    expect(await on).toBe(true);
    expect(push.current).not.toBeNull();
  });
});
