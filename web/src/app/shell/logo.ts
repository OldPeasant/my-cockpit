import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The My Cockpit brand mark (docs/brand/mark.svg) as inline SVG: three dials and an artificial horizon.
 * Decorative: the text next to it names the app. The needles are cut out of the dials (evenodd stadiums, as in the
 * Android drawables), so no mask id can collide when the mark is shown twice. Ink follows the active colour scheme
 * (`light-dark()` reads `color-scheme`, which `data-theme` and `prefers-color-scheme` set); the sky stays orange.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[style.--logo-size.px]': 'size()' },
  template: `
    <svg viewBox="0 0 64 64" focusable="false">
      <g transform="translate(0.5 0.5)">
        <path
          class="ink"
          fill-rule="evenodd"
          d="M4,12a8,8 0,1 1,16,0a8,8 0,1 1,-16,0ZM12.972,11.028L9.772,7.828A1.375,1.375 0,0 0,7.828,9.772L11.028,12.972A1.375,1.375 0,0 0,12.972,11.028ZM34,10a8,8 0,1 1,16,0a8,8 0,1 1,-16,0ZM42.687,11.191L46.587,8.941A1.375,1.375 0,0 0,45.213,6.559L41.313,8.809A1.375,1.375 0,0 0,42.687,11.191ZM2,42a8,8 0,1 1,16,0a8,8 0,1 1,-16,0ZM11.375,42L11.375,37.5A1.375,1.375 0,0 0,8.625,37.5L8.625,42A1.375,1.375 0,0 0,11.375,42Z"
        />
        <circle class="sky" cx="42" cy="42" r="19" />
        <path class="ink" d="M25.55 51.5 L58.45 32.5 A19 19 0 0 1 25.55 51.5 Z" />
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: inline-block;
      flex: none;
      width: var(--logo-size);
      height: var(--logo-size);
      line-height: 0;
    }
    svg {
      width: 100%;
      height: 100%;
    }
    /* Brand colours (docs/brand/README.md): ink / ink on dark, sky. */
    .ink {
      fill: light-dark(#14243a, #eef2f7);
    }
    .sky {
      fill: #e8962e;
    }
  `,
})
export class LogoComponent {
  /** Edge length in CSS pixels. */
  readonly size = input(28);
}
