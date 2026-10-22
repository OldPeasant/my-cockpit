import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router } from '@angular/router';
import { TotpEnrollmentResponse } from '../../../api/generated';
import { AuthService, safeReturnUrl } from '../../../core/auth';
import { ApiError, LOGIN_PATH, NotificationService, RETURN_URL_PARAM } from '../../../core/http';
import { AuthLayout } from '../auth-layout';
import { QrCode } from '../setup/qr-code';

/**
 * Forced TOTP re-enrollment (SPEC.md §10.2): a restore without the backup passphrase drops the
 * TOTP secret, so the session of the next login may only set up a new one. Shows the new secret
 * (QR code and key), confirms it with a code, then opens the app.
 */
@Component({
  selector: 'app-totp-enrollment',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AuthLayout,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    QrCode,
    ReactiveFormsModule,
  ],
  templateUrl: './totp-enrollment.html',
  styleUrls: ['../auth.scss', '../setup/setup.scss'],
})
export class TotpEnrollment implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly notifications = inject(NotificationService);

  protected readonly form = inject(NonNullableFormBuilder).group({
    code: ['', [Validators.required, Validators.pattern(/^\s*\d{3}\s?\d{3}\s*$/)]],
  });

  /** The new secret, once the server created it. */
  protected readonly enrollment = signal<TotpEnrollmentResponse | null>(null);
  protected readonly starting = signal(false);
  protected readonly submitting = signal(false);
  /** Starting failed: the page offers to try again. */
  protected readonly startError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    void this.start();
  }

  /** Asks the server for a new secret; this replaces one started before (e.g. in another tab). */
  protected async start(): Promise<void> {
    if (this.starting()) return;
    this.starting.set(true);
    this.startError.set(null);
    this.error.set(null);
    try {
      this.enrollment.set(await this.auth.startTotpEnrollment());
      this.form.reset();
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      this.enrollment.set(null);
      this.startError.set(error.userMessage);
    } finally {
      this.starting.set(false);
    }
  }

  protected async confirm(): Promise<void> {
    if (this.submitting() || !this.enrollment()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const code = this.form.getRawValue().code.replace(/\s/g, '');
    this.submitting.set(true);
    this.error.set(null);
    try {
      await this.auth.confirmTotpEnrollment(code);
      this.notifications.info(
        $localize`:@@totpEnrollment.done:Your authenticator app is set up again.`,
      );
      const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get(RETURN_URL_PARAM));
      await this.router.navigateByUrl(returnUrl, { replaceUrl: true });
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      // A code is good for one attempt only.
      this.form.controls.code.reset();
      this.error.set(this.message(error));
    } finally {
      this.submitting.set(false);
    }
  }

  protected async signOut(): Promise<void> {
    try {
      await this.auth.logout();
    } catch {
      // The local session is cleared anyway; the interceptor showed the failure.
    }
    await this.router.navigateByUrl(LOGIN_PATH, { replaceUrl: true });
  }

  private message(error: ApiError): string {
    if (error.errors.length > 0) return error.errors.map((e) => e.message).join(' ');
    if (error.status === 429) {
      return error.retryAfter === undefined
        ? $localize`:@@totpEnrollment.error.throttled:Too many failed attempts. Try again later.`
        : $localize`:@@totpEnrollment.error.throttledWait:Too many failed attempts. Try again in ${error.retryAfter}:seconds: s.`;
    }
    return error.userMessage;
  }
}

export default TotpEnrollment;
