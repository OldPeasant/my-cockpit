import { createEnvironmentInjector, EnvironmentInjector, InjectionToken } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { CAPTURE_DIALOG } from '../../shell/shell-tokens';
import { CapturePlaceholder } from './capture-placeholder';
import { PlaceholderCaptureDialog, provideCapture } from './capture.providers';

describe('provideCapture (placeholder)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideCapture()] });
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  function openDialogs(): unknown[] {
    return TestBed.inject(MatDialog).openDialogs.map((ref) => ref.componentInstance);
  }

  it('provides CAPTURE_DIALOG with the placeholder implementation', () => {
    expect(TestBed.inject(CAPTURE_DIALOG)).toBeInstanceOf(PlaceholderCaptureDialog);
  });

  it('opens the lazily loaded placeholder dialog with a close button', async () => {
    TestBed.inject(CAPTURE_DIALOG).open();
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CapturePlaceholder);

    const dialog = await vi.waitFor(() => {
      const el = document.querySelector('app-capture-placeholder');
      expect(el).not.toBeNull();
      return el!;
    });
    await vi.waitFor(() =>
      expect(dialog.querySelector('[mat-dialog-title]')?.textContent?.trim()).toBe('Capture'),
    );
    expect(dialog.textContent).toContain('Quick capture to the inbox is coming soon.');

    dialog.querySelector<HTMLButtonElement>('button[mat-dialog-close]')!.click();
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
      expect(ref.componentInstance).toBeInstanceOf(CapturePlaceholder);
      expect(ref.componentRef!.injector.get(ROUTE_ONLY, null)).toBe('from the route');
    } finally {
      TestBed.inject(MatDialog).closeAll();
      route.destroy();
    }
  });
});
