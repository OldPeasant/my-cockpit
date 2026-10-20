import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { create } from 'qrcode';
import { QrCode } from './qr-code';

const URI = 'otpauth://totp/My%20Cockpit:demo?secret=JBSWY3DPEHPK3PXP&issuer=My%20Cockpit';

@Component({
  imports: [QrCode],
  template: `<app-qr-code [value]="value()" label="Fictitious QR label" />`,
})
class Host {
  readonly value = signal(URI);
}

/** The dark unit squares of the path as `col,row` keys (in SVG coordinates, quiet zone included). */
function squares(d: string): Set<string> {
  const keys = new Set<string>();
  for (const match of d.matchAll(/M(\d+) (\d+)h1v1h-1z/g)) keys.add(`${match[1]},${match[2]}`);
  return keys;
}

describe('QrCode', () => {
  async function render(): Promise<{
    host: Host;
    svg: () => SVGElement;
    stable: () => Promise<unknown>;
  }> {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    return {
      host: fixture.componentInstance,
      svg: () => root.querySelector('app-qr-code svg') as SVGElement,
      stable: () => fixture.whenStable(),
    };
  }

  function path(svg: SVGElement): string {
    return svg.querySelector('path')!.getAttribute('d')!;
  }

  it('is an accessible image with the given label', async () => {
    const { svg } = await render();
    expect(svg().getAttribute('role')).toBe('img');
    expect(svg().getAttribute('aria-label')).toBe('Fictitious QR label');
  });

  it('draws only unit squares, inside a square view box with a 2-module quiet zone', async () => {
    const { svg } = await render();
    const size = create(URI, { errorCorrectionLevel: 'M' }).modules.size;
    expect(svg().getAttribute('viewBox')).toBe(`0 0 ${size + 4} ${size + 4}`);

    const d = path(svg());
    expect(d.replace(/M\d+ \d+h1v1h-1z/g, '')).toBe('');
    for (const key of squares(d)) {
      const [col, row] = key.split(',').map(Number);
      expect(col).toBeGreaterThanOrEqual(2);
      expect(row).toBeGreaterThanOrEqual(2);
      expect(col).toBeLessThan(size + 2);
      expect(row).toBeLessThan(size + 2);
    }
  });

  it('draws exactly the dark modules of the symbol for the value', async () => {
    const { svg } = await render();
    const modules = create(URI, { errorCorrectionLevel: 'M' }).modules;
    const expected = new Set<string>();
    for (let row = 0; row < modules.size; row++) {
      for (let col = 0; col < modules.size; col++) {
        if (modules.get(row, col)) expected.add(`${col + 2},${row + 2}`);
      }
    }
    expect(squares(path(svg()))).toEqual(expected);
  });

  it('has the three finder patterns in the top-left, top-right and bottom-left corners', async () => {
    const { svg } = await render();
    const dark = squares(path(svg()));
    const size = create(URI, { errorCorrectionLevel: 'M' }).modules.size;
    const last = size + 1;
    // Outer ring corners of each finder pattern are dark, the separator next to them is light.
    expect(dark.has('2,2')).toBe(true);
    expect(dark.has(`${last},2`)).toBe(true);
    expect(dark.has(`2,${last}`)).toBe(true);
    expect(dark.has('9,9')).toBe(false);
    expect(dark.has(`${last - 7},9`)).toBe(false);
  });

  it('redraws when the value changes', async () => {
    const { host, svg, stable } = await render();
    const before = path(svg());
    const other = 'otpauth://totp/My%20Cockpit:other?secret=GEZDGNBVGY3TQOJQ&issuer=My%20Cockpit';
    host.value.set(other);
    await stable();
    expect(path(svg())).not.toBe(before);
    const size = create(other, { errorCorrectionLevel: 'M' }).modules.size;
    expect(svg().getAttribute('viewBox')).toBe(`0 0 ${size + 4} ${size + 4}`);
  });
});
