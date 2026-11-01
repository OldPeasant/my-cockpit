import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge } from 'rxjs';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ChangeEventsService } from '../../../core/events';
import { ApiError } from '../../../core/http';
import { MAIL_ACCOUNT_EVENT, MAIL_FOLDER_EVENT, REFRESH_DEBOUNCE_MS } from './mail-list-store';
import { MailService } from './mail.service';

/** A folder in the tree, with its nesting depth (0 for top-level folders). */
export interface FolderNode {
  folder: MailFolderResponse;
  depth: number;
}

/** An account with its folders in tree order. */
export interface AccountTree {
  account: MailAccountResponse;
  folders: FolderNode[];
}

/** The order of folders with a role; other folders follow by name. */
const ROLE_ORDER = ['INBOX', 'DRAFTS', 'SENT', 'ARCHIVE', 'SPAM', 'TRASH'];

/**
 * The accounts and their cached folders, for the folder tree, titles and the move dialog. Reloads
 * when accounts change, and when a folder event names a folder it does not know (a folder sync).
 */
@Injectable()
export class MailFoldersStore {
  private readonly mail = inject(MailService);

  /** Accounts in display-name order with their folders; null until loaded. */
  readonly trees = signal<AccountTree[] | null>(null);
  readonly error = signal<string | null>(null);

  readonly folderById = computed(() => {
    const map = new Map<string, MailFolderResponse>();
    for (const tree of this.trees() ?? []) {
      for (const node of tree.folders) map.set(node.folder.id, node.folder);
    }
    return map;
  });
  readonly accountById = computed(
    () => new Map((this.trees() ?? []).map((tree) => [tree.account.id, tree.account])),
  );

  private seq = 0;

  constructor() {
    const events = inject(ChangeEventsService);
    merge(
      events.events$(MAIL_ACCOUNT_EVENT),
      events
        .events$(MAIL_FOLDER_EVENT)
        .pipe(filter((event) => event.op !== 'UPDATED' || !this.folderById().has(event.id))),
      events.resync$,
    )
      .pipe(debounceTime(REFRESH_DEBOUNCE_MS), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => void this.load());
  }

  async load(): Promise<void> {
    const seq = ++this.seq;
    try {
      const accounts = await this.mail.accounts();
      const folders = await Promise.all(accounts.map((account) => this.mail.folders(account.id)));
      if (seq !== this.seq) return;
      this.trees.set(
        accounts.map((account, index) => ({ account, folders: folderTree(folders[index]) })),
      );
      this.error.set(null);
    } catch (error) {
      if (seq !== this.seq) return;
      this.error.set(
        error instanceof ApiError
          ? error.userMessage
          : $localize`:@@mail.folders.error:The folders could not be loaded.`,
      );
    }
  }

  /**
   * Shows a folder created on the server at once, then reloads the folders in the background (the
   * server may have changed others, e.g. a parent that became a group).
   */
  added(folder: MailFolderResponse): void {
    this.updateAccount(folder.accountId, (folders) => [
      ...folders.filter((other) => other.id !== folder.id),
      folder,
    ]);
    void this.load();
  }

  /**
   * Shows a renamed folder at once: its subfolders move along (their paths change, their ids stay).
   * Then reloads the folders in the background for their new versions.
   */
  renamed(before: MailFolderResponse, after: MailFolderResponse): void {
    this.updateAccount(before.accountId, (folders) =>
      folders.map((folder) => {
        if (folder.id === before.id) return after;
        if (!isBelow(folder, before)) return folder;
        return { ...folder, path: after.path + folder.path.slice(before.path.length) };
      }),
    );
    void this.load();
  }

  /** Drops a deleted folder and its subfolders at once; returns their ids. */
  removed(deleted: MailFolderResponse): Set<string> {
    const ids = new Set(this.subtree(deleted).map((folder) => folder.id));
    this.updateAccount(deleted.accountId, (folders) =>
      folders.filter((folder) => !ids.has(folder.id)),
    );
    void this.load();
    return ids;
  }

  /** The folder and every folder below it. */
  subtree(root: MailFolderResponse): MailFolderResponse[] {
    const tree = (this.trees() ?? []).find((candidate) => candidate.account.id === root.accountId);
    return (tree?.folders ?? [])
      .map((node) => node.folder)
      .filter((folder) => folder.id === root.id || isBelow(folder, root));
  }

  private updateAccount(
    accountId: string,
    change: (folders: MailFolderResponse[]) => MailFolderResponse[],
  ): void {
    this.trees.update((trees) =>
      trees?.map((tree) =>
        tree.account.id === accountId
          ? { ...tree, folders: folderTree(change(tree.folders.map((node) => node.folder))) }
          : tree,
      ) ?? null,
    );
  }

  /** The selectable folders of an account other than `exceptId`, in tree order. */
  moveTargets(accountId: string, exceptId?: string | null): FolderNode[] {
    const tree = (this.trees() ?? []).find((candidate) => candidate.account.id === accountId);
    return (tree?.folders ?? []).filter(
      (node) => node.folder.selectable && node.folder.id !== exceptId,
    );
  }
}

/**
 * Orders an account's folders as a tree: a folder is the child of the folder whose path is its own
 * without the last segment. Top level: folders with a role first (Inbox, Drafts, Sent, Archive,
 * Spam, Trash), then the others by name; children by name.
 */
export function folderTree(folders: MailFolderResponse[]): FolderNode[] {
  const byPath = new Map(folders.map((folder) => [folder.path, folder]));
  const children = new Map<string | null, MailFolderResponse[]>();
  for (const folder of folders) {
    const parent = parentPath(folder);
    const key = parent !== null && byPath.has(parent) ? parent : null;
    children.set(key, [...(children.get(key) ?? []), folder]);
  }
  const rank = (folder: MailFolderResponse) =>
    folder.role ? ROLE_ORDER.indexOf(folder.role) : ROLE_ORDER.length;
  const byName = (a: MailFolderResponse, b: MailFolderResponse) =>
    a.name.localeCompare(b.name) || a.path.localeCompare(b.path);
  const nodes: FolderNode[] = [];
  const walk = (key: string | null, depth: number) => {
    const list = [...(children.get(key) ?? [])].sort(
      depth === 0 ? (a, b) => rank(a) - rank(b) || byName(a, b) : byName,
    );
    for (const folder of list) {
      nodes.push({ folder, depth });
      walk(folder.path, depth + 1);
    }
  };
  walk(null, 0);
  return nodes;
}

/** Whether `folder` lies somewhere below `ancestor` (same account, path under its path). */
function isBelow(folder: MailFolderResponse, ancestor: MailFolderResponse): boolean {
  return (
    !!ancestor.delimiter &&
    folder.accountId === ancestor.accountId &&
    folder.id !== ancestor.id &&
    folder.path.startsWith(ancestor.path + ancestor.delimiter)
  );
}

function parentPath(folder: MailFolderResponse): string | null {
  if (!folder.delimiter) return null;
  const index = folder.path.lastIndexOf(folder.delimiter);
  return index > 0 ? folder.path.slice(0, index) : null;
}
