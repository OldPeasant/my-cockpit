import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { DEFAULT_PORTS, Protocol, Security, ServerForm } from './account-form';

/** Host, port and security of the incoming (IMAP) or outgoing (SMTP) server. */
@Component({
  selector: 'app-server-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatFormFieldModule, MatInputModule, MatSelectModule, ReactiveFormsModule],
  template: `
    <fieldset class="server" [formGroup]="form()">
      <legend>
        @if (protocol() === 'imap') {
          <ng-container i18n="@@mailSettings.server.imap">Incoming mail (IMAP)</ng-container>
        } @else {
          <ng-container i18n="@@mailSettings.server.smtp">Outgoing mail (SMTP)</ng-container>
        }
      </legend>
      <mat-form-field subscriptSizing="dynamic" class="host">
        <mat-label i18n="@@mailSettings.field.host">Server</mat-label>
        <input
          matInput
          formControlName="host"
          autocomplete="off"
          [placeholder]="protocol() === 'imap' ? 'imap.example.org' : 'smtp.example.org'"
        />
        @if (form().controls.host.touched && form().controls.host.invalid) {
          <mat-error i18n="@@mailSettings.field.hostRequired">Enter the server name.</mat-error>
        }
      </mat-form-field>
      <mat-form-field subscriptSizing="dynamic" class="port">
        <mat-label i18n="@@mailSettings.field.port">Port</mat-label>
        <input matInput formControlName="port" type="number" min="1" max="65535" />
        @if (form().controls.port.touched && form().controls.port.invalid) {
          <mat-error i18n="@@mailSettings.field.portRange">1 to 65535.</mat-error>
        }
      </mat-form-field>
      <mat-form-field subscriptSizing="dynamic" class="security">
        <mat-label i18n="@@mailSettings.field.security">Security</mat-label>
        <mat-select formControlName="security" (selectionChange)="securityChanged($event.value)">
          <mat-option value="SSL_TLS" i18n="@@mailSettings.security.tls">SSL/TLS</mat-option>
          <mat-option value="STARTTLS" i18n="@@mailSettings.security.starttls">STARTTLS</mat-option>
          <mat-option value="NONE" i18n="@@mailSettings.security.none"
            >None (unencrypted)</mat-option
          >
        </mat-select>
      </mat-form-field>
    </fieldset>
  `,
  styles: `
    .server {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 6.5rem minmax(0, 11rem);
      gap: var(--app-space-3);
      margin: 0;
      padding: 0;
      border: 0;
      min-width: 0;
    }

    legend {
      margin-bottom: var(--app-space-2);
      padding: 0;
      font: var(--mat-sys-title-small);
    }

    @media (max-width: 720px) {
      .server {
        grid-template-columns: minmax(0, 1fr) 6.5rem;
      }

      .host {
        grid-column: 1 / -1;
      }
    }
  `,
})
export class ServerFields {
  readonly form = input.required<ServerForm>();
  readonly protocol = input.required<Protocol>();

  /** Moves the port along to the new security's usual one, unless it is an unusual one. */
  protected securityChanged(security: Security): void {
    const ports = DEFAULT_PORTS[this.protocol()];
    const port = this.form().controls.port;
    if (Object.values(ports).includes(Number(port.value))) port.setValue(ports[security]);
  }
}
