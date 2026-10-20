import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <section class="not-found">
      <h1 i18n="@@notFound.title">Page not found</h1>
      <p i18n="@@notFound.text">
        This address doesn't lead anywhere. Pick a section on the left or
        <a routerLink="/">go to the dashboard</a>.
      </p>
    </section>
  `,
  styles: `
    .not-found {
      max-width: 60ch;
      padding: var(--app-space-5) var(--app-space-4);
    }
    h1 {
      margin: 0 0 var(--app-space-2);
      font: var(--mat-sys-headline-medium);
    }
    p {
      margin: 0;
      color: var(--app-fg-muted);
    }
  `,
})
export class NotFound {}

export default NotFound;
