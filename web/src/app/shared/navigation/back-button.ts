import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { BackNavigation } from './back-navigation';

/** The label of a back button whose destination has no name. */
export const BACK_LABEL = $localize`:@@back.label:Back`;

/**
 * A detail page's back link: goes back in the browser history when the previous entry is a page of
 * this app (New with its filter, the dashboard, a project, ...), else to the page's parent route.
 * It reads the destination's name ("New", "Mail", the project's title), else "Back".
 *
 * `<app-back-button fallback="/mail" fallbackLabel="Mail" />`
 *
 * The link's `href` is the destination, so opening it in a new tab works too.
 */
@Component({
  selector: 'app-back-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" [attr.href]="href()" [attr.aria-label]="ariaLabel()" (click)="go($event)">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
      </svg>
      <span class="label">{{ label() }}</span>
    </a>
  `,
  styleUrl: './back-button.scss',
})
export class BackButton {
  private readonly navigation = inject(BackNavigation);
  private readonly location = inject(Location);

  /** The parent route, for a page opened by a deep link or in a new tab. */
  readonly fallback = input.required<string>();
  /** What the parent route is called ("Mail", "All projects"). */
  readonly fallbackLabel = input<string | null>(null);

  /** Where the button leads: the previous app page, else the parent route. */
  private readonly target = computed(() => {
    const previous = this.navigation.previous();
    return previous
      ? { url: previous.url, label: previous.label }
      : { url: this.fallback(), label: this.fallbackLabel() };
  });

  protected readonly label = computed(() => this.target().label || BACK_LABEL);
  protected readonly ariaLabel = computed(() => {
    const destination = this.target().label;
    return destination ? $localize`:@@back.aria:Back to ${destination}:destination:` : BACK_LABEL;
  });
  protected readonly href = computed(() => this.location.prepareExternalUrl(this.target().url));

  protected go(event: MouseEvent): void {
    // Modified clicks (new tab, new window) follow the link's href.
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    void this.navigation.back(this.fallback());
  }
}
