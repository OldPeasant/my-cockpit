import { Clipboard } from '@angular/cdk/clipboard';
import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  inject,
  Injector,
  OnInit,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { DeviceResponse, PasskeyResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { PasskeyError } from '../../auth/passkey/webauthn';
import { DeletePasskeyDialog } from './delete-passkey-dialog';
import { RevokeDeviceDialog } from './revoke-device-dialog';
import { SecurityService } from './security.service';

/**
 * Settings → Security (SPEC.md §4.1): the devices signed in with the Android app, each revocable
 * after a confirmation, the passkeys (added with a current TOTP code, deleted after a confirmation),
 * and new recovery codes for a current TOTP code, shown only once.
 */
@Component({
  selector: 'app-security-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  templateUrl: './security-settings.html',
  styleUrl: './security-settings.scss',
})
export default class SecuritySettings implements OnInit {
  private readonly service = inject(SecurityService);
  private readonly dialog = inject(MatDialog);
  private readonly clipboard = inject(Clipboard);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  private readonly devicesHeading = viewChild<ElementRef<HTMLElement>>('devicesHeading');
  private readonly revokeButtons = viewChildren('revokeButton', {
    read: ElementRef<HTMLButtonElement>,
  });
  private readonly freshTitle = viewChild<ElementRef<HTMLElement>>('freshTitle');
  private readonly totpInput = viewChild<ElementRef<HTMLInputElement>>('totpInput');
  private readonly passkeysHeading = viewChild<ElementRef<HTMLElement>>('passkeysHeading');
  private readonly deleteButtons = viewChildren('deletePasskeyButton', {
    read: ElementRef<HTMLButtonElement>,
  });

  protected readonly devices = signal<DeviceResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** Ids of the devices whose revoke request is still running. */
  protected readonly revoking = signal<ReadonlySet<string>>(new Set());
  /** The last failed revoke, with the name of its device. */
  protected readonly revokeError = signal<{ name: string; message: string } | null>(null);

  protected readonly passkeys = signal<PasskeyResponse[] | null>(null);
  protected readonly passkeysError = signal<string | null>(null);
  protected readonly passkeysSupported = this.service.passkeysSupported();
  /** Ids of the passkeys whose delete request is still running. */
  protected readonly deleting = signal<ReadonlySet<string>>(new Set());
  /** The last failed delete, with the name of its passkey. */
  protected readonly deleteError = signal<{ name: string; message: string } | null>(null);
  protected readonly passkeyForm = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    totp: ['', [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]],
  });
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  /** Name of the passkey added last, announced once. */
  protected readonly added = signal<string | null>(null);

  protected readonly totp = inject(NonNullableFormBuilder).control('', [
    Validators.required,
    Validators.pattern(/^\s*\d{6}\s*$/),
  ]);
  protected readonly regenerating = signal(false);
  protected readonly regenerateError = signal<string | null>(null);
  /** The new codes; held only by this page and gone once it is left. */
  protected readonly codes = signal<string[] | null>(null);
  protected readonly copied = signal(false);

  ngOnInit(): void {
    void this.load();
    void this.loadPasskeys();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.devices.set(null);
    try {
      this.devices.set(await this.service.devices());
    } catch (error) {
      this.loadError.set(message(error));
    }
  }

  protected async revoke(device: DeviceResponse): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open<RevokeDeviceDialog, { name: string }, boolean>(RevokeDeviceDialog, {
          data: { name: device.name },
        })
        .afterClosed(),
    );
    if (!confirmed) return;
    this.revoking.update((ids) => new Set(ids).add(device.id));
    this.revokeError.set(null);
    try {
      await this.service.revoke(device.id);
      this.removeDevice(device.id);
    } catch (error) {
      // Already gone (revoked elsewhere): the list just catches up.
      if (error instanceof ApiError && error.status === 404) this.removeDevice(device.id);
      else this.revokeError.set({ name: device.name, message: message(error) });
    } finally {
      this.revoking.update((ids) => {
        const rest = new Set(ids);
        rest.delete(device.id);
        return rest;
      });
    }
  }

  protected async loadPasskeys(): Promise<void> {
    this.passkeysError.set(null);
    this.passkeys.set(null);
    try {
      this.passkeys.set(await this.service.passkeys());
    } catch (error) {
      this.passkeysError.set(message(error));
    }
  }

  protected async addPasskey(): Promise<void> {
    if (this.adding()) return;
    if (this.passkeyForm.invalid) {
      this.passkeyForm.markAllAsTouched();
      return;
    }
    const { name, totp } = this.passkeyForm.getRawValue();
    this.adding.set(true);
    this.addError.set(null);
    this.added.set(null);
    try {
      const passkey = await this.service.addPasskey(name.trim(), totp.trim());
      this.passkeys.update((list) => [...(list ?? []).filter((p) => p.id !== passkey.id), passkey]);
      this.added.set(passkey.name);
      this.passkeyForm.reset();
    } catch (error) {
      // A TOTP code is good for one ceremony only; the name stays for the next attempt.
      this.passkeyForm.controls.totp.reset();
      this.addError.set(passkeyMessage(error));
    } finally {
      this.adding.set(false);
    }
  }

  protected async deletePasskey(passkey: PasskeyResponse): Promise<void> {
    const confirmed = await firstValueFrom(
      this.dialog
        .open<DeletePasskeyDialog, { name: string }, boolean>(DeletePasskeyDialog, {
          data: { name: passkey.name },
        })
        .afterClosed(),
    );
    if (!confirmed) return;
    this.deleting.update((ids) => new Set(ids).add(passkey.id));
    this.deleteError.set(null);
    this.added.set(null);
    try {
      await this.service.deletePasskey(passkey.id);
      this.removePasskey(passkey.id);
    } catch (error) {
      // Already gone (deleted elsewhere): the list just catches up.
      if (error instanceof ApiError && error.status === 404) this.removePasskey(passkey.id);
      else this.deleteError.set({ name: passkey.name, message: message(error) });
    } finally {
      this.deleting.update((ids) => {
        const rest = new Set(ids);
        rest.delete(passkey.id);
        return rest;
      });
    }
  }

  protected deleteLabel(passkey: PasskeyResponse): string {
    return $localize`:@@security.passkeys.deleteAria:Delete ${passkey.name}:name:`;
  }

  protected async regenerate(): Promise<void> {
    if (this.regenerating()) return;
    if (this.totp.invalid) {
      this.totp.markAsTouched();
      return;
    }
    this.regenerating.set(true);
    this.regenerateError.set(null);
    try {
      this.codes.set(await this.service.regenerateRecoveryCodes(this.totp.value.trim()));
      this.copied.set(false);
      this.totp.reset();
      // The form gives way to the codes: move the focus to their title so they are announced.
      this.focusIfLost(() => this.freshTitle()?.nativeElement);
    } catch (error) {
      this.regenerateError.set(totpMessage(error));
    } finally {
      this.regenerating.set(false);
    }
  }

  protected copyCodes(): void {
    this.copied.set(this.clipboard.copy((this.codes() ?? []).join('\n')));
  }

  protected doneWithCodes(): void {
    this.codes.set(null);
    this.copied.set(false);
    afterNextRender(() => this.totpInput()?.nativeElement.focus(), { injector: this.injector });
  }

  protected revokeLabel(device: DeviceResponse): string {
    return $localize`:@@security.devices.revokeAria:Revoke ${device.name}:name:`;
  }

  private removeDevice(id: string): void {
    const index = this.devices()?.findIndex((device) => device.id === id) ?? -1;
    this.devices.update((devices) => devices?.filter((device) => device.id !== id) ?? null);
    // The removed row took the focus with it: go on with the next row, the previous one or the heading.
    this.focusIfLost(() => {
      const buttons = this.revokeButtons().map((ref) => ref.nativeElement);
      const target = [buttons[index], buttons[index - 1]].find((b) => b && !b.disabled);
      return target ?? this.devicesHeading()?.nativeElement;
    });
  }

  private removePasskey(id: string): void {
    const index = this.passkeys()?.findIndex((passkey) => passkey.id === id) ?? -1;
    this.passkeys.update((list) => list?.filter((passkey) => passkey.id !== id) ?? null);
    this.focusIfLost(() => {
      const buttons = this.deleteButtons().map((ref) => ref.nativeElement);
      const target = [buttons[index], buttons[index - 1]].find((b) => b && !b.disabled);
      return target ?? this.passkeysHeading()?.nativeElement;
    });
  }

  /**
   * After the next render, focuses the target if the focus was lost because its element was removed,
   * so keyboard and screen-reader users keep their place; a focus the user moved elsewhere stays.
   */
  private focusIfLost(target: () => HTMLElement | undefined): void {
    afterNextRender(
      () => {
        const active = this.document.activeElement;
        if (active && active !== this.document.body) return;
        target()?.focus();
      },
      { injector: this.injector },
    );
  }
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@security.error:Something went wrong.`;
}

function passkeyMessage(error: unknown): string {
  if (error instanceof PasskeyError) {
    switch (error.failure) {
      case 'cancelled':
        return $localize`:@@security.passkeys.cancelled:No passkey was created. Enter a new code and try again.`;
      case 'duplicate':
        return $localize`:@@security.passkeys.duplicateLocal:This authenticator already has a passkey for your account.`;
      case 'unsupported':
        return $localize`:@@security.passkeys.unsupported:This browser cannot create passkeys here.`;
      default:
        return $localize`:@@security.passkeys.failed:The passkey could not be created. Enter a new code and try again.`;
    }
  }
  if (error instanceof ApiError && error.status === 403) {
    return $localize`:@@security.passkeys.wrongTotp:That code is not valid. Enter the current code from your authenticator app.`;
  }
  if (error instanceof ApiError && error.status === 409) {
    return $localize`:@@security.passkeys.duplicate:This passkey is already registered.`;
  }
  if (error instanceof ApiError && error.status === 400) {
    return $localize`:@@security.passkeys.rejected:The passkey was not accepted. Enter a new code and try again.`;
  }
  return totpMessage(error);
}

function totpMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return $localize`:@@security.codes.wrongTotp:That code is not valid. Enter the current code from your authenticator app.`;
  }
  if (error instanceof ApiError && error.status === 429) {
    return error.retryAfter
      ? $localize`:@@security.codes.throttled:Too many wrong codes. Try again in ${error.retryAfter}:seconds: seconds.`
      : $localize`:@@security.codes.throttledLater:Too many wrong codes. Try again later.`;
  }
  return message(error);
}
