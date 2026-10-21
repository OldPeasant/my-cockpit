import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  applyMailAction,
  downloadMailAttachment,
  FileResponse,
  getMailMessage,
  MailGtdItemResponse,
  MailMessageResponse,
  mailToNote,
  mailToTask,
  mailToWaiting,
  uploadFile,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** What the read view does with a message: the GTD actions of SPEC.md §7.3. */
export type MailGtdAction = 'task' | 'waiting' | 'note';

/**
 * The mail API of the read view (SPEC.md §7.3, §7.4): one message with its sanitized body (remote
 * images blocked or proxied), its attachments, the GTD actions and "save to Files". Errors are not
 * toasted: the view reports them.
 */
@Injectable({ providedIn: 'root' })
export class MailReadService {
  private readonly api = inject(Api);

  /** The message; `loadImages` rewrites remote images to the server's image proxy. */
  get(id: string, loadImages: boolean): Promise<MailMessageResponse> {
    return this.api.invoke(
      getMailMessage,
      { id, remoteImages: loadImages ? 'PROXY' : 'BLOCK' },
      silent(),
    );
  }

  /** Marks the message read on the mail server and in the cache. */
  async markRead(id: string): Promise<void> {
    await this.api.invoke(applyMailAction, { body: { action: 'READ', ids: [id] } }, silent());
  }

  /** Creates an Inbox task, a Waiting For task or a note from the message, linked to it. */
  gtd(id: string, action: MailGtdAction): Promise<MailGtdItemResponse> {
    const fn = action === 'task' ? mailToTask : action === 'waiting' ? mailToWaiting : mailToNote;
    return this.api.invoke(fn, { id }, silent());
  }

  /** The decoded attachment; `inline` for raster images (previews). */
  attachment(id: string, partId: string, inline = false): Promise<Blob> {
    return this.api.invoke(downloadMailAttachment, { id, partId, inline }, silent());
  }

  /** Copies an attachment to the root of Files under its file name. */
  async saveToFiles(id: string, partId: string, name: string): Promise<FileResponse> {
    const file = await this.attachment(id, partId);
    return this.api.invoke(uploadFile, { name, body: { file } }, silent());
  }
}

/** The download URL of an attachment (the browser sends the session cookie). */
export function attachmentUrl(id: string, partId: string, inline = false): string {
  const path = `/api/v1/mail/messages/${encodeURIComponent(id)}/attachments/${encodeURIComponent(partId)}`;
  return inline ? `${path}?inline=true` : path;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
