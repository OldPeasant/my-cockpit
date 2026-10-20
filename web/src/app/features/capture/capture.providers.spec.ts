import { createEnvironmentInjector, EnvironmentInjector, InjectionToken } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { CAPTURE_DIALOG } from '../../shell/shell-tokens';
import { provideHttp } from '../../core/http';
import { CaptureDialog } from './capture-dialog';
import { provideCapture, QuickCaptureDialog } from './capture.providers';

describe('provideCapture', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideCapture(), provideHttp()] });
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  function openDialogs(): unknown[] {
    return TestBed.inject(MatDialog).openDialogs.map((ref) => ref.componentInstance);
  }

  it('provides CAPTURE_DIALOG with the quick capture dialog', () => {
    expect(TestBed.inject(CAPTURE_DIALOG)).toBeInstanceOf(QuickCaptureDialog);
  });

  it('opens the lazily loaded dialog with the text field focused; Cancel closes it', async () => {
    TestBed.inject(CAPTURE_DIALOG).open();
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CaptureDialog);

    const dialog = await vi.waitFor(() => {
      const el = document.querySelector('app-capture-dialog');
      expect(el).not.toBeNull();
      return el!;
    });
    await vi.waitFor(() => expect(dialog.querySelector('h2')?.textContent?.trim()).toBe('Capture'));
    await vi.waitFor(() => expect(document.activeElement?.id).toBe('capture-text'));

    dialog.querySelector<HTMLButtonElement>('button.quiet')!.click();
    await vi.waitFor(() => expect(openDialogs()).toEqual([]));
  });

  // Regression guard for CR-2: the dialog content must resolve providers from the injector that
  // provides CAPTURE_DIALOG (the shell route in app.routes.ts), not only from the root injector.
  it('opens the dialog with the injector that provides CAPTURE_DIALOG', async () => {
    const ROUTE_ONLY = new InjectionToken<string>('ROUTE_ONLY');
    const route = createEnvironmentInjector(
      [provideCapture(), { provide: ROUTE_ONLY, useValue: 'from the route' }],
      TestBed.inject(EnvironmentInjector),
    );
    try {
      route.get(CAPTURE_DIALOG).open();
      const ref = await vi.waitFor(() => {
        const [first] = TestBed.inject(MatDialog).openDialogs;
        expect(first).toBeDefined();
        return first;
      });
      expect(ref.componentInstance).toBeInstanceOf(CaptureDialog);
      expect(ref.componentRef!.injector.get(ROUTE_ONLY, null)).toBe('from the route');
    } finally {
      TestBed.inject(MatDialog).closeAll();
      route.destroy();
    }
  });
});
