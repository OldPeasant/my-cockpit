import { filter, Subject } from 'rxjs';
import {
  ChangeEvent,
  MailAccountResponse,
  MailFolderResponse,
  MailToTaskResultResponse,
} from '../../../api/generated';
import { MailAction, MailPage, MailScope, MailSearchResult, MailSummary } from './mail.service';

// Test doubles of the mail list specs.

export const INBOX = 'f-inbox';
export const OTHER = 'f-other';

export function summary(id: string, extra: Partial<MailSummary> = {}): MailSummary {
  return {
    id,
    accountId: 'acc-1',
    folderId: INBOX,
    subject: `Subject ${id}`,
    snippet: '',
    threadKey: id,
    receivedAt: '2026-10-20T10:00:00Z',
    sentAt: '2026-10-20T10:00:00Z',
    seen: false,
    flagged: false,
    from: [{ name: 'Max Muster', address: 'max@example.test' }],
    ...extra,
  };
}

/** A "→ Next action & archive" result of message `id`; not archived with `archiveDetail`. */
export function toTaskResult(
  id: string,
  archived = true,
  archiveDetail: string | null = null,
): MailToTaskResultResponse {
  return {
    id,
    messageId: id,
    linkId: `link-${id}`,
    archived,
    archiveErrorCode: archived ? null : 'NO_ROLE_FOLDER',
    archiveDetail: archived ? null : archiveDetail,
    task: { id: `task-${id}`, list: 'NEXT', title: `Subject ${id}` },
  };
}

/** A controllable MailService: each call waits until the test resolves or rejects it. */
export class FakeMail {
  lists: {
    scope: MailScope;
    cursor?: string;
    limit?: number;
    resolve: (page: MailPage) => void;
    reject: (e: unknown) => void;
  }[] = [];
  applies: {
    action: MailAction;
    ids: string[];
    target?: string;
    resolve: (
      results: { id: string; ok: boolean; detail: string | null; errorCode: string | null }[],
    ) => void;
    reject: (e: unknown) => void;
  }[] = [];
  threads = new Map<string, MailSummary[]>();
  loadOlders: {
    folderId: string;
    resolve: (result: { loaded: number; hasMore: boolean }) => void;
    reject: (e: unknown) => void;
  }[] = [];

  loadOlder(folderId: string): Promise<{ loaded: number; hasMore: boolean }> {
    return new Promise((resolve, reject) => this.loadOlders.push({ folderId, resolve, reject }));
  }
  searches: {
    query: string;
    folderId: string | null;
    server: boolean;
    resolve: (result: MailSearchResult) => void;
    reject: (e: unknown) => void;
  }[] = [];

  search(query: string, folderId: string | null, server: boolean): Promise<MailSearchResult> {
    return new Promise((resolve, reject) =>
      this.searches.push({ query, folderId, server, resolve, reject }),
    );
  }

  list(scope: MailScope, cursor?: string, limit?: number): Promise<MailPage> {
    return new Promise((resolve, reject) =>
      this.lists.push({ scope, cursor, limit, resolve, reject }),
    );
  }

  apply(action: MailAction, ids: string[], target?: string) {
    return new Promise<
      { id: string; ok: boolean; detail: string | null; errorCode: string | null }[]
    >((resolve, reject) => this.applies.push({ action, ids, target, resolve, reject }));
  }

  thread(id: string): Promise<MailSummary[]> {
    return Promise.resolve(this.threads.get(id) ?? []);
  }

  toTasks: {
    ids: readonly string[];
    resolve: (results: MailToTaskResultResponse[]) => void;
    reject: (e: unknown) => void;
  }[] = [];

  toNextActions(ids: readonly string[]): Promise<MailToTaskResultResponse[]> {
    return new Promise((resolve, reject) => this.toTasks.push({ ids, resolve, reject }));
  }

  accounts: () => Promise<MailAccountResponse[]> = () => Promise.resolve([]);
  folders: (accountId: string) => Promise<MailFolderResponse[]> = () => Promise.resolve([]);

  /** Answers every apply with success except `failing`. */
  succeed(index = 0, failing: string[] = [], detail = 'Server said no') {
    const call = this.applies[index];
    call.resolve(
      call.ids.map((id) => ({
        id,
        ok: !failing.includes(id),
        detail: failing.includes(id) ? detail : null,
        errorCode: failing.includes(id) ? 'REJECTED' : null,
      })),
    );
  }
}

export class FakeEvents {
  readonly changes = new Subject<ChangeEvent>();
  readonly resync$ = new Subject<void>();
  events$(type: string) {
    return this.changes.pipe(filter((event) => event.type === type));
  }
}
