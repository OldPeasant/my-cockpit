import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  deletePasskey,
  DeviceResponse,
  finishPasskeyRegistration,
  listDevices,
  listPasskeys,
  PasskeyResponse,
  regenerateRecoveryCodes,
  revokeDevice,
  startPasskeyRegistration,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';
import { WebAuthn } from '../../auth/passkey/webauthn';

/**
 * Devices, passkeys and recovery codes of the owner account (SPEC.md §4.1). Errors are not toasted: the
 * security section shows them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class SecurityService {
  private readonly api = inject(Api);
  private readonly webAuthn = inject(WebAuthn);

  /** Devices logged in with tokens (the Android app), oldest first. */
  devices(): Promise<DeviceResponse[]> {
    return this.api.invoke(listDevices, undefined, silent());
  }

  /** Revokes a device: its tokens stop working at once. */
  async revoke(id: string): Promise<void> {
    await this.api.invoke(revokeDevice, { id }, silent());
  }

  /** Registered passkeys, oldest first. */
  passkeys(): Promise<PasskeyResponse[]> {
    return this.api.invoke(listPasskeys, undefined, silent());
  }

  /** Whether this browser can create passkeys at all. */
  passkeysSupported(): boolean {
    return this.webAuthn.supported();
  }

  /**
   * Adds a passkey: a current TOTP code starts the ceremony, the browser creates the credential and
   * the server stores it under [name]. Rejects with a {@link PasskeyError} when the browser ceremony
   * ends without one, else with an {@link ApiError}.
   */
  async addPasskey(name: string, totp: string): Promise<PasskeyResponse> {
    const options = await this.api.invoke(startPasskeyRegistration, { body: { totp } }, silent());
    const credential = await this.webAuthn.create(options.publicKey);
    return this.api.invoke(
      finishPasskeyRegistration,
      { body: { ceremonyId: options.ceremonyId, credential, name } },
      silent(),
    );
  }

  /** Deletes a passkey: it can no longer be used to log in. */
  async deletePasskey(id: string): Promise<void> {
    await this.api.invoke(deletePasskey, { id }, silent());
  }

  /** Replaces all recovery codes; the new ones are returned only now. */
  async regenerateRecoveryCodes(totp: string): Promise<string[]> {
    const response = await this.api.invoke(regenerateRecoveryCodes, { body: { totp } }, silent());
    return response.recoveryCodes;
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
