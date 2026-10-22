import { Injectable } from '@angular/core';
import { CreationOptionsJson, RequestOptionsJson } from '../../../api/generated';

/** Why a passkey ceremony in the browser did not produce a credential. */
export type PasskeyFailure =
  /** The browser has no WebAuthn (or not in this context, e.g. plain HTTP on another host). */
  | 'unsupported'
  /** The user closed the prompt, it timed out, or no matching passkey was chosen. */
  | 'cancelled'
  /** Registration: this authenticator already holds a passkey for the account. */
  | 'duplicate'
  /** Anything else the browser or authenticator refused (wrong origin, no user verification, ...). */
  | 'failed';

/** A WebAuthn ceremony that ended in the browser, before anything was sent to the server. */
export class PasskeyError extends Error {
  override readonly name = 'PasskeyError';

  constructor(
    readonly failure: PasskeyFailure,
    cause?: unknown,
  ) {
    super(`passkey ceremony ${failure}`, { cause });
  }
}

/** The created or asserted credential as the server reads it (`RegistrationResponseJSON` / `AuthenticationResponseJSON`). */
export type CredentialJson = Record<string, unknown>;

/**
 * The browser side of passkeys (`navigator.credentials`): turns the server's JSON options into the
 * binary form WebAuthn wants and the resulting credential back into JSON. Converted by hand instead
 * of `PublicKeyCredential.parseCreationOptionsFromJSON()`/`toJSON()`, which older browsers lack.
 * Rejects only with a {@link PasskeyError}.
 */
@Injectable({ providedIn: 'root' })
export class WebAuthn {
  /** Whether this browser can create and use passkeys at all. */
  supported(): boolean {
    return (
      typeof globalThis.PublicKeyCredential === 'function' &&
      typeof globalThis.navigator?.credentials?.create === 'function'
    );
  }

  /** Creates a passkey for the options of `startPasskeyRegistration`. */
  async create(options: CreationOptionsJson): Promise<CredentialJson> {
    const credential = await this.ceremony(() =>
      navigator.credentials.create({
        publicKey: {
          rp: options.rp,
          user: { ...options.user, id: fromBase64Url(options.user.id) },
          challenge: fromBase64Url(options.challenge),
          pubKeyCredParams: options.pubKeyCredParams,
          timeout: options.timeout,
          excludeCredentials: options.excludeCredentials.map(descriptor),
          authenticatorSelection: options.authenticatorSelection,
          attestation: options.attestation,
        },
      }),
    );
    const response = credential.response as AuthenticatorAttestationResponse;
    return credentialJson(credential, {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      attestationObject: toBase64Url(response.attestationObject),
      transports: response.getTransports?.() ?? [],
    });
  }

  /** Signs the challenge of `startPasskeyAuthentication` with a passkey the user picks. */
  async get(options: RequestOptionsJson): Promise<CredentialJson> {
    const credential = await this.ceremony(() =>
      navigator.credentials.get({
        publicKey: {
          challenge: fromBase64Url(options.challenge),
          rpId: options.rpId,
          timeout: options.timeout,
          allowCredentials: options.allowCredentials.map(descriptor),
          userVerification: options.userVerification,
        },
      }),
    );
    const response = credential.response as AuthenticatorAssertionResponse;
    return credentialJson(credential, {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      authenticatorData: toBase64Url(response.authenticatorData),
      signature: toBase64Url(response.signature),
      userHandle: response.userHandle ? toBase64Url(response.userHandle) : null,
    });
  }

  private async ceremony(run: () => Promise<Credential | null>): Promise<PublicKeyCredential> {
    if (!this.supported()) throw new PasskeyError('unsupported');
    let credential: Credential | null;
    try {
      credential = await run();
    } catch (error) {
      throw new PasskeyError(failureOf(error), error);
    }
    if (!credential) throw new PasskeyError('cancelled');
    return credential as PublicKeyCredential;
  }
}

function credentialJson(
  credential: PublicKeyCredential,
  response: Record<string, unknown>,
): CredentialJson {
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? null,
    response,
    clientExtensionResults: credential.getClientExtensionResults?.() ?? {},
  };
}

function descriptor(json: {
  id: string;
  type: 'public-key';
  transports?: string[];
}): PublicKeyCredentialDescriptor {
  return {
    type: json.type,
    id: fromBase64Url(json.id),
    ...(json.transports ? { transports: json.transports as AuthenticatorTransport[] } : {}),
  };
}

/** WebAuthn reports what went wrong only through the DOMException name. */
function failureOf(error: unknown): PasskeyFailure {
  switch ((error as { name?: unknown } | null)?.name) {
    case 'NotAllowedError':
    case 'AbortError':
      return 'cancelled';
    case 'InvalidStateError':
      return 'duplicate';
    case 'NotSupportedError':
      return 'unsupported';
    default:
      return 'failed';
  }
}

export function toBase64Url(buffer: ArrayBuffer): string {
  let binary = '';
  for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}
