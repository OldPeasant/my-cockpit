import { inject, Injectable, Injector, Provider } from '@angular/core';
import { CAPTURE_DIALOG, CaptureDialog } from '../../shell/shell-tokens';

/**
 * Placeholder until the quick capture task (web-capture) builds the real dialog. The dialog code is
 * imported on first use, so it stays out of the initial bundle (`app.routes.ts` imports this file).
 */
@Injectable()
export class PlaceholderCaptureDialog implements CaptureDialog {
  private readonly injector = inject(Injector);

  open(): void {
    void import('./capture-placeholder').then((m) => m.openCapturePlaceholder(this.injector));
  }
}

/** Provides `CAPTURE_DIALOG` for the shell. */
export function provideCapture(): Provider[] {
  return [{ provide: CAPTURE_DIALOG, useClass: PlaceholderCaptureDialog }];
}
