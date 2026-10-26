import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  AddCardDavAccountRequest,
  addCardDavAccount,
  Api,
  CardDavAccountResponse,
  ContactPageResponse,
  ContactRelatedResponse,
  ContactRequest,
  ContactResponse,
  createCardDavContact,
  createContact,
  deleteContact,
  exportContacts,
  getContact,
  getContactRelated,
  importContacts,
  listCardDavAccounts,
  listContacts,
  listOAuthConnections,
  OAuthConnectionResponse,
  removeCardDavAccount,
  syncCardDavAccount,
  updateContact,
  VCardImportResponse,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/** Page size of the contact list. */
export const CONTACTS_PAGE_SIZE = 100;

/**
 * Contacts (SPEC.md §8.3): list and search, CRUD, related items, vCard import/export, and the
 * accounts they sync with. Changes send the contact's version as `If-Match`. Errors are not toasted:
 * the Contacts views show them next to what failed.
 */
@Injectable({ providedIn: 'root' })
export class ContactsService {
  private readonly api = inject(Api);

  /** One page of contacts by display name; `q` filters by name or email address. */
  list(q: string, cursor?: string): Promise<ContactPageResponse> {
    const query = q.trim();
    return this.api.invoke(
      listContacts,
      { limit: CONTACTS_PAGE_SIZE, ...(query ? { q: query } : {}), ...(cursor ? { cursor } : {}) },
      silent(),
    );
  }

  get(id: string): Promise<ContactResponse> {
    return this.api.invoke(getContact, { id }, silent());
  }

  /** Mails, upcoming events and Waiting For items of the contact. */
  related(id: string): Promise<ContactRelatedResponse> {
    return this.api.invoke(getContactRelated, { id }, silent());
  }

  create(body: ContactRequest): Promise<ContactResponse> {
    return this.api.invoke(createContact, { body }, silent());
  }

  /** Creates the contact in the address book of a CardDAV account; the next sync writes it there. */
  createIn(accountId: string, body: ContactRequest): Promise<ContactResponse> {
    return this.api.invoke(createCardDavContact, { id: accountId, body }, silent());
  }

  update(contact: ContactResponse, body: ContactRequest): Promise<ContactResponse> {
    return this.api.invoke(
      updateContact,
      { id: contact.id, 'If-Match': `"${contact.version}"`, body },
      silent(),
    );
  }

  /** Moves the contact to the Trash. */
  remove(contact: ContactResponse): Promise<void> {
    return this.api.invoke(
      deleteContact,
      { id: contact.id, 'If-Match': `"${contact.version}"` },
      silent(),
    );
  }

  /** Every card of the vCard file becomes a new local contact; invalid cards are skipped. */
  import(file: Blob): Promise<VCardImportResponse> {
    return this.api.invoke(importContacts, { body: { file } }, silent());
  }

  /**
   * The download URL of all contacts (or only `ids`) as one vCard file. The browser downloads it
   * with the session cookie; the server names the file.
   */
  exportUrl(ids: string[] = []): string {
    const query = ids.map((id) => `ids=${encodeURIComponent(id)}`).join('&');
    return this.api.rootUrl + exportContacts.PATH + (query ? `?${query}` : '');
  }

  /** The connected Google and Microsoft accounts that may sync contacts. */
  async syncAccounts(): Promise<OAuthConnectionResponse[]> {
    const connections = await this.api.invoke(listOAuthConnections, undefined, silent());
    return connections.filter(syncsContacts);
  }

  /** The CardDAV accounts with their sync status, in the order they were added. */
  async carddavAccounts(): Promise<CardDavAccountResponse[]> {
    return (await this.api.invoke(listCardDavAccounts, undefined, silent())).items;
  }

  addCardDavAccount(body: AddCardDavAccountRequest): Promise<CardDavAccountResponse> {
    return this.api.invoke(addCardDavAccount, { body }, silent());
  }

  /** Stops syncing the account; its contacts stay here. */
  removeCardDavAccount(id: string): Promise<void> {
    return this.api.invoke(removeCardDavAccount, { id }, silent());
  }

  /** Syncs the account now; a failing server shows in the returned status, not as an error. */
  syncCardDavAccount(id: string): Promise<CardDavAccountResponse> {
    return this.api.invoke(syncCardDavAccount, { id }, silent());
  }
}

/** Whether the connection was granted access to the provider's contacts. */
export function syncsContacts(connection: OAuthConnectionResponse): boolean {
  return connection.scopes.some((scope) => /contacts/i.test(scope));
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
