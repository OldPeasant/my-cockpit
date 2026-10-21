import { ChangeDetectionStrategy, Component } from '@angular/core';
import { LogoComponent } from '../../shell/logo';

/** Frame of the pages outside the shell (login, setup): the brand mark and app name above a narrow column. */
@Component({
  selector: 'app-auth-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LogoComponent],
  template: `
    <main class="page">
      <div class="column">
        <app-logo class="logo" [size]="64" />
        <p class="brand" i18n="@@app.title">My Cockpit</p>
        <ng-content />
      </div>
    </main>
  `,
  styles: `
    :host {
      display: block;
      min-height: 100%;
      background: var(--app-bg);
    }
    .page {
      display: flex;
      justify-content: center;
      padding: 12vh var(--app-space-4) var(--app-space-5);
    }
    .column {
      width: 100%;
      max-width: 400px;
    }
    .logo {
      display: block;
      margin: 0 0 var(--app-space-3);
    }
    .brand {
      margin: 0 0 var(--app-space-4);
      color: var(--app-fg-muted);
      font: var(--mat-sys-title-medium);
    }
    @media (max-width: 599.98px) {
      .page {
        padding-top: var(--app-space-5);
      }
    }
  `,
})
export class AuthLayout {}
