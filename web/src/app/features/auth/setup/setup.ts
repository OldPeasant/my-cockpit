import { Clipboard } from '@angular/cdk/clipboard';
import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { SetupResponse } from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { ApiError, LOGIN_PATH, NotificationService } from '../../../core/http';
import { AuthLayout } from '../auth-layout';
import { QrCode } from './qr-code';

/** Same rules as the server's SetupRequest (letters, digits, `._@-`). */
const USERNAME_PATTERN = /^[\p{L}\p{N}._@-]+$/u;
export const MIN_PASSWORD_LENGTH = 12;
export const RECOVERY_CODES_FILE = 'my-cockpit-recovery-codes.txt';

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const { password, confirm } = group.value as { password: string; confirm: string };
  return confirm && password !== confirm ? { mismatch: true } : null;
}

/**
 * First-run setup (SPEC.md §4.1): create the account, then enroll the authenticator app (QR code,
 * recovery codes) and confirm it with a code. Afterwards the user signs in on the login page.
 */
@Component({
  selector: 'app-setup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AuthLayout,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    QrCode,
    ReactiveFormsModule,
  ],
  templateUrl: './setup.html',
  styleUrls: ['../auth.scss', './setup.scss'],
})
export class Setup {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly clipboard = inject(Clipboard);
  private readonly document = inject(DOCUMENT);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly minPasswordLength = MIN_PASSWORD_LENGTH;

  protected readonly account = this.fb.group(
    {
      username: [
        '',
        [Validators.required, Validators.maxLength(64), Validators.pattern(USERNAME_PATTERN)],
      ],
      password: [
        '',
        [Validators.required, Validators.minLength(MIN_PASSWORD_LENGTH), Validators.maxLength(256)],
      ],
      confirm: ['', Validators.required],
    },
    { validators: passwordsMatch },
  );

  protected readonly confirmation = this.fb.group({
    saved: [false, Validators.requiredTrue],
    code: ['', [Validators.required, Validators.pattern(/^\s*\d{3}\s?\d{3}\s*$/)]],
  });

  /** Set once the account was created: the secret and recovery codes, shown only now. */
  protected readonly enrollment = signal<SetupResponse | null>(null);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly copied = signal(false);

  protected async createAccount(): Promise<void> {
    if (this.submitting()) return;
    if (this.account.invalid) {
      this.account.markAllAsTouched();
      return;
    }
    const { username, password } = this.account.getRawValue();
    await this.run(async () => {
      this.enrollment.set(await this.auth.startSetup({ username: username.trim(), password }));
    });
  }

  protected async confirm(): Promise<void> {
    if (this.submitting()) return;
    if (this.confirmation.invalid) {
      this.confirmation.markAllAsTouched();
      return;
    }
    const code = this.confirmation.getRawValue().code.replace(/\s/g, '');
    await this.run(async () => {
      await this.auth.confirmSetup(code);
      this.notifications.info(
        $localize`:@@setup.done:Your account is ready. Sign in with your password and a code.`,
      );
      await this.router.navigateByUrl(LOGIN_PATH, { replaceUrl: true });
    }, true);
  }

  protected copyCodes(): void {
    this.copied.set(this.clipboard.copy(this.codesText()));
  }

  protected downloadCodes(): void {
    const blob = new Blob([this.codesText()], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = RECOVERY_CODES_FILE;
    link.click();
    URL.revokeObjectURL(url);
  }

  private codesText(): string {
    const codes = this.enrollment()?.recoveryCodes ?? [];
    return `${$localize`:@@setup.codes.fileTitle:My Cockpit recovery codes (each works once)`}\n\n${codes.join('\n')}\n`;
  }

  private async run(action: () => Promise<void>, clearCode = false): Promise<void> {
    this.submitting.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      if (clearCode) this.confirmation.controls.code.reset();
      this.error.set(this.message(error));
    } finally {
      this.submitting.set(false);
    }
  }

  private message(error: ApiError): string {
    if (error.errors.length > 0) return error.errors.map((e) => e.message).join(' ');
    if (error.status === 400 && this.enrollment()) {
      return $localize`:@@setup.error.code:That code is not valid. Enter the current code from the app.`;
    }
    return error.userMessage;
  }
}

export default Setup;
