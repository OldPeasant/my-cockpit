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
import { AuthService, safeReturnUrl } from '../../../core/auth';
import { ApiError, RETURN_URL_PARAM } from '../../../core/http';
import { AuthLayout } from '../auth-layout';

/** Six digits from the authenticator app (a space in the middle, as some apps show it, is fine). */
const TOTP_PATTERN = /^\s*\d{3}\s?\d{3}\s*$/;

/** Wait when a 429 comes without a usable Retry-After header. */
const DEFAULT_BACKOFF_SECONDS = 60;

/** Login with password and a TOTP or recovery code (SPEC.md §4.1). */
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

  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', [Validators.required, Validators.maxLength(64)]],
    password: ['', [Validators.required, Validators.maxLength(256)]],
    totp: ['', [Validators.required, Validators.pattern(TOTP_PATTERN)]],
    recoveryCode: [{ value: '', disabled: true }, [Validators.required, Validators.maxLength(64)]],
    rememberDevice: [false],
  });

  protected readonly useRecoveryCode = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Seconds left of the login backoff after too many failures (429). */
  protected readonly waitSeconds = signal(0);
  protected readonly blocked = computed(() => this.waitSeconds() > 0);

  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopTimer());
  }

  protected toggleRecoveryCode(): void {
    const recovery = !this.useRecoveryCode();
    this.useRecoveryCode.set(recovery);
    const { totp, recoveryCode } = this.form.controls;
    if (recovery) {
      totp.disable();
      recoveryCode.enable();
    } else {
      recoveryCode.disable();
      totp.enable();
    }
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
      await this.auth.login({
        username: value.username.trim(),
        password: value.password,
        rememberDevice: value.rememberDevice,
        ...(this.useRecoveryCode()
          ? { recoveryCode: value.recoveryCode.trim() }
          : { totp: value.totp.replace(/\s/g, '') }),
      });
      const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get(RETURN_URL_PARAM));
      await this.router.navigateByUrl(returnUrl, { replaceUrl: true });
    } catch (error) {
      this.showError(error);
    } finally {
      this.submitting.set(false);
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
    } else if (error.status === 401) {
      this.error.set(
        $localize`:@@login.error.credentials:Wrong username, password or code. Check them and try again.`,
      );
    } else {
      this.error.set(error.userMessage);
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

export default Login;
