import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  DeviceResponse,
  listDevices,
  regenerateRecoveryCodes,
  revokeDevice,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/**
 * Devices and recovery codes of the owner account (SPEC.md §4.1). Errors are not toasted: the
 * security section shows them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class SecurityService {
  private readonly api = inject(Api);

  /** Devices logged in with tokens (the Android app), oldest first. */
  devices(): Promise<DeviceResponse[]> {
    return this.api.invoke(listDevices, undefined, silent());
  }

  /** Revokes a device: its tokens stop working at once. */
  async revoke(id: string): Promise<void> {
    await this.api.invoke(revokeDevice, { id }, silent());
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
