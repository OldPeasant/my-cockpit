import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http';

/** Longest name the server accepts. */
export const MAX_NAME_LENGTH = 255;

export interface NameDialogData {
  readonly title: string;
  readonly label: string;
  readonly confirm: string;
  readonly name?: string;
  /** Saves the name; a rejection keeps the dialog open and shows the error. */
  readonly save: (name: string) => Promise<void>;
}

/** A name the server would reject for its characters (it trims, so blanks count as empty). */
export function nameError(name: string): ValidationErrors | null {
  const trimmed = name.trim();
  if (!trimmed) return { required: true };
  if (trimmed === '.' || trimmed === '..' || /[/\\]/.test(trimmed)) return { invalidName: true };
  return null;
}

/**
 * Asks for the name of a new folder or the new name of an entry and saves it. Closes with `true`
 * once saved; a server error (e.g. the name is taken) stays in the dialog.
 */
@Component({
  selector: 'app-files-name-dialog',
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
          <mat-label>{{ data.label }}</mat-label>
          <input
            matInput
            cdkFocusInitial
            autocomplete="off"
            [maxlength]="maxLength"
            [formControl]="name"
          />
          @if (name.touched && name.hasError('required')) {
            <mat-error i18n="@@files.name.required">Enter a name.</mat-error>
          } @else if (name.touched && name.hasError('invalidName')) {
            <mat-error i18n="@@files.name.invalid"
              >A name cannot contain / or \\, or be . or ..</mat-error
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
          i18n="@@files.cancel"
        >
          Cancel
        </button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ data.confirm }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    mat-form-field {
      width: min(28rem, 100%);
    }
    .error {
      margin: var(--app-space-2) 0 0;
      max-width: 28rem;
      color: var(--app-danger);
    }
  `,
})
export class NameDialog {
  protected readonly data = inject<NameDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<NameDialog, boolean>>(MatDialogRef);

  protected readonly maxLength = MAX_NAME_LENGTH;
  protected readonly name = new FormControl(this.data.name ?? '', {
    nonNullable: true,
    validators: [
      Validators.maxLength(MAX_NAME_LENGTH),
      (control) => nameError(control.value as string),
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
    // The save may be committed even if its answer never arrives here: no closing until it has.
    this.ref.disableClose = true;
    try {
      await this.data.save(name);
      this.ref.close(true);
    } catch (error) {
      this.error.set(
        error instanceof ApiError
          ? (error.errors[0]?.message ?? error.userMessage)
          : $localize`:@@files.error:Something went wrong.`,
      );
    } finally {
      this.ref.disableClose = false;
      this.saving.set(false);
    }
  }
}
