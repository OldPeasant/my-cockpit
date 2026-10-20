import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/**
 * Placeholder page of a section that is not built yet. Each feature's routes file points to it
 * until the feature task replaces it; the heading is the route's `title`.
 */
@Component({
  selector: 'app-coming-soon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="coming-soon">
      <h1>{{ title }}</h1>
      <p i18n="@@comingSoon.text">This section is coming soon.</p>
    </section>
  `,
  styles: `
    .coming-soon {
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
export class ComingSoon {
  protected readonly title = inject(ActivatedRoute).snapshot.title ?? '';
}
