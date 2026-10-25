import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  autocompleteContacts,
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

/** An autocomplete suggestion for a recipient; `contactId` when the address belongs to a contact. */
export interface RecipientSuggestion {
  address: string;
  name: string;
  contactId?: string;
}

/** One listing of a drive folder; `more`: the folder holds entries beyond these. */
export interface FolderListing {
  entries: DriveEntryResponse[];
  more: boolean;
}

/** Suggestions shown at most. */
export const SUGGESTION_LIMIT = 8;

/** Entries of a drive folder listed at most. */
export const FILES_LIMIT = 200;

/**
 * The compose API (SPEC.md §7.3, §7.4): drafts in the account's Drafts folder (create, save, upload,
 * discard, send), reply/forward drafts, recipient suggestions and the files to attach. Errors are not
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

  /**
   * Recipients matching `query` (name or address) from the contacts autocomplete: contacts first,
   * then recent correspondents, as the server merges them. Entries without address are dropped and
   * an address suggested twice is kept once (its first, best ranked entry).
   */
  async suggestions(query: string): Promise<RecipientSuggestion[]> {
    const found = await this.api.invoke(
      autocompleteContacts,
      { q: query, limit: SUGGESTION_LIMIT },
      silent(),
    );
    const seen = new Set<string>();
    const result: RecipientSuggestion[] = [];
    for (const item of found ?? []) {
      const address = item.email?.trim();
      if (!address || seen.has(address.toLowerCase())) continue;
      seen.add(address.toLowerCase());
      const name = item.name ?? '';
      result.push(
        item.contactId ? { address, name, contactId: item.contactId } : { address, name },
      );
    }
    return result;
  }

  /**
   * The folders and files of a drive folder (`null`: the root), first page; `more` when the folder
   * holds further entries.
   */
  async files(folderId: string | null): Promise<FolderListing> {
    const page = await this.api.invoke(
      listFolder,
      { folderId: folderId ?? undefined, limit: FILES_LIMIT },
      silent(),
    );
    return { entries: page.items, more: !!page.nextCursor };
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
