import { filter, Subject } from 'rxjs';
import { ChangeEvent, MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { MailAction, MailPage, MailScope, MailSummary } from './mail.service';

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
