import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  loginWithPasskey,
  MeResponse,
  startPasskeyAuthentication,
} from '../../../api/generated';
import { AuthService } from '../../../core/auth';
import { SILENT_ERRORS, SKIP_LOGIN_REDIRECT } from '../../../core/http';
import { WebAuthn } from './webauthn';

/**
 * Web login with a passkey instead of password + TOTP (SPEC.md §4.1): the server's challenge, the
 * browser's passkey prompt, then the session cookie. The login form shows the errors itself.
 */
@Injectable({ providedIn: 'root' })
export class PasskeyLogin {
  private readonly api = inject(Api);
  private readonly webAuthn = inject(WebAuthn);
  private readonly auth = inject(AuthService);

  supported(): boolean {
    return this.webAuthn.supported();
  }

  /**
   * Logs in; rejects with a {@link PasskeyError} when the browser ceremony ends without a passkey,
   * or an {@link ApiError} (401 passkey not accepted, 429 with `retryAfter`).
   */
  async login(rememberDevice: boolean): Promise<MeResponse> {
    const options = await this.api.invoke(startPasskeyAuthentication, undefined, quiet());
    const credential = await this.webAuthn.get(options.publicKey);
    const me = await this.api.invoke(
      loginWithPasskey,
      { body: { ceremonyId: options.ceremonyId, credential, rememberDevice } },
      quiet(),
    );
    // The session cache of AuthService learns about the new session from the server.
    await this.auth.checkSession(true);
    return me;
  }
}

function quiet(): HttpContext {
  return new HttpContext().set(SKIP_LOGIN_REDIRECT, true).set(SILENT_ERRORS, true);
}
