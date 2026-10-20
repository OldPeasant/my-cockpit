import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { create } from 'qrcode';

/** Blank modules around the symbol, as scanners expect (4 per ISO/IEC 18004; 2 read reliably). */
const QUIET_ZONE = 2;

/** A QR code as an SVG path: no canvas, scales sharply, follows no theme (always dark on white). */
@Component({
  selector: 'app-qr-code',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      role="img"
      [attr.aria-label]="label()"
      [attr.viewBox]="viewBox()"
      shape-rendering="crispEdges"
    >
      <rect width="100%" height="100%" fill="#fff" />
      <path [attr.d]="path()" fill="#000" />
    </svg>
  `,
  styles: `
    :host {
      display: block;
    }
    svg {
      display: block;
      width: 100%;
      height: auto;
    }
  `,
})
export class QrCode {
  readonly value = input.required<string>();
  readonly label = input.required<string>();

  private readonly symbol = computed(() => create(this.value(), { errorCorrectionLevel: 'M' }));

  protected readonly viewBox = computed(() => {
    const size = this.symbol().modules.size + 2 * QUIET_ZONE;
    return `0 0 ${size} ${size}`;
  });

  /** One unit square per dark module. */
  protected readonly path = computed(() => {
    const modules = this.symbol().modules;
    const parts: string[] = [];
    for (let row = 0; row < modules.size; row++) {
      for (let col = 0; col < modules.size; col++) {
        if (modules.get(row, col)) parts.push(`M${col + QUIET_ZONE} ${row + QUIET_ZONE}h1v1h-1z`);
      }
    }
    return parts.join('');
  });
}
