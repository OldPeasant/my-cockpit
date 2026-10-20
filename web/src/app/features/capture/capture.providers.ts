import { inject, Injectable, Injector, Provider } from '@angular/core';
import { CAPTURE_DIALOG, CaptureDialog } from '../../shell/shell-tokens';

/**
 * Opens the quick capture dialog (SPEC.md §4.5). The dialog code is imported on first use, so it
 * stays out of the initial bundle (`app.routes.ts` imports this file).
 */
@Injectable()
export class QuickCaptureDialog implements CaptureDialog {
  private readonly injector = inject(Injector);

  open(): void {
    void import('./capture-dialog').then((m) => m.openCaptureDialog(this.injector));
  }
}

/** Provides `CAPTURE_DIALOG` for the shell. */
export function provideCapture(): Provider[] {
  return [{ provide: CAPTURE_DIALOG, useClass: QuickCaptureDialog }];
}
