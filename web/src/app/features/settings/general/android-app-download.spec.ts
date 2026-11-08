import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NotificationService, provideHttp, SILENT_ERRORS } from '../../../core/http';
import { AndroidAppDownload } from './android-app-download';
import { ANDROID_VERSION_PATH, fakeAndroidApp } from './android-app.testing';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('AndroidAppDownload', () => {
  let fixture: ComponentFixture<AndroidAppDownload>;
  let root: HTMLElement;
  let http: HttpTestingController;
  let notifications: { error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifications = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AndroidAppDownload);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  async function answer(
    body: unknown,
    init: { status?: number; statusText?: string; headers?: Record<string, string> } = {},
  ): Promise<void> {
    const req = http.expectOne({ method: 'GET', url: ANDROID_VERSION_PATH });
    expect(req.request.context.get(SILENT_ERRORS)).toBe(true);
    req.flush(body as object, init);
    await tick();
    await fixture.whenStable();
  }

  const problem = (status: number, title: string) => ({
    status,
    statusText: title,
    headers: { 'Content-Type': 'application/problem+json' },
  });

  it('says it is checking until the version arrives', async () => {
    expect(root.querySelector('[role="status"]')?.textContent?.trim()).toBe('Checking…');
    await answer(fakeAndroidApp());
  });

  it('shows the version, size and date and links the APK download', async () => {
    await answer(fakeAndroidApp());
    expect(root.querySelector('.version')?.textContent?.trim()).toBe('Version 1.4.0');
    const details = root.querySelector('.version + .note')?.textContent?.replace(/\s+/g, ' ');
    expect(details?.trim()).toBe('12.3 MB, updated Nov 2, 2026');
    const link = root.querySelector<HTMLAnchorElement>('a.download')!;
    expect(link.getAttribute('href')).toBe('/download/android');
    expect(link.hasAttribute('download')).toBe(true);
    expect(link.textContent?.trim()).toBe('Download APK');
  });

  it('still offers the download when the APK has no version name', async () => {
    await answer(fakeAndroidApp({ versionName: undefined, versionCode: undefined }));
    expect(root.querySelector('.version')?.textContent?.trim()).toBe('Version unknown');
    expect(root.querySelector('a.download')).not.toBeNull();
  });

  it('says so, without a link, when the server has no APK (404), and toasts nothing', async () => {
    await answer({ status: 404, title: 'Not Found' }, problem(404, 'Not Found'));
    expect(root.textContent?.trim()).toBe('No Android app is available on this server.');
    expect(root.querySelector('a')).toBeNull();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('treats an unreachable server as a failure, not as "no app"', async () => {
    const req = http.expectOne({ method: 'GET', url: ANDROID_VERSION_PATH });
    req.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    await tick();
    await fixture.whenStable();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'The Android app version could not be loaded.',
    );
    expect(root.textContent).not.toContain('No Android app is available');
    expect(root.querySelector('a')).toBeNull();
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('a 404 after Try again also says that no app is available', async () => {
    await answer({ status: 500, title: 'Server Error' }, problem(500, 'Server Error'));
    root.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    fixture.detectChanges();
    await answer({ status: 404, title: 'Not Found' }, problem(404, 'Not Found'));
    expect(root.textContent?.trim()).toBe('No Android app is available on this server.');
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows a failure in place and loads again on Try again', async () => {
    await answer(
      { status: 503, title: 'Service Unavailable' },
      problem(503, 'Service Unavailable'),
    );
    const alert = root.querySelector('[role="alert"]')!;
    expect(alert.textContent).toContain('The Android app version could not be loaded.');
    expect(root.querySelector('a')).toBeNull();
    expect(notifications.error).not.toHaveBeenCalled();

    alert.querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(root.textContent?.trim()).toBe('Checking…');
    await answer(fakeAndroidApp());
    expect(root.querySelector('a.download')).not.toBeNull();
  });
});
