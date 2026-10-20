import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Frame of the pages outside the shell (login, setup): the app name above a narrow column. */
@Component({
  selector: 'app-auth-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="page">
      <div class="column">
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
