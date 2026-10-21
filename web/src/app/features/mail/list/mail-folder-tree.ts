import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MailFolderResponse } from '../../../api/generated';
import { MailFoldersStore } from './mail-folders-store';

/**
 * The unified Inbox and the folder tree of every account. Each folder is a link to its list;
 * folders that only group others are plain text.
 */
@Component({
  selector: 'app-mail-folder-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
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
        <section class="account" [attr.aria-label]="tree.account.displayName">
          <h2 class="account-name" [title]="tree.account.emailAddress">
            {{ tree.account.displayName }}
          </h2>
          <ul>
            @for (node of tree.folders; track node.folder.id) {
              <li [style.--depth]="node.depth">
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
              </li>
            }
          </ul>
        </section>
      }
    </nav>
  `,
  styleUrl: './mail-folder-tree.scss',
})
export class MailFolderTree {
  protected readonly store = inject(MailFoldersStore);

  /** The shown folder; null for the unified Inbox. */
  readonly current = input<string | null>(null);

  protected label(folder: MailFolderResponse): string {
    return folderLabel(folder);
  }
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
