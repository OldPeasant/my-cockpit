import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MailAccountResponse, MailIdentityResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { EMAIL_PATTERN } from './account-form';
import { MailAccountsService } from './mail-accounts.service';
import { errorMessage } from './messages';

/** What the form edits: a new identity, or an existing one by id. */
type Editing = 'new' | string;

/**
 * The further sending identities of an account (SPEC.md §7.1): From name and address, an optional
 * Reply-To and a signature that replaces the account's. One of them may be the default that new
 * mail starts with. The account's own address is edited with the account.
 */
@Component({
  selector: 'app-mail-identities',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    NgTemplateOutlet,
    ReactiveFormsModule,
  ],
  templateUrl: './identities.html',
  styleUrls: ['./mail-forms.scss', './identities.scss'],
})
export class MailIdentities implements OnInit {
  private readonly service = inject(MailAccountsService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly account = input.required<MailAccountResponse>();

  protected readonly identities = signal<MailIdentityResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** The identity in the form, if the form is open. */
  protected readonly editing = signal<Editing | null>(null);
  /** The identity asked to confirm its removal. */
  protected readonly removing = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);

  protected readonly form = this.fb.group({
    // The server rejects a blank name.
    name: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.pattern(EMAIL_PATTERN)]],
    replyTo: ['', Validators.pattern(EMAIL_PATTERN)],
    signatureMd: [''],
    isDefault: [false],
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.identities.set(null);
    try {
      this.identities.set(await this.service.identities(this.account().id));
    } catch (error) {
      this.loadError.set(errorMessage(error));
    }
  }

  protected add(): void {
    this.open('new');
    this.form.reset({ name: this.account().displayName });
  }

  protected edit(identity: MailIdentityResponse): void {
    this.open(identity.id);
    this.form.reset({
      name: identity.name,
      email: identity.email,
      replyTo: identity.replyTo ?? '',
      signatureMd: identity.signatureMd ?? '',
      isDefault: identity.isDefault,
    });
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected askRemove(identity: MailIdentityResponse): void {
    this.editing.set(null);
    this.error.set(null);
    this.status.set(null);
    this.removing.set(this.removing() === identity.id ? null : identity.id);
  }

  protected async save(): Promise<void> {
    const editing = this.editing();
    if (this.saving() || editing === null) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const body = {
      name: value.name.trim(),
      email: value.email.trim(),
      replyTo: value.replyTo.trim(),
      signatureMd: value.signatureMd.trim() ? value.signatureMd : '',
      isDefault: value.isDefault,
    };
    await this.run(async () => {
      if (editing === 'new') {
        // Absent instead of empty: nothing to remove on a new identity.
        await this.service.createIdentity(this.account().id, {
          ...body,
          replyTo: body.replyTo || undefined,
          signatureMd: body.signatureMd || undefined,
        });
        this.status.set($localize`:@@mailIdentities.added:${body.email}:email: added.`);
      } else {
        await this.service.updateIdentity(this.find(editing), body);
        this.status.set($localize`:@@mailIdentities.saved:${body.email}:email: saved.`);
      }
      this.editing.set(null);
    });
  }

  protected async remove(identity: MailIdentityResponse): Promise<void> {
    await this.run(async () => {
      await this.service.removeIdentity(this.find(identity.id));
      this.removing.set(null);
      this.status.set($localize`:@@mailIdentities.removed:${identity.email}:email: removed.`);
    });
  }

  protected editLabel(identity: MailIdentityResponse): string {
    return $localize`:@@mailIdentities.editAria:Edit ${identity.email}:email:`;
  }

  protected removeLabel(identity: MailIdentityResponse): string {
    return $localize`:@@mailIdentities.removeAria:Remove ${identity.email}:email:`;
  }

  private open(editing: Editing): void {
    this.editing.set(editing);
    this.removing.set(null);
    this.error.set(null);
    this.status.set(null);
  }

  /**
   * Runs a change, then reads the list again: making one identity the default takes the flag
   * (and so a new version) from another.
   */
  private async run(change: () => Promise<void>): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      await change();
    } catch (error) {
      this.error.set(identityError(error));
      return;
    } finally {
      this.saving.set(false);
    }
    await this.refresh();
  }

  /** Reads the identities again without hiding the list; a failure offers a retry. */
  private async refresh(): Promise<void> {
    try {
      this.identities.set(await this.service.identities(this.account().id));
    } catch (error) {
      this.loadError.set(errorMessage(error));
    }
  }

  private find(id: string): MailIdentityResponse {
    const found = this.identities()?.find((i) => i.id === id);
    if (!found) throw new Error(`No identity ${id}`);
    return found;
  }
}

/** A duplicate address is the only conflict of a create or change here. */
function identityError(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) {
    return $localize`:@@mailIdentities.duplicate:This account already has an identity with that address.`;
  }
  return errorMessage(error);
}
