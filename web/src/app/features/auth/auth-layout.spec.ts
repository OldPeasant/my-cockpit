import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthLayout } from './auth-layout';

@Component({
  imports: [AuthLayout],
  template: `<app-auth-layout><form class="projected">Sign in form</form></app-auth-layout>`,
})
class Host {}

describe('AuthLayout', () => {
  function render(): HTMLElement {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the 64 px brand mark above the app name and the projected form', () => {
    const root = render();
    const column = root.querySelector('.column')!;
    const children = Array.from(column.children);
    expect(children.map((el) => el.tagName.toLowerCase())).toEqual(['app-logo', 'p', 'form']);

    const logo = children[0] as HTMLElement;
    expect(logo.style.getPropertyValue('--logo-size')).toBe('64px');
    expect(logo.querySelector('svg')).not.toBeNull();
    expect(children[1].textContent?.trim()).toBe('My Cockpit');
    expect(children[2].classList).toContain('projected');
  });

  it('keeps the mark decorative so only the text names the app', () => {
    const root = render();
    expect(root.querySelectorAll('app-logo')).toHaveLength(1);
    expect(root.querySelector('app-logo')!.getAttribute('aria-hidden')).toBe('true');
    expect(root.querySelector('main')!.textContent).toContain('My Cockpit');
  });
});
