import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MailAccountResponse, UpdateMailAccountRequest } from '../../../api/generated';
import { EMAIL_PATTERN, MAX_SYNC_WINDOW_MONTHS, serverForm, serverValue } from './account-form';
import { MailAccountsService } from './mail-accounts.service';
import { errorMessage } from './messages';
import { ServerFields } from './server-fields';

/**
 * Changes an account: its identity (name and address), signature, how much mail is cached and,
 * for IMAP/SMTP, the login and servers. A blank password keeps the stored one.
 */
@Component({
  selector: 'app-mail-account-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule, ServerFields],
  templateUrl: './account-editor.html',
  styleUrl: './mail-forms.scss',
})
export class AccountEditor implements OnInit {
  private readonly service = inject(MailAccountsService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly account = input.required<MailAccountResponse>();
  /** The account as saved. */
  readonly saved = output<MailAccountResponse>();
  readonly cancelled = output<void>();

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly imapSmtp = computed(() => this.account().type === 'IMAP_SMTP');
  protected readonly maxMonths = MAX_SYNC_WINDOW_MONTHS;

  protected readonly form = this.fb.group({
    displayName: ['', Validators.maxLength(200)],
    emailAddress: ['', [Validators.required, Validators.pattern(EMAIL_PATTERN)]],
    username: [''],
    password: [''],
    imap: serverForm(this.fb, 'imap'),
    smtp: serverForm(this.fb, 'smtp'),
    signatureMd: [''],
    syncWindowMonths: [
      12,
      [Validators.required, Validators.min(1), Validators.max(MAX_SYNC_WINDOW_MONTHS)],
    ],
  });

  ngOnInit(): void {
    const account = this.account();
    this.form.reset({
      displayName: account.displayName,
      emailAddress: account.emailAddress,
      username: account.username,
      password: '',
      imap: account.imap,
      smtp: account.smtp,
      signatureMd: account.signatureMd ?? '',
      syncWindowMonths: account.syncWindowMonths,
    });
    if (this.imapSmtp()) {
      this.form.controls.username.addValidators(Validators.required);
    } else {
      // The login and servers of a Google/Microsoft account come from the provider.
      this.form.controls.imap.disable();
      this.form.controls.smtp.disable();
    }
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      this.saved.emit(await this.service.update(this.account(), this.body()));
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  private body(): UpdateMailAccountRequest {
    const value = this.form.getRawValue();
    const body: UpdateMailAccountRequest = {
      displayName: value.displayName.trim() || value.emailAddress.trim(),
      emailAddress: value.emailAddress.trim(),
      signatureMd: value.signatureMd.trim() ? value.signatureMd : '',
      syncWindowMonths: Number(value.syncWindowMonths),
    };
    if (!this.imapSmtp()) return body;
    return {
      ...body,
      username: value.username.trim(),
      imap: serverValue(this.form.controls.imap),
      smtp: serverValue(this.form.controls.smtp),
      ...(value.password ? { password: value.password } : {}),
    };
  }
}
