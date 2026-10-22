import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService, safeReturnUrl, totpEnrollmentUrl } from '../../../core/auth';
import { LoginRequest, MeResponse } from '../../../api/generated';
import { ApiError, RETURN_URL_PARAM } from '../../../core/http';
import { AuthLayout } from '../auth-layout';
import { PasskeyLogin } from '../passkey/passkey-login.service';
import { PasskeyError } from '../passkey/webauthn';

/** Six digits from the authenticator app (a space in the middle, as some apps show it, is fine). */
const TOTP_PATTERN = /^\s*\d{3}\s?\d{3}\s*$/;

/** Wait when a 429 comes without a usable Retry-After header. */
const DEFAULT_BACKOFF_SECONDS = 60;

/**
 * The second factor sent with the password. `password` sends none: after a restore without
 * secrets the server accepts the password alone while no unused recovery code is left (SPEC.md §10.2).
 */
export type SecondFactor = 'totp' | 'recovery' | 'password';

/**
 * Login with password and a TOTP or recovery code, or with a passkey (SPEC.md §4.1). A session that must set up TOTP
 * again (after a restore, SPEC.md §10.2) goes to the enrollment page instead of the app.
 */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AuthLayout,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  templateUrl: './login.html',
  styleUrl: '../auth.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly passkeys = inject(PasskeyLogin);

  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', [Validators.required, Validators.maxLength(64)]],
    password: ['', [Validators.required, Validators.maxLength(256)]],
    totp: ['', [Validators.required, Validators.pattern(TOTP_PATTERN)]],
    recoveryCode: [{ value: '', disabled: true }, [Validators.required, Validators.maxLength(64)]],
    rememberDevice: [false],
  });

  protected readonly factor = signal<SecondFactor>('totp');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Seconds left of the login backoff after too many failures (429). */
  protected readonly waitSeconds = signal(0);
  protected readonly blocked = computed(() => this.waitSeconds() > 0);
  /** Browsers without WebAuthn don't offer the passkey button at all. */
  protected readonly passkeySupported = this.passkeys.supported();

  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopTimer());
  }

  /** From the authenticator code to a recovery code, and from anything else back to the app. */
  protected toggleRecoveryCode(): void {
    this.useFactor(this.factor() === 'totp' ? 'recovery' : 'totp');
  }

  protected useFactor(factor: SecondFactor): void {
    this.factor.set(factor);
    const { totp, recoveryCode } = this.form.controls;
    if (factor === 'totp') totp.enable();
    else totp.disable();
    if (factor === 'recovery') recoveryCode.enable();
    else recoveryCode.disable();
    this.error.set(null);
  }

  protected async submit(): Promise<void> {
    if (this.submitting() || this.blocked()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    try {
      const me = await this.auth.login({
        username: value.username.trim(),
        password: value.password,
        rememberDevice: value.rememberDevice,
        ...this.secondFactor(value.totp, value.recoveryCode),
      });
      await this.enter(me);
    } catch (error) {
      this.showError(error);
    } finally {
      this.submitting.set(false);
    }
  }

  /** Signs in with a passkey alone; username, password and code are not needed. */
  protected async signInWithPasskey(): Promise<void> {
    if (this.submitting() || this.blocked()) return;
    this.submitting.set(true);
    this.error.set(null);
    try {
      await this.enter(await this.passkeys.login(this.form.controls.rememberDevice.value));
    } catch (error) {
      this.showPasskeyError(error);
    } finally {
      this.submitting.set(false);
    }
  }

  private async enter(me: MeResponse): Promise<void> {
    const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get(RETURN_URL_PARAM));
    await this.router.navigateByUrl(
      me.totpEnrollmentRequired ? totpEnrollmentUrl(this.router, returnUrl) : returnUrl,
      { replaceUrl: true },
    );
  }

  private secondFactor(totp: string, recoveryCode: string): Partial<LoginRequest> {
    switch (this.factor()) {
      case 'totp':
        return { totp: totp.replace(/\s/g, '') };
      case 'recovery':
        return { recoveryCode: recoveryCode.trim() };
      case 'password':
        return {};
    }
  }

  private showError(error: unknown): void {
    if (!(error instanceof ApiError)) throw error;
    // A code is good for one attempt only, so clear it; keep username and password.
    this.form.controls.totp.reset();
    this.form.controls.recoveryCode.reset();
    if (error.status === 429) {
      this.error.set(null);
      this.startBackoff(error.retryAfter ?? DEFAULT_BACKOFF_SECONDS);
    } else if (error.status === 401 && this.factor() === 'password') {
      this.error.set(
        $localize`:@@login.error.passwordOnly:Wrong username or password, or a recovery code is still needed. Use one of your recovery codes.`,
      );
    } else if (error.status === 401) {
      this.error.set(
        $localize`:@@login.error.credentials:Wrong username, password or code. Check them and try again.`,
      );
    } else {
      this.error.set(error.userMessage);
    }
  }

  private showPasskeyError(error: unknown): void {
    if (error instanceof PasskeyError) {
      this.error.set(passkeyFailureMessage(error));
    } else if (error instanceof ApiError && error.status === 429) {
      this.error.set(null);
      this.startBackoff(error.retryAfter ?? DEFAULT_BACKOFF_SECONDS);
    } else if (error instanceof ApiError && error.status === 401) {
      this.error.set(
        $localize`:@@login.passkey.rejected:This passkey was not accepted. It may have been deleted; sign in with your password and code instead.`,
      );
    } else if (error instanceof ApiError) {
      this.error.set(error.userMessage);
    } else {
      throw error;
    }
  }

  private startBackoff(seconds: number): void {
    this.stopTimer();
    this.waitSeconds.set(seconds);
    this.timer = setInterval(() => {
      const left = Math.max(this.waitSeconds() - 1, 0);
      this.waitSeconds.set(left);
      if (left === 0) this.stopTimer();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
  }
}

function passkeyFailureMessage(error: PasskeyError): string {
  switch (error.failure) {
    case 'cancelled':
      return $localize`:@@login.passkey.cancelled:No passkey was used. Try again, or sign in with your password and code.`;
    case 'unsupported':
      return $localize`:@@login.passkey.unsupported:This browser cannot use passkeys here. Sign in with your password and code.`;
    default:
      return $localize`:@@login.passkey.failed:The passkey could not be used. Try again, or sign in with your password and code.`;
  }
}

export default Login;
