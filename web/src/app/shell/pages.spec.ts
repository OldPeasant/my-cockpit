import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MatDialog } from '@angular/material/dialog';
import { SHELL_SHORTCUTS } from './hotkeys';
import { NotFound } from './not-found';
import { ShortcutsDialog } from './shortcuts-dialog';

describe('NotFound', () => {
  it('explains the dead end and links back to the dashboard', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', pathMatch: 'full', children: [] },
          { path: '**', component: NotFound },
        ]),
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/does-not-exist');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('h1')?.textContent?.trim()).toBe('Page not found');

    const link = root.querySelector<HTMLAnchorElement>('a')!;
    expect(link.getAttribute('href')).toBe('/');
    link.click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/');
  });
});

describe('ShortcutsDialog', () => {
  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  it('lists every shell shortcut with its keys and description', async () => {
    const ref = TestBed.inject(MatDialog).open(ShortcutsDialog);
    await vi.waitFor(() => {
      TestBed.tick();
      expect(document.querySelectorAll('app-shortcuts-dialog dt').length).toBeGreaterThan(0);
    });
    const dialog = document.querySelector('app-shortcuts-dialog')!;

    const rows = Array.from(dialog.querySelectorAll('dt')).map((dt) =>
      Array.from(dt.querySelectorAll('kbd')).map((k) => k.textContent?.trim()),
    );
    expect(rows).toEqual([['Ctrl+K'], ['Q', 'Ctrl+Shift+Space'], ['?']]);
    expect(rows).toEqual(SHELL_SHORTCUTS.map((s) => [...s.keys]));
    expect(Array.from(dialog.querySelectorAll('dd')).map((dd) => dd.textContent?.trim())).toEqual(
      SHELL_SHORTCUTS.map((s) => s.description),
    );
    // "or" separates alternative keys only.
    expect(dialog.querySelectorAll('.or')).toHaveLength(1);

    const closed = vi.fn();
    ref.afterClosed().subscribe(closed);
    dialog.querySelector<HTMLButtonElement>('button[mat-dialog-close]')!.click();
    await vi.waitFor(() => expect(closed).toHaveBeenCalled());
  });
});
