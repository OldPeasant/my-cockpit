import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  applyMailAction,
  createMailFolder,
  deleteMailFolder,
  getMailThread,
  listMailAccounts,
  listMailFolders,
  listMailMessages,
  MailAccountResponse,
  MailActionRequest,
  MailActionResultResponse,
  MailFolderResponse,
  MailMessageSummaryResponse,
  searchMail,
  updateMailFolder,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** Page size of a message list. */
export const MAIL_PAGE_SIZE = 50;
/** The largest page the server returns (`CursorRequest.MAX_LIMIT`). */
export const MAIL_MAX_PAGE_SIZE = 200;
/** The most ids one action request may carry (`MailActionRequest.ids`). */
export const MAIL_MAX_ACTION_IDS = 1000;

export type MailAction = MailActionRequest['action'];

/** A list entry whose id is known (the generated model marks every field optional). */
export type MailSummary = MailMessageSummaryResponse & { id: string };

export interface MailPage {
  items: MailSummary[];
  nextCursor: string | null;
}

/** The answer of a mail search (SPEC.md §7.3 "Search"). */
export interface MailSearchResult {
  /** Newest first. */
  items: MailSummary[];
  /** More messages match than were returned: only the newest are listed. */
  hasMore: boolean;
  /** Ids of the accounts whose server could not be searched (only with `server`). */
  failedAccountIds: string[];
}

/**
 * What the mail list shows: the unified Inbox of all accounts (`folderId` null) or one folder.
 * `threaded` lists one item per conversation. With `search`, the list shows the messages of that
 * scope (all folders of all accounts instead of the unified Inbox) matching the query, never
 * threaded.
 */
export interface MailScope {
  folderId: string | null;
  threaded: boolean;
  search?: MailSearch | null;
}

/** A search of the mail list: the query text, and whether the mail servers are searched too. */
export interface MailSearch {
  query: string;
  server: boolean;
}

/**
 * The mail API used by the list (SPEC.md §7.4): accounts, cached folders, message pages, the
 * conversation of a message and the triage actions. Errors are not toasted: the view reports them.
 */
@Injectable({ providedIn: 'root' })
export class MailService {
  private readonly api = inject(Api);

  async accounts(): Promise<MailAccountResponse[]> {
    return (await this.api.invoke(listMailAccounts, undefined, silent())).items;
  }

  async folders(accountId: string): Promise<MailFolderResponse[]> {
    return (await this.api.invoke(listMailFolders, { id: accountId }, silent())).items;
  }

  /** Creates a folder on the mail server, under `parentId` or at the top level. */
  async createFolder(
    accountId: string,
    name: string,
    parentId?: string | null,
  ): Promise<MailFolderResponse> {
    return this.api.invoke(
      createMailFolder,
      { id: accountId, body: { name, parentId: parentId ?? undefined } },
      silent(),
    );
  }

  /** Renames a folder on the mail server (same parent); its subfolders move along. */
  async renameFolder(folder: MailFolderResponse, name: string): Promise<MailFolderResponse> {
    return this.api.invoke(
      updateMailFolder,
      {
        id: folder.accountId,
        folderId: folder.id,
        'If-Match': `"${folder.version}"`,
        body: { name },
      },
      silent(),
    );
  }

  /** Deletes a folder with its subfolders and their messages on the mail server. */
  async deleteFolder(folder: MailFolderResponse): Promise<void> {
    await this.api.invoke(
      deleteMailFolder,
      { id: folder.accountId, folderId: folder.id, 'If-Match': `"${folder.version}"` },
      silent(),
    );
  }

  /** One page of the scope, newest first. */
  async list(scope: MailScope, cursor?: string, limit = MAIL_PAGE_SIZE): Promise<MailPage> {
    const page = await this.api.invoke(
      listMailMessages,
      {
        folderId: scope.folderId ?? undefined,
        threaded: scope.threaded || undefined,
        cursor,
        limit,
      },
      silent(),
    );
    return {
      items: (page.items ?? []).filter((item): item is MailSummary => !!item.id),
      nextCursor: page.nextCursor ?? null,
    };
  }

  /**
   * Messages matching `query` in one folder, or in all folders of all accounts (`folderId` null).
   * `server` also searches the mail servers, including mail older than the sync window.
   */
  async search(
    query: string,
    folderId: string | null,
    server: boolean,
    limit = MAIL_PAGE_SIZE,
  ): Promise<MailSearchResult> {
    const response = await this.api.invoke(
      searchMail,
      { q: query, folderId: folderId ?? undefined, server: server || undefined, limit },
      silent(),
    );
    return {
      items: (response.items ?? []).filter((item): item is MailSummary => !!item.id),
      hasMore: response.hasMore ?? false,
      failedAccountIds: (response.server?.failures ?? [])
        .map((failure) => failure.accountId)
        .filter((id): id is string => !!id),
    };
  }

  /** Every cached message of the conversation of `id` (all folders of its account), oldest first. */
  async thread(id: string): Promise<MailSummary[]> {
    const thread = await this.api.invoke(getMailThread, { id }, silent());
    return (thread.items ?? []).filter((item): item is MailSummary => !!item.id);
  }

  /** Applies an action; the result tells per id whether it worked. */
  async apply(
    action: MailAction,
    ids: string[],
    targetFolderId?: string,
  ): Promise<MailActionResultResponse[]> {
    const response = await this.api.invoke(
      applyMailAction,
      { body: { action, ids, targetFolderId } },
      silent(),
    );
    return response.results;
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
