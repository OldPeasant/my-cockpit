import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSidenav } from '@angular/material/sidenav';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BehaviorSubject, of } from 'rxjs';
import { AuthService } from '../core/auth';
import { provideFakeSession } from '../core/auth/testing';
import { CommandPalette } from './command-palette';
import { NAV_SECTIONS } from './nav-sections';
import { provideNavigationPalette } from './navigation-palette.provider';
import { Shell } from './shell';
import { CAPTURE_DIALOG, PALETTE_PROVIDERS, PaletteProvider } from './shell-tokens';
import { ShortcutsDialog } from './shortcuts-dialog';
import { ThemeService } from './theme.service';

describe('Shell', () => {
  let fixture: ComponentFixture<Shell>;
  let root: HTMLElement;
  let capture: { open: ReturnType<typeof vi.fn> };
  let breakpoint: BehaviorSubject<BreakpointState>;

  async function setup(compact = false): Promise<void> {
    capture = { open: vi.fn() };
    breakpoint = new BehaviorSubject<BreakpointState>({ matches: compact, breakpoints: {} });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        provideFakeSession('demo'),
        provideNavigationPalette(),
        { provide: CAPTURE_DIALOG, useValue: capture },
        { provide: BreakpointObserver, useValue: { observe: () => breakpoint } },
      ],
    });
    fixture = TestBed.createComponent(Shell);
    root = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  }

  function keydown(init: KeyboardEventInit, target: EventTarget = document.body): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    return event;
  }

  function openDialogs(): unknown[] {
    return TestBed.inject(MatDialog).openDialogs.map((ref) => ref.componentInstance);
  }

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
    localStorage.clear();
  });

  it('renders a link for every navigation section, Logbook and Trash included', async () => {
    await setup();
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('nav a.link'));
    expect(links.map((a) => a.textContent?.trim())).toEqual(NAV_SECTIONS.map((s) => s.label));
    expect(links.map((a) => a.getAttribute('href'))).toEqual(NAV_SECTIONS.map((s) => s.path));
    const labels = links.map((a) => a.textContent?.trim());
    for (const label of [
      'Dashboard',
      'Inbox',
      'Next',
      'Projects',
      'Waiting',
      'Someday',
      'Calendar',
      'Mail',
      'Notes',
      'Files',
      'Contacts',
      'Bookmarks',
      'Journal',
      'Habits',
      'Review',
      'Settings',
      'Logbook',
      'Trash',
    ]) {
      expect(labels).toContain(label);
    }
  });

  it('shows the navigation side by side on wide screens', async () => {
    await setup(false);
    const sidenav = fixture.debugElement.query(By.directive(MatSidenav)).componentInstance;
    expect(sidenav.mode).toBe('side');
    expect(sidenav.opened).toBe(true);
    expect(root.querySelector('button.menu')).toBeNull();
  });

  it('collapses the navigation to a drawer below 900 px', async () => {
    await setup(true);
    const sidenav: MatSidenav = fixture.debugElement.query(
      By.directive(MatSidenav),
    ).componentInstance;
    expect(sidenav.mode).toBe('over');
    expect(sidenav.opened).toBe(false);

    root.querySelector<HTMLButtonElement>('button.menu')!.click();
    await fixture.whenStable();
    expect(sidenav.opened).toBe(true);

    root.querySelector<HTMLAnchorElement>('nav a.link')!.click();
    await fixture.whenStable();
    expect(sidenav.opened).toBe(false);
  });

  it('switches between side navigation and drawer when the width changes', async () => {
    await setup(false);
    breakpoint.next({ matches: true, breakpoints: {} });
    await fixture.whenStable();
    const sidenav: MatSidenav = fixture.debugElement.query(
      By.directive(MatSidenav),
    ).componentInstance;
    expect(sidenav.mode).toBe('over');
    expect(root.querySelector('button.menu')).not.toBeNull();
  });

  it('opens capture with Q, Ctrl+Shift+Space and the + button', async () => {
    await setup();
    const q = keydown({ key: 'q' });
    expect(capture.open).toHaveBeenCalledTimes(1);
    expect(q.defaultPrevented).toBe(true);
    keydown({ key: ' ', code: 'Space', ctrlKey: true, shiftKey: true });
    expect(capture.open).toHaveBeenCalledTimes(2);
    root.querySelector<HTMLButtonElement>('button.capture')!.click();
    expect(capture.open).toHaveBeenCalledTimes(3);
  });

  it('ignores Q and ? while typing in an input', async () => {
    await setup();
    const input = document.createElement('input');
    document.body.appendChild(input);
    try {
      const q = keydown({ key: 'q' }, input);
      keydown({ key: '?', shiftKey: true }, input);
      expect(capture.open).not.toHaveBeenCalled();
      expect(q.defaultPrevented).toBe(false);
      expect(openDialogs()).toEqual([]);
    } finally {
      input.remove();
    }
  });

  it('opens the command palette with Ctrl+K and from the search field, only once', async () => {
    await setup();
    keydown({ key: 'k', ctrlKey: true });
    expect(openDialogs().filter((c) => c instanceof CommandPalette)).toHaveLength(1);
    keydown({ key: 'k', ctrlKey: true });
    root.querySelector<HTMLButtonElement>('button.search')!.click();
    expect(openDialogs().filter((c) => c instanceof CommandPalette)).toHaveLength(1);
  });

  it('opens the shortcut overview with ?', async () => {
    await setup();
    keydown({ key: '?', shiftKey: true });
    expect(openDialogs().some((c) => c instanceof ShortcutsDialog)).toBe(true);
    await fixture.whenStable();
    expect(document.querySelector('app-shortcuts-dialog')?.textContent).toContain('Ctrl+K');
  });

  it('does not open capture while a dialog is open', async () => {
    await setup();
    keydown({ key: '?', shiftKey: true });
    keydown({ key: 'q' });
    expect(capture.open).not.toHaveBeenCalled();
  });

  it('does not open capture with Ctrl+Shift+Space while a dialog is open', async () => {
    await setup();
    keydown({ key: '?', shiftKey: true });
    const event = keydown({ key: ' ', code: 'Space', ctrlKey: true, shiftKey: true });
    expect(capture.open).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
  });

  it('switches the theme from the theme menu', async () => {
    await setup();
    const theme = TestBed.inject(ThemeService);
    root.querySelector<HTMLButtonElement>('button.theme')!.click();
    await fixture.whenStable();
    const items = Array.from(
      document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]'),
    );
    expect(items.map((i) => i.textContent?.trim())).toEqual(['Light', 'Dark', 'System']);
    items[1].click();
    await fixture.whenStable();
    expect(theme.mode()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('opens the palette with Ctrl+K while typing in an input and while another dialog is open', async () => {
    await setup();
    const input = document.createElement('input');
    document.body.appendChild(input);
    try {
      const event = keydown({ key: 'k', ctrlKey: true }, input);
      expect(event.defaultPrevented).toBe(true);
      expect(openDialogs().filter((c) => c instanceof CommandPalette)).toHaveLength(1);
    } finally {
      input.remove();
    }
    TestBed.inject(MatDialog).closeAll();
    keydown({ key: '?', shiftKey: true });
    keydown({ key: 'k', ctrlKey: true });
    expect(openDialogs().some((c) => c instanceof CommandPalette)).toBe(true);
  });

  it('opens capture with Ctrl+Shift+Space while typing in an input', async () => {
    await setup();
    const textarea = document.createElement('textarea');
    document.body.appendChild(textarea);
    try {
      keydown({ key: ' ', code: 'Space', ctrlKey: true, shiftKey: true }, textarea);
      expect(capture.open).toHaveBeenCalledTimes(1);
    } finally {
      textarea.remove();
    }
  });

  it('does not open the shortcut overview twice', async () => {
    await setup();
    keydown({ key: '?', shiftKey: true });
    keydown({ key: '?', shiftKey: true });
    expect(openDialogs().filter((c) => c instanceof ShortcutsDialog)).toHaveLength(1);
  });

  it('marks the current theme in the theme menu', async () => {
    localStorage.setItem('cockpit.theme', 'light');
    await setup();
    root.querySelector<HTMLButtonElement>('button.theme')!.click();
    await fixture.whenStable();
    const checked = () =>
      Array.from(document.querySelectorAll('[role="menuitemradio"]')).map((i) =>
        i.getAttribute('aria-checked'),
      );
    expect(checked()).toEqual(['true', 'false', 'false']);
    document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')[2].click();
    await fixture.whenStable();
    expect(localStorage.getItem('cockpit.theme')).toBe('system');
    root.querySelector<HTMLButtonElement>('button.theme')!.click();
    await fixture.whenStable();
    expect(checked()).toEqual(['false', 'false', 'true']);
  });

  it('signs out from the account menu and shows the login page', async () => {
    await setup();
    const logout = vi.spyOn(TestBed.inject(AuthService), 'logout');
    root.querySelector<HTMLButtonElement>('button.account')!.click();
    await fixture.whenStable();
    expect(document.querySelector('.account-name')?.textContent?.trim()).toBe('demo');
    document.querySelector<HTMLButtonElement>('button.logout')!.click();
    await fixture.whenStable();
    expect(logout).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(Router).url).toBe('/login');
  });

  it('shows the login page even when signing out fails on the server', async () => {
    await setup();
    vi.spyOn(TestBed.inject(AuthService), 'logout').mockRejectedValue(new Error('offline'));
    root.querySelector<HTMLButtonElement>('button.account')!.click();
    await fixture.whenStable();
    document.querySelector<HTMLButtonElement>('button.logout')!.click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/login');
  });

  it('does not leave an unhandled rejection when the navigation to the login page fails', async () => {
    await setup();
    const logout = vi.spyOn(TestBed.inject(AuthService), 'logout');
    const navigate = vi
      .spyOn(TestBed.inject(Router), 'navigateByUrl')
      .mockRejectedValue(new Error('guard failed'));
    root.querySelector<HTMLButtonElement>('button.account')!.click();
    await fixture.whenStable();
    document.querySelector<HTMLButtonElement>('button.logout')!.click();
    await fixture.whenStable();
    await Promise.resolve();
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('keeps the side navigation open after following a link on wide screens', async () => {
    await setup(false);
    const sidenav: MatSidenav = fixture.debugElement.query(
      By.directive(MatSidenav),
    ).componentInstance;
    root.querySelector<HTMLAnchorElement>('nav a.link')!.click();
    await fixture.whenStable();
    expect(sidenav.opened).toBe(true);
  });
});

// The plug-in point for feature providers (e.g. provideSearch()): PALETTE_PROVIDERS registered on
// the route that loads the shell, not at the root, show up in the palette next to the "Go to" rows.
describe('Shell with route-level palette providers', () => {
  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  it('shows the rows of every provider registered on the shell route', async () => {
    const searchLike: PaletteProvider = {
      items: (query) =>
        of([{ id: 'note-1', group: 'Notes', label: `Fictitious note ${query}`, run: vi.fn() }]),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: '',
            component: Shell,
            providers: [
              provideNavigationPalette(),
              { provide: PALETTE_PROVIDERS, useValue: searchLike, multi: true },
              { provide: CAPTURE_DIALOG, useValue: { open: vi.fn() } },
            ],
          },
        ]),
        {
          provide: BreakpointObserver,
          useValue: { observe: () => of({ matches: false, breakpoints: {} }) },
        },
      ],
    });
    const harness = await RouterTestingHarness.create('/');
    const root = harness.routeNativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('button.search')!.click();
    expect(TestBed.inject(MatDialog).openDialogs[0].componentInstance).toBeInstanceOf(
      CommandPalette,
    );

    const groups = (): string[] =>
      Array.from(document.querySelectorAll('app-command-palette .group-name')).map(
        (e) => e.textContent?.trim() ?? '',
      );
    await vi.waitFor(() => {
      TestBed.tick();
      expect(groups()).toEqual(['Go to', 'Notes']);
    });
    expect(document.querySelector('app-command-palette')?.textContent).toContain('Fictitious note');
  });
});
