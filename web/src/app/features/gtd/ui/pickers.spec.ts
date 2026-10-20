import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ContextResponse } from '../../../api/generated';
import { ContextChips } from './context-chips';
import { CreativityPicker, EnergyPicker, EstimatePicker } from './pickers';
import { Creativity, Energy } from './labels';

@Component({
  imports: [EnergyPicker, CreativityPicker, EstimatePicker, ContextChips],
  template: `
    <app-energy-picker [(value)]="energy" />
    <app-creativity-picker [(value)]="creativity" />
    <app-estimate-picker [(value)]="estimate" [disabled]="disabled()" />
    <app-context-chips [contexts]="contexts()" [(selected)]="selected" />
  `,
})
class Host {
  readonly energy = signal<Energy | undefined>(undefined);
  readonly creativity = signal<Creativity | undefined>('MIXED');
  readonly estimate = signal<number | undefined>(undefined);
  readonly disabled = signal(false);
  readonly contexts = signal<readonly ContextResponse[]>([
    { id: 'c1', name: '@home', position: 1 } as ContextResponse,
    { id: 'c2', name: '@phone', position: 2 } as ContextResponse,
  ]);
  readonly selected = signal<readonly string[]>([]);
}

describe('GTD pickers', () => {
  let host: Host;
  let element: HTMLElement;
  let detect: () => void;

  beforeEach(() => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    host = fixture.componentInstance;
    element = fixture.nativeElement;
    detect = () => fixture.detectChanges();
  });

  const buttons = (selector: string) => [
    ...element.querySelectorAll<HTMLButtonElement>(`${selector} button`),
  ];
  const pressed = (selector: string) =>
    buttons(selector)
      .filter((button) => button.getAttribute('aria-pressed') === 'true')
      .map((button) => button.textContent!.trim());

  it('labels the groups and options', () => {
    expect(buttons('app-energy-picker').map((b) => b.textContent!.trim())).toEqual([
      'Low',
      'Medium',
      'High',
    ]);
    expect(buttons('app-estimate-picker').map((b) => b.textContent!.trim())).toEqual([
      '5 min',
      '15 min',
      '30 min',
      '1 h',
      '2 h+',
    ]);
    expect(
      element.querySelector('app-energy-picker [role=group]')?.getAttribute('aria-label'),
    ).toBe('Energy');
    expect(pressed('app-creativity-picker')).toEqual(['Mixed']);
  });

  it('chooses a value and clears it when pressed again', () => {
    buttons('app-energy-picker')[2].click();
    detect();
    expect(host.energy()).toBe('HIGH');
    expect(pressed('app-energy-picker')).toEqual(['High']);

    buttons('app-energy-picker')[2].click();
    detect();
    expect(host.energy()).toBeUndefined();
    expect(pressed('app-energy-picker')).toEqual([]);

    buttons('app-estimate-picker')[1].click();
    expect(host.estimate()).toBe(15);

    // Switching straight from one creativity to another.
    buttons('app-creativity-picker')[2].click();
    detect();
    expect(host.creativity()).toBe('CREATIVE');
    expect(pressed('app-creativity-picker')).toEqual(['Creative']);
  });

  it('disables the options', () => {
    host.disabled.set(true);
    detect();
    expect(buttons('app-estimate-picker').every((button) => button.disabled)).toBe(true);
  });

  it('toggles any number of contexts in the order chosen', () => {
    const [home, phone] = buttons('app-context-chips');
    phone.click();
    home.click();
    detect();
    expect(host.selected()).toEqual(['c2', 'c1']);
    expect(pressed('app-context-chips')).toEqual(['@home', '@phone']);

    phone.click();
    expect(host.selected()).toEqual(['c1']);
  });

  it('says when there are no contexts', () => {
    host.contexts.set([]);
    detect();
    expect(element.querySelector('app-context-chips')?.textContent?.trim()).toBe('No contexts yet');
  });
});
