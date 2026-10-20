import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  capture,
  CapturePreviewResponse,
  CaptureRequest,
  previewCapture,
  TaskResponse,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/** Quick capture calls (SPEC.md §4.5): the syntax preview and the save to the Inbox. */
@Injectable({ providedIn: 'root' })
export class CaptureService {
  private readonly api = inject(Api);

  /** What a capture of `text` would create. Silent: a failed preview only hides the preview. */
  preview(text: string): Promise<CapturePreviewResponse> {
    return this.api.invoke(
      previewCapture,
      { body: { text } },
      new HttpContext().set(SILENT_ERRORS, true),
    );
  }

  /**
   * Captures to the Inbox. Send the same `id` again to retry: the server answers a repeat with the
   * task it already created, so a retry never creates a duplicate.
   */
  save(body: CaptureRequest & { id: string }): Promise<TaskResponse> {
    return this.api.invoke(capture, { body });
  }
}
