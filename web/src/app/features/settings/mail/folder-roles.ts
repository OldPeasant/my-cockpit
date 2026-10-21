import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MailAccountResponse, MailFolderResponse } from '../../../api/generated';
import { FolderRole, MailAccountsService, RoleChoice } from './mail-accounts.service';
import { errorMessage } from './messages';

export const ROLE_LABELS: Record<FolderRole, string> = {
  INBOX: $localize`:@@mailSettings.role.inbox:Inbox`,
  SENT: $localize`:@@mailSettings.role.sent:Sent`,
  DRAFTS: $localize`:@@mailSettings.role.drafts:Drafts`,
  TRASH: $localize`:@@mailSettings.role.trash:Trash`,
  ARCHIVE: $localize`:@@mailSettings.role.archive:Archive`,
  SPAM: $localize`:@@mailSettings.role.spam:Spam`,
};

const ROLES = Object.keys(ROLE_LABELS) as FolderRole[];

/**
 * Which folder is the Inbox, Sent, Drafts, Trash, Archive and Spam of an account (SPEC.md §7.1):
 * detected by the server, overridable per folder. A role can belong to one folder only, so giving
 * it to one folder takes it from the other.
 */
@Component({
  selector: 'app-mail-folder-roles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatSelectModule],
  templateUrl: './folder-roles.html',
  styleUrl: './folder-roles.scss',
})
export class FolderRoles implements OnInit {
  private readonly service = inject(MailAccountsService);

  readonly account = input.required<MailAccountResponse>();

  protected readonly folders = signal<MailFolderResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly detecting = signal(false);
  /** A role change, and the reload after it, is in flight. */
  protected readonly saving = signal(false);
  protected readonly saveError = signal<{ name: string; message: string } | null>(null);

  protected readonly roles = ROLES;
  protected readonly roleLabels = ROLE_LABELS;

  /** Folders that can hold mail; the others only group subfolders. */
  protected readonly selectable = computed(() => this.folders()?.filter((f) => f.selectable));

  /** Roles no folder has, so the account lacks e.g. an Archive. */
  protected readonly missing = computed(() => {
    const taken = new Set(this.folders()?.map((f) => f.role));
    return ROLES.filter((role) => !taken.has(role)).map((role) => ROLE_LABELS[role]);
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.folders.set(null);
    try {
      this.folders.set(await this.service.folders(this.account().id));
    } catch (error) {
      this.loadError.set(errorMessage(error));
    }
  }

  /** Reads the folder list from the mail server and detects the roles again. */
  protected async detect(): Promise<void> {
    if (this.detecting()) return;
    this.detecting.set(true);
    this.loadError.set(null);
    this.saveError.set(null);
    try {
      this.folders.set(await this.service.syncFolders(this.account().id));
    } catch (error) {
      this.loadError.set(errorMessage(error));
    } finally {
      this.detecting.set(false);
    }
  }

  protected choice(folder: MailFolderResponse): RoleChoice {
    if (!folder.roleOverridden) return 'AUTOMATIC';
    return folder.role ?? 'NONE';
  }

  /** "Automatic (Inbox)" for a detected role, so the user sees what detection chose. */
  protected automaticLabel(folder: MailFolderResponse): string {
    return !folder.roleOverridden && folder.role
      ? $localize`:@@mailSettings.role.automaticIs:Automatic (${ROLE_LABELS[folder.role]}:role:)`
      : $localize`:@@mailSettings.role.automatic:Automatic`;
  }

  protected roleLabel(folder: MailFolderResponse): string {
    return $localize`:@@mailSettings.role.aria:Role of ${folder.path}:path:`;
  }

  protected async setRole(folder: MailFolderResponse, choice: RoleChoice): Promise<void> {
    // One change at a time: moving a role changes another folder's version on the server.
    if (this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      let saved: MailFolderResponse;
      try {
        saved = await this.service.setRole(folder, choice);
      } catch (error) {
        this.saveError.set({ name: folder.path, message: errorMessage(error) });
        await this.load();
        return;
      }
      this.folders.update((all) => all?.map((f) => (f.id === saved.id ? saved : f)) ?? null);
      if (saved.role && saved.role !== folder.role) {
        // The folder that had this role lost it on the server: read them all again.
        await this.refresh();
      }
    } finally {
      this.saving.set(false);
    }
  }

  /** Reads the folders again without hiding the list; a failure offers a retry. */
  private async refresh(): Promise<void> {
    try {
      this.folders.set(await this.service.folders(this.account().id));
    } catch (error) {
      this.loadError.set(errorMessage(error));
    }
  }
}
