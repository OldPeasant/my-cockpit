import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSidenav } from '@angular/material/sidenav';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { CommandPalette } from './command-palette';
import { NAV_SECTIONS } from './nav-sections';
import { provideNavigationPalette } from './navigation-palette.provider';
import { Shell } from './shell';
import { CAPTURE_DIALOG } from './shell-tokens';
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
        provideRouter([]),
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
});
