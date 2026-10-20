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
import { DeviceResponse } from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { RevokeDeviceDialog } from './revoke-device-dialog';
import { SecurityService } from './security.service';

/**
 * Settings → Security (SPEC.md §4.1): the devices signed in with the Android app, each revocable
 * after a confirmation, and new recovery codes for a current TOTP code, shown only once.
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

  protected readonly devices = signal<DeviceResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  /** Ids of the devices whose revoke request is still running. */
  protected readonly revoking = signal<ReadonlySet<string>>(new Set());
  /** The last failed revoke, with the name of its device. */
  protected readonly revokeError = signal<{ name: string; message: string } | null>(null);

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
