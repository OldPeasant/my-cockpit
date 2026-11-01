import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http';

/** Longest folder name the server accepts (`CreateMailFolderRequest.name`). */
export const MAX_FOLDER_NAME_LENGTH = 200;

/**
 * What a failed folder change tells the owner, by status: the server's texts are English, these
 * follow the UI language. Unknown statuses fall back to the server's text.
 */
export function folderErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return $localize`:@@mail.folder.error.unknown:Something went wrong.`;
  }
  switch (error.status) {
    case 0:
      return $localize`:@@mail.folder.error.unreachable:The server cannot be reached. Check the connection and try again.`;
    case 400:
      return $localize`:@@mail.folder.error.invalid:The mail server does not accept this name.`;
    case 404:
    case 412:
      return $localize`:@@mail.folder.error.stale:The folder was changed or removed elsewhere. The folder list was reloaded; try again.`;
    case 409:
      return $localize`:@@mail.folder.error.conflict:A folder with this name already exists here.`;
    case 502:
      return $localize`:@@mail.folder.error.mailServer:The mail server could not be reached or refused the change.`;
    default:
      return error.userMessage;
  }
}

export interface MailFolderNameDialogData {
  readonly title: string;
  readonly confirm: string;
  /** The current name when renaming. */
  readonly name?: string;
  /** The server's hierarchy delimiter, which a name cannot contain. */
  readonly delimiter?: string;
  /** Saves the name; a rejection keeps the dialog open and shows the error. */
  readonly save: (name: string) => Promise<void>;
}

/**
 * Asks for the name of a new folder or the new name of a folder and saves it. Closes with `true`
 * once saved; a server error (name taken, invalid, server unreachable) stays in the dialog.
 */
@Component({
  selector: 'app-mail-folder-name-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content>
        <mat-form-field subscriptSizing="dynamic">
          <mat-label i18n="@@mail.folder.name">Folder name</mat-label>
          <input
            matInput
            cdkFocusInitial
            autocomplete="off"
            [maxlength]="maxLength"
            [formControl]="name"
          />
          @if (name.touched && name.hasError('required')) {
            <mat-error i18n="@@mail.folder.name.required">Enter a name.</mat-error>
          } @else if (name.touched && name.hasError('delimiter')) {
            <mat-error i18n="@@mail.folder.name.delimiter"
              >A name cannot contain {{ data.delimiter }}.</mat-error
            >
          }
        </mat-form-field>
        @if (error(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button
          mat-button
          type="button"
          mat-dialog-close
          [disabled]="saving()"
          i18n="@@mail.folder.cancel"
        >
          Cancel
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ data.confirm }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    mat-form-field {
      width: min(24rem, 100%);
    }
    .error {
      max-width: 24rem;
      margin: var(--app-space-2) 0 0;
      color: var(--app-danger);
    }
  `,
})
export class MailFolderNameDialog {
  protected readonly data = inject<MailFolderNameDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<MailFolderNameDialog, boolean>>(MatDialogRef);

  protected readonly maxLength = MAX_FOLDER_NAME_LENGTH;
  protected readonly name = new FormControl(this.data.name ?? '', {
    nonNullable: true,
    validators: [
      Validators.maxLength(MAX_FOLDER_NAME_LENGTH),
      (control) => this.nameError(control.value as string),
    ],
  });
  protected readonly form = new FormGroup({ name: this.name });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    this.name.markAsTouched();
    if (this.name.invalid || this.saving()) return;
    const name = this.name.value.trim();
    if (name === this.data.name) {
      this.ref.close(false);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    // The change may be made on the mail server even if its answer never arrives: no closing until then.
    this.ref.disableClose = true;
    try {
      await this.data.save(name);
      this.ref.close(true);
    } catch (error) {
      this.error.set(folderErrorMessage(error));
    } finally {
      this.ref.disableClose = false;
      this.saving.set(false);
    }
  }

  private nameError(name: string): ValidationErrors | null {
    const trimmed = name.trim();
    if (!trimmed) return { required: true };
    if (this.data.delimiter && trimmed.includes(this.data.delimiter)) return { delimiter: true };
    return null;
  }
}

export interface MailFolderDeleteDialogData {
  /** The folder's name as shown in the tree. */
  readonly name: string;
  /** How many folders lie below it (they are deleted too). */
  readonly subfolders: number;
  /** Deletes the folder; a rejection keeps the dialog open and shows the error. */
  readonly remove: () => Promise<void>;
}

/**
 * Asks before deleting a folder, saying that its messages and subfolders go on the mail server too,
 * and deletes it. Closes with `true` once deleted; a server error stays in the dialog.
 */
@Component({
  selector: 'app-mail-folder-delete-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title i18n="@@mail.folder.delete.title">Delete “{{ data.name }}”?</h2>
    <mat-dialog-content>
      <p class="consequence">
        @if (data.subfolders === 0) {
          <ng-container i18n="@@mail.folder.delete.text"
            >The folder and all its messages are deleted on the mail server too. This cannot be
            undone.</ng-container
          >
        } @else {
          <ng-container i18n="@@mail.folder.delete.textWithSubfolders"
            >The folder, its
            {data.subfolders, plural, =1 {subfolder} other {{{ data.subfolders }} subfolders}} and
            all their messages are deleted on the mail server too. This cannot be
            undone.</ng-container
          >
        }
      </p>
      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        mat-button
        type="button"
        mat-dialog-close
        cdkFocusInitial
        [disabled]="deleting()"
        i18n="@@mail.folder.cancel"
      >
        Cancel
      </button>
      <button
        mat-flat-button
        type="button"
        class="danger"
        [disabled]="deleting()"
        (click)="confirm()"
        i18n="@@mail.folder.delete.confirm"
      >
        Delete folder
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    p {
      max-width: 44ch;
      margin: 0;
    }
    .error {
      margin-top: var(--app-space-2);
      color: var(--app-danger);
    }
    .danger {
      --mat-button-filled-container-color: var(--app-danger);
    }
  `,
})
export class MailFolderDeleteDialog {
  protected readonly data = inject<MailFolderDeleteDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<MailFolderDeleteDialog, boolean>>(MatDialogRef);

  protected readonly deleting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async confirm(): Promise<void> {
    if (this.deleting()) return;
    this.deleting.set(true);
    this.error.set(null);
    this.ref.disableClose = true;
    try {
      await this.data.remove();
      this.ref.close(true);
    } catch (error) {
      this.error.set(folderErrorMessage(error));
    } finally {
      this.ref.disableClose = false;
      this.deleting.set(false);
    }
  }
}
