import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { Observable, of, Subject } from 'rxjs';
import { CommandPalette } from './command-palette';
import { provideNavigationPalette } from './navigation-palette.provider';
import { PALETTE_PROVIDERS, PaletteItem, PaletteProvider } from './shell-tokens';

function item(id: string, group: string, run = vi.fn()): PaletteItem {
  return { id, group, label: id, run };
}

describe('CommandPalette', () => {
  let fixture: ComponentFixture<CommandPalette>;
  let root: HTMLElement;
  let close: ReturnType<typeof vi.fn>;

  async function setup(providers: unknown[]): Promise<void> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        ...(providers as never[]),
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    fixture = TestBed.createComponent(CommandPalette);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  function input(): HTMLInputElement {
    return root.querySelector('input')!;
  }

  async function type(value: string): Promise<void> {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function key(name: string): Promise<void> {
    input().dispatchEvent(new KeyboardEvent('keydown', { key: name, cancelable: true }));
    await fixture.whenStable();
  }

  function labels(): string[] {
    return Array.from(root.querySelectorAll('[role="option"] .label')).map(
      (e) => e.textContent?.trim() ?? '',
    );
  }

  it('lists the navigation commands and filters them by the query', async () => {
    await setup([provideNavigationPalette()]);
    expect(labels()).toContain('Inbox');
    expect(root.querySelector('.group-name')?.textContent?.trim()).toBe('Go to');
    await type('inb');
    expect(labels()).toEqual(['Inbox']);
    await type('zzz');
    expect(labels()).toEqual([]);
    expect(root.querySelector('.empty')).not.toBeNull();
  });

  it('runs the chosen navigation command with Enter and closes', async () => {
    await setup([provideNavigationPalette()]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    await type('mail');
    await key('Enter');
    expect(navigate).toHaveBeenCalledWith('/mail');
    expect(close).toHaveBeenCalled();
  });

  it('groups rows of several providers and moves the selection with the arrow keys', async () => {
    const a: PaletteProvider = { items: () => of([item('a1', 'A'), item('b1', 'B')]) };
    const b: PaletteProvider = { items: () => of([item('a2', 'A')]) };
    await setup([
      { provide: PALETTE_PROVIDERS, useValue: a, multi: true },
      { provide: PALETTE_PROVIDERS, useValue: b, multi: true },
    ]);
    expect(Array.from(root.querySelectorAll('.group-name')).map((e) => e.textContent)).toEqual([
      'A',
      'B',
    ]);
    expect(labels()).toEqual(['a1', 'a2', 'b1']);
    const active = () => root.querySelector('[role="option"].active .label')?.textContent;
    expect(active()).toBe('a1');
    await key('ArrowDown');
    expect(active()).toBe('a2');
    expect(input().getAttribute('aria-activedescendant')).toBe(
      root.querySelector('[role="option"].active')!.id,
    );
    await key('ArrowDown');
    await key('ArrowDown');
    expect(active()).toBe('a1');
    await key('ArrowUp');
    expect(active()).toBe('b1');
  });

  it('ignores results of an older query that arrive late', async () => {
    const responses = new Map<string, Subject<readonly PaletteItem[]>>();
    const slow: PaletteProvider = {
      items: (q: string): Observable<readonly PaletteItem[]> => {
        const subject = new Subject<readonly PaletteItem[]>();
        responses.set(q, subject);
        return subject;
      },
    };
    await setup([{ provide: PALETTE_PROVIDERS, useValue: slow, multi: true }]);
    await type('old');
    await type('new');
    responses.get('new')!.next([item('fresh', 'Results')]);
    responses.get('old')!.next([item('stale', 'Results')]);
    await fixture.whenStable();
    expect(labels()).toEqual(['fresh']);
  });

  it('keeps the other rows when one provider fails', async () => {
    const failing: PaletteProvider = {
      items: () => new Observable((s) => s.error(new Error('offline'))),
    };
    await setup([
      { provide: PALETTE_PROVIDERS, useValue: failing, multi: true },
      provideNavigationPalette(),
    ]);
    await type('trash');
    expect(labels()).toEqual(['Trash']);
  });

  it('runs a row on click', async () => {
    const run = vi.fn();
    await setup([
      {
        provide: PALETTE_PROVIDERS,
        useValue: { items: () => of([item('x', 'G', run)]) },
        multi: true,
      },
    ]);
    root.querySelector<HTMLElement>('[role="option"]')!.click();
    expect(run).toHaveBeenCalled();
    expect(close).toHaveBeenCalled();
  });

  it('shows the empty state without providers and ignores Enter and arrow keys', async () => {
    await setup([]);
    expect(labels()).toEqual([]);
    expect(root.querySelector('.empty')).not.toBeNull();
    expect(input().getAttribute('aria-activedescendant')).toBeNull();
    await key('ArrowDown');
    await key('ArrowUp');
    await key('Enter');
    expect(close).not.toHaveBeenCalled();
  });
});
