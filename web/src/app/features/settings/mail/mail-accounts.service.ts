import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  ConnectionTestResponse,
  createMailAccount,
  CreateMailAccountRequest,
  deleteMailAccount,
  listMailAccounts,
  listMailFolders,
  listOAuthConnections,
  listOAuthProviders,
  MailAccountResponse,
  MailFolderResponse,
  OAuthConnectionResponse,
  OAuthProviderResponse,
  startOAuthConnection,
  syncMailFolders,
  testMailAccountConnection,
  updateMailAccount,
  UpdateMailAccountRequest,
  updateMailFolder,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { BROWSER_NAVIGATE, OAuthSlug } from '../calendars/calendar-sources.service';

/** A folder role, or none. */
export type FolderRole = NonNullable<MailFolderResponse['role']>;

/** How a folder gets its role: detected by the server, set by hand, or none set by hand. */
export type RoleChoice = FolderRole | 'AUTOMATIC' | 'NONE';

/**
 * Mail accounts, their folders and the OAuth connections they log in with, for the settings
 * (SPEC.md §7.1). Errors are not toasted: the section shows them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class MailAccountsService {
  private readonly api = inject(Api);
  private readonly navigate = inject(BROWSER_NAVIGATE);

  /** Every account in creation order. */
  async accounts(): Promise<MailAccountResponse[]> {
    return (await this.api.invoke(listMailAccounts, undefined, silent())).items;
  }

  /** Adds an account; the servers are not contacted (see `test` and `syncFolders`). */
  create(body: CreateMailAccountRequest): Promise<MailAccountResponse> {
    return this.api.invoke(createMailAccount, { body }, silent());
  }

  /** Changes an account at the version the page showed. */
  update(
    account: MailAccountResponse,
    body: UpdateMailAccountRequest,
  ): Promise<MailAccountResponse> {
    return this.api.invoke(
      updateMailAccount,
      { id: account.id, 'If-Match': `"${account.version}"`, body },
      silent(),
    );
  }

  /** Removes an account with its cached mail. */
  remove(account: MailAccountResponse): Promise<void> {
    return this.api.invoke(
      deleteMailAccount,
      { id: account.id, 'If-Match': `"${account.version}"` },
      silent(),
    );
  }

  /** Logs in to IMAP and SMTP with the stored settings; a failed login is in the result. */
  test(id: string): Promise<ConnectionTestResponse> {
    return this.api.invoke(testMailAccountConnection, { id }, silent());
  }

  /** The folders known for an account (empty until they were detected once). */
  async folders(id: string): Promise<MailFolderResponse[]> {
    return (await this.api.invoke(listMailFolders, { id }, silent())).items;
  }

  /** Reads the folder list from the server again and detects the roles. */
  async syncFolders(id: string): Promise<MailFolderResponse[]> {
    return (await this.api.invoke(syncMailFolders, { id }, silent())).items;
  }

  /** Sets a folder's role by hand, or hands it back to detection (`AUTOMATIC`). */
  setRole(folder: MailFolderResponse, choice: RoleChoice): Promise<MailFolderResponse> {
    // No role in the body means "no role" for the server.
    const body =
      choice === 'AUTOMATIC' ? { automatic: true } : choice === 'NONE' ? {} : { role: choice };
    return this.api.invoke(
      updateMailFolder,
      { id: folder.accountId, folderId: folder.id, 'If-Match': `"${folder.version}"`, body },
      silent(),
    );
  }

  /** Google and Microsoft, and whether the server has their OAuth app. */
  providers(): Promise<OAuthProviderResponse[]> {
    return this.api.invoke(listOAuthProviders, undefined, silent());
  }

  /** The connected Google/Microsoft accounts, oldest first. */
  connections(): Promise<OAuthConnectionResponse[]> {
    return this.api.invoke(listOAuthConnections, undefined, silent());
  }

  /**
   * Leaves for the provider's consent screen. The server sends the browser back to
   * Settings → Integrations; the new connection is then offered when adding a mail account.
   */
  connect(provider: OAuthSlug): void {
    const query = new URLSearchParams({ purpose: 'MAIL' });
    const path = startOAuthConnection.PATH.replace('{provider}', provider);
    this.navigate(`${this.api.rootUrl}${path}?${query}`);
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
