import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { Router, RouterLink } from '@angular/router';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import {
  MailFolderDeleteDialog,
  MailFolderDeleteDialogData,
  MailFolderNameDialog,
  MailFolderNameDialogData,
} from './mail-folder-dialogs';
import { MailFoldersStore } from './mail-folders-store';
import { MailService } from './mail.service';

/**
 * The unified Inbox and the folder tree of every account. Each folder is a link to its list;
 * folders that only group others are plain text. Each account can get a new folder, each folder a
 * subfolder or a new name, or be deleted (not INBOX; folders with a role cannot be deleted).
 */
@Component({
  selector: 'app-mail-folder-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatMenuModule, RouterLink],
  template: `
    <nav class="tree" i18n-aria-label="@@mail.folders" aria-label="Mail folders">
      <a
        class="row unified"
        routerLink="/mail"
        [class.current]="current() === null"
        [attr.aria-current]="current() === null ? 'page' : null"
        i18n="@@mail.unified"
        >All inboxes</a
      >
      @if (store.error(); as error) {
        <p class="error" role="alert">{{ error }}</p>
      }
      @for (tree of store.trees() ?? []; track tree.account.id) {
        <section class="account" [attr.aria-label]="tree.account.emailAddress">
          <div class="account-head">
            <h2 class="account-name" [title]="tree.account.emailAddress">
              {{ tree.account.emailAddress }}
            </h2>
            <button
              type="button"
              class="icon add"
              [attr.aria-label]="newFolderLabel(tree.account)"
              [title]="newFolderLabel(tree.account)"
              (click)="create(tree.account)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path d="M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z" />
              </svg>
            </button>
          </div>
          <ul>
            @for (node of tree.folders; track node.folder.id) {
              <li
                class="node"
                [style.--depth]="node.depth"
                [class.current]="current() === node.folder.id"
              >
                @if (node.folder.selectable) {
                  <a
                    class="row"
                    [routerLink]="['/mail/folders', node.folder.id]"
                    [class.current]="current() === node.folder.id"
                    [attr.aria-current]="current() === node.folder.id ? 'page' : null"
                    >{{ label(node.folder) }}</a
                  >
                } @else {
                  <span class="row group">{{ label(node.folder) }}</span>
                }
                @if (hasActions(node.folder)) {
                  <button
                    type="button"
                    class="icon folder-menu"
                    [matMenuTriggerFor]="menu"
                    [matMenuTriggerData]="{ folder: node.folder }"
                    [attr.aria-label]="actionsLabel(node.folder)"
                    [title]="actionsLabel(node.folder)"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <path
                        d="M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
                      />
                    </svg>
                  </button>
                }
              </li>
            }
          </ul>
        </section>
      }
    </nav>
    <mat-menu #menu="matMenu">
      <ng-template matMenuContent let-folder="folder">
        @if (canCreateIn(folder)) {
          <button
            mat-menu-item
            type="button"
            class="new-subfolder"
            (click)="create(account(folder), folder)"
            i18n="@@mail.folder.newSubfolder"
          >
            New subfolder
          </button>
        }
        @if (canRename(folder)) {
          <button
            mat-menu-item
            type="button"
            class="rename"
            (click)="rename(folder)"
            i18n="@@mail.folder.rename"
          >
            Rename
          </button>
        }
        @if (canDelete(folder)) {
          <button
            mat-menu-item
            type="button"
            class="delete"
            (click)="remove(folder)"
            i18n="@@mail.folder.delete"
          >
            Delete
          </button>
        }
      </ng-template>
    </mat-menu>
  `,
  styleUrl: './mail-folder-tree.scss',
})
export class MailFolderTree {
  protected readonly store = inject(MailFoldersStore);
  private readonly mail = inject(MailService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  /** The shown folder; null for the unified Inbox. */
  readonly current = input<string | null>(null);

  protected label(folder: MailFolderResponse): string {
    return folderLabel(folder);
  }

  protected newFolderLabel(account: MailAccountResponse): string {
    return $localize`:@@mail.folder.newIn:New folder in ${account.emailAddress}:account:`;
  }

  protected actionsLabel(folder: MailFolderResponse): string {
    return $localize`:@@mail.folder.actions:Actions for ${folderLabel(folder)}:folder:`;
  }

  protected account(folder: MailFolderResponse): MailAccountResponse | undefined {
    return this.store.accountById().get(folder.accountId);
  }

  /** Subfolders need a hierarchy delimiter (flat namespaces have none). */
  protected canCreateIn(folder: MailFolderResponse): boolean {
    return !!folder.delimiter;
  }

  protected canRename(folder: MailFolderResponse): boolean {
    return !isInbox(folder);
  }

  /** Not INBOX, nor a folder with a role or holding one (the server refuses those). */
  protected canDelete(folder: MailFolderResponse): boolean {
    return !isInbox(folder) && this.store.subtree(folder).every((each) => !each.role);
  }

  protected hasActions(folder: MailFolderResponse): boolean {
    return this.canCreateIn(folder) || this.canRename(folder) || this.canDelete(folder);
  }

  protected create(account: MailAccountResponse | undefined, parent?: MailFolderResponse): void {
    if (!account?.id) return;
    const accountId = account.id;
    const title = parent
      ? $localize`:@@mail.folder.newSubfolder.title:New folder in ${folderLabel(parent)}:folder:`
      : $localize`:@@mail.folder.new.title:New folder`;
    this.dialog.open<MailFolderNameDialog, MailFolderNameDialogData, boolean>(
      MailFolderNameDialog,
      {
        data: {
          title,
          confirm: $localize`:@@mail.folder.create:Create`,
          delimiter: parent?.delimiter ?? this.delimiterOf(accountId),
          save: (name) =>
            this.reloadingOnStale(async () => {
              this.store.added(await this.mail.createFolder(accountId, name, parent?.id));
            }),
        },
      },
    );
  }

  protected rename(folder: MailFolderResponse): void {
    this.dialog.open<MailFolderNameDialog, MailFolderNameDialogData, boolean>(
      MailFolderNameDialog,
      {
        data: {
          title: $localize`:@@mail.folder.rename.title:Rename ${folderLabel(folder)}:folder:`,
          confirm: $localize`:@@mail.folder.rename.confirm:Rename`,
          name: folder.name,
          delimiter: folder.delimiter,
          save: (name) =>
            this.reloadingOnStale(async () => {
              this.store.renamed(folder, await this.mail.renameFolder(folder, name));
            }),
        },
      },
    );
  }

  protected remove(folder: MailFolderResponse): void {
    this.dialog.open<MailFolderDeleteDialog, MailFolderDeleteDialogData, boolean>(
      MailFolderDeleteDialog,
      {
        data: {
          name: folderLabel(folder),
          subfolders: this.store.subtree(folder).length - 1,
          // 409: INBOX or a role the tree did not know about; the reload shows it and hides Delete.
          remove: () =>
            this.reloadingOnStale(async () => {
              try {
                await this.mail.deleteFolder(folder);
              } catch (error) {
                // Already gone on the server (404): what the owner wanted, so drop it here too.
                if (!(error instanceof ApiError && error.status === 404)) throw error;
              }
              const removed = this.store.removed(folder);
              const current = this.current();
              if (current !== null && removed.has(current)) void this.router.navigate(['/mail']);
            }, [409]),
        },
      },
    );
  }

  /**
   * A folder changed or removed elsewhere (404, 412), or another status the caller names: reload
   * the folders, then report it.
   */
  private async reloadingOnStale(
    change: () => Promise<void>,
    alsoOn: readonly number[] = [],
  ): Promise<void> {
    try {
      await change();
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.status === 404 || error.status === 412 || alsoOn.includes(error.status))
      ) {
        void this.store.load();
      }
      throw error;
    }
  }

  /** The account's hierarchy delimiter, taken from any of its folders. */
  private delimiterOf(accountId: string): string | undefined {
    const tree = (this.store.trees() ?? []).find((each) => each.account.id === accountId);
    return tree?.folders.find((node) => node.folder.delimiter)?.folder.delimiter;
  }
}

/** INBOX: by role, or by its reserved name (RFC 3501, case-insensitive). */
function isInbox(folder: MailFolderResponse): boolean {
  return folder.role === 'INBOX' || folder.path.toUpperCase() === 'INBOX';
}

/** A folder's name for people: the role's name for role folders, else the folder's own name. */
export function folderLabel(folder: MailFolderResponse): string {
  switch (folder.role) {
    case 'INBOX':
      return $localize`:@@mail.role.inbox:Inbox`;
    case 'DRAFTS':
      return $localize`:@@mail.role.drafts:Drafts`;
    case 'SENT':
      return $localize`:@@mail.role.sent:Sent`;
    case 'ARCHIVE':
      return $localize`:@@mail.role.archive:Archive`;
    case 'SPAM':
      return $localize`:@@mail.role.spam:Spam`;
    case 'TRASH':
      return $localize`:@@mail.role.trash:Trash`;
    default:
      return folder.name;
  }
}
