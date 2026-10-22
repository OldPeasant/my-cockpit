import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createMailDraft,
  CreateDraftRequest,
  deleteMailDraft,
  DraftContentRequest,
  DraftResponse,
  DriveEntryResponse,
  forwardMailMessage,
  getMailDraft,
  listFolder,
  listMailAccounts,
  listMailCorrespondents,
  MailAccountResponse,
  replyAllToMailMessage,
  replyToMailMessage,
  SendDraftResponse,
  sendMailDraft,
  StrictHttpResponse,
  updateMailDraft,
  uploadMailDraftAttachment,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** How a draft is started from a message. */
export type ResponseMode = 'reply' | 'replyAll' | 'forward';

/** A draft with its version: the `ETag` every change of it needs as `If-Match`. */
export interface VersionedDraft {
  draft: DraftResponse & { id: string };
  etag: string;
}

/** An autocomplete suggestion for a recipient. */
export interface RecipientSuggestion {
  address: string;
  name: string;
}

/** Suggestions shown at most. */
export const SUGGESTION_LIMIT = 8;

/**
 * The compose API (SPEC.md §7.3, §7.4): drafts in the account's Drafts folder (create, save, upload,
 * discard, send), reply/forward drafts, recent correspondents and the files to attach. Errors are not
 * toasted: the compose view reports them.
 */
@Injectable({ providedIn: 'root' })
export class MailComposeService {
  private readonly api = inject(Api);

  async accounts(): Promise<MailAccountResponse[]> {
    return (await this.api.invoke(listMailAccounts, undefined, silent())).items;
  }

  async get(id: string): Promise<VersionedDraft> {
    return versioned(await this.api.invoke$Response(getMailDraft, { id }, silent()));
  }

  async create(body: CreateDraftRequest): Promise<VersionedDraft> {
    return versioned(await this.api.invoke$Response(createMailDraft, { body }, silent()));
  }

  async update(id: string, etag: string, body: DraftContentRequest): Promise<VersionedDraft> {
    return versioned(
      await this.api.invoke$Response(updateMailDraft, { id, 'If-Match': etag, body }, silent()),
    );
  }

  async upload(id: string, etag: string, file: File): Promise<VersionedDraft> {
    return versioned(
      await this.api.invoke$Response(
        uploadMailDraftAttachment,
        { id, 'If-Match': etag, body: { file } },
        silent(),
      ),
    );
  }

  async discard(id: string, etag: string): Promise<void> {
    await this.api.invoke(deleteMailDraft, { id, 'If-Match': etag }, silent());
  }

  send(id: string, etag: string): Promise<SendDraftResponse> {
    return this.api.invoke(sendMailDraft, { id, 'If-Match': etag }, silent());
  }

  /** A new draft answering or forwarding message `id`. */
  async respond(id: string, mode: ResponseMode): Promise<VersionedDraft> {
    const fn =
      mode === 'reply'
        ? replyToMailMessage
        : mode === 'replyAll'
          ? replyAllToMailMessage
          : forwardMailMessage;
    return versioned(await this.api.invoke$Response(fn, { id }, silent()));
  }

  /** Recent correspondents matching `query` (address or name), newest first. */
  async suggestions(query: string): Promise<RecipientSuggestion[]> {
    const page = await this.api.invoke(
      listMailCorrespondents,
      { q: query || undefined, limit: SUGGESTION_LIMIT },
      silent(),
    );
    return (page.items ?? [])
      .filter((item) => !!item.address)
      .map((item) => ({ address: item.address!, name: item.name ?? '' }));
  }

  /** The folders and files of a drive folder (`null`: the root), first page. */
  async files(folderId: string | null): Promise<DriveEntryResponse[]> {
    const page = await this.api.invoke(
      listFolder,
      { folderId: folderId ?? undefined, limit: 200 },
      silent(),
    );
    return page.items;
  }
}

function versioned(response: StrictHttpResponse<DraftResponse>): VersionedDraft {
  const draft = response.body;
  const etag = response.headers.get('ETag');
  if (!draft.id || !etag) throw new Error('Draft response without id or ETag');
  return { draft: { ...draft, id: draft.id }, etag };
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
