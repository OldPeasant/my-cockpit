import { TestBed } from '@angular/core/testing';
import { LogoComponent } from './logo';

describe('LogoComponent', () => {
  function render(size?: number): HTMLElement {
    const fixture = TestBed.createComponent(LogoComponent);
    if (size !== undefined) fixture.componentRef.setInput('size', size);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the mark inline: dials with cut-out needles, sky and ground', () => {
    const host = render();
    const svg = host.querySelector('svg')!;
    expect(svg.getAttribute('viewBox')).toBe('0 0 64 64');
    expect(svg.querySelector('path.ink[fill-rule="evenodd"]')).not.toBeNull();
    expect(svg.querySelector('circle.sky')).not.toBeNull();
    expect(svg.querySelectorAll('.ink')).toHaveLength(2);
    expect(svg.querySelector('image, use, mask')).toBeNull();
  });

  it('is decorative', () => {
    const host = render();
    expect(host.getAttribute('aria-hidden')).toBe('true');
    expect(host.querySelector('title')).toBeNull();
  });

  it('is 28 px by default and follows the size input', () => {
    expect(render().style.getPropertyValue('--logo-size')).toBe('28px');
    expect(render(64).style.getPropertyValue('--logo-size')).toBe('64px');
  });
});
