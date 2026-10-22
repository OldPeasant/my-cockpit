import { HttpContext } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import {
  Api,
  confirmSetupTotp,
  confirmTotpEnrollment,
  getCurrentUser,
  getSetupStatus,
  login,
  LoginRequest,
  logout,
  MeResponse,
  SetupRequest,
  SetupResponse,
  startSetup,
  startTotpEnrollment,
  TotpEnrollmentResponse,
} from '../../api/generated';
import { ApiError, SILENT_ERRORS, SKIP_LOGIN_REDIRECT } from '../http';

/** A 401 of an auth call is an answer, not an expired session: never redirect to login. */
function noRedirect(): HttpContext {
  return new HttpContext().set(SKIP_LOGIN_REDIRECT, true);
}

/** Form submissions: the form shows the error itself, so no toast either. */
function quiet(): HttpContext {
  return noRedirect().set(SILENT_ERRORS, true);
}

/**
 * The web session (SPEC.md §4.1): who is logged in, the first-run setup, login and logout.
 * The session itself is the server's HttpOnly cookie; this only caches what the server said.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(Api);

  /** `undefined` until the server was asked, `null` when not logged in. */
  private readonly current = signal<MeResponse | null | undefined>(undefined);
  /** `undefined` until the server was asked. */
  private setupRequiredCache: boolean | undefined;

  readonly user = computed(() => this.current() ?? null);
  readonly loggedIn = computed(() => !!this.current());
  /**
   * The session may only set up TOTP again (after a restore without secrets, SPEC.md §10.2):
   * every other endpoint answers 403 until {@link confirmTotpEnrollment} succeeds.
   */
  readonly totpEnrollmentRequired = computed(() => !!this.current()?.totpEnrollmentRequired);

  /** Whether the first-run setup still has to be completed (asked once, until setup completes). */
  async setupRequired(): Promise<boolean> {
    if (this.setupRequiredCache === undefined) {
      const status = await this.api.invoke(getSetupStatus, undefined, noRedirect());
      this.setupRequiredCache = status.setupRequired;
    }
    return this.setupRequiredCache;
  }

  /**
   * Whether a session exists. Asks the server on first use (or with `refresh`, e.g. on the login
   * page, which a 401 of an expired session leads to), then trusts the cached answer.
   */
  async checkSession(refresh = false): Promise<boolean> {
    if (refresh || this.current() === undefined) {
      try {
        this.current.set(await this.api.invoke(getCurrentUser, undefined, noRedirect()));
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error;
        this.current.set(null);
      }
    }
    return this.loggedIn();
  }

  /** Logs in; rejects with an {@link ApiError} (401 wrong credentials, 429 with `retryAfter`). */
  async login(request: LoginRequest): Promise<MeResponse> {
    const me = await this.api.invoke(login, { body: request }, quiet());
    this.current.set(me);
    return me;
  }

  /**
   * Ends the session on the server; the local state is cleared even when that call fails
   * (the error is shown as a toast and rethrown).
   */
  async logout(): Promise<void> {
    try {
      await this.api.invoke(logout, undefined, noRedirect());
    } finally {
      this.current.set(null);
    }
  }

  /** Creates the pending account; the result (TOTP secret, recovery codes) is shown only once. */
  startSetup(request: SetupRequest): Promise<SetupResponse> {
    return this.api.invoke(startSetup, { body: request }, quiet());
  }

  /** Activates the account with a code from the authenticator app; setup is then complete. */
  async confirmSetup(code: string): Promise<void> {
    await this.api.invoke(confirmSetupTotp, { body: { code } }, quiet());
    this.setupRequiredCache = false;
  }

  /** Creates a new TOTP secret for the re-enrollment; each call replaces the previous one. */
  startTotpEnrollment(): Promise<TotpEnrollmentResponse> {
    return this.api.invoke(startTotpEnrollment, undefined, quiet());
  }

  /** Confirms the new secret with a code; the session then has full access. */
  async confirmTotpEnrollment(code: string): Promise<void> {
    await this.api.invoke(confirmTotpEnrollment, { body: { code } }, quiet());
    const me = this.current();
    if (me) this.current.set({ ...me, totpEnrollmentRequired: false });
  }
}
