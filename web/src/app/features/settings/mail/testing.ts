import {
  ConnectionTestResponse,
  MailAccountResponse,
  MailFolderResponse,
} from '../../../api/generated';

/** Test data for the mail account settings (fictitious). */
export function fakeAccount(over: Partial<MailAccountResponse> = {}): MailAccountResponse {
  return {
    id: 'a1',
    type: 'IMAP_SMTP',
    displayName: 'Jane Doe',
    emailAddress: 'jane@mail.example.test',
    username: 'jane',
    hasPassword: true,
    imap: { host: 'imap.mail.example.test', port: 993, security: 'SSL_TLS' },
    smtp: { host: 'smtp.mail.example.test', port: 587, security: 'STARTTLS' },
    notifyNewMail: true,
    syncWindowMonths: 12,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 0,
    ...over,
  };
}

export function fakeFolder(over: Partial<MailFolderResponse> = {}): MailFolderResponse {
  return {
    id: 'f1',
    accountId: 'a1',
    path: 'INBOX',
    name: 'INBOX',
    role: 'INBOX',
    roleOverridden: false,
    selectable: true,
    version: 0,
    ...over,
  };
}

export const TEST_OK: ConnectionTestResponse = { imap: { ok: true }, smtp: { ok: true } };

export const TEST_SMTP_FAILED: ConnectionTestResponse = {
  imap: { ok: true },
  smtp: {
    ok: false,
    errorCode: 'AUTHENTICATION_FAILED',
    message: 'The server did not accept the user name or password.',
  },
};
