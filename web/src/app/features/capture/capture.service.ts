import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  BookmarkResponse,
  capture,
  CapturePreviewResponse,
  CaptureRequest,
  createBookmark,
  previewCapture,
  TaskResponse,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/**
 * Quick capture calls (SPEC.md §4.5): the syntax preview, the save to the Inbox and, for a single
 * URL, the save to Read later as a bookmark (SPEC.md §8.4).
 */
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

  /**
   * Saves `url` as a bookmark in the read-later list; the server fills in the page's title.
   * Unlike `save` there is no client id: a retry after a lost answer may create a second bookmark.
   */
  readLater(url: string, description?: string): Promise<BookmarkResponse> {
    return this.api.invoke(createBookmark, {
      body: { url, readLater: true, ...(description ? { description } : {}) },
    });
  }
}
