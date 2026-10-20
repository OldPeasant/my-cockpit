import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { App } from './app';
import { routes } from './app.routes';
import { provideFakeSession } from './core/auth/testing';
import { CapturePlaceholder } from './features/capture/capture-placeholder';
import { CommandPalette } from './shell/command-palette';
import { NAV_SECTIONS } from './shell/nav-sections';
import { NavigationPaletteProvider } from './shell/navigation-palette.provider';
import { PALETTE_PROVIDERS } from './shell/shell-tokens';
import { ShortcutsDialog } from './shell/shortcuts-dialog';

describe('App', () => {
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes), provideFakeSession()] });
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    TestBed.inject(MatDialog).closeAll();
  });

  function openDialogs(): unknown[] {
    return TestBed.inject(MatDialog).openDialogs.map((ref) => ref.componentInstance);
  }

  function keydown(init: KeyboardEventInit): void {
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }),
    );
  }

  it('should create the app', () => {
    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });

  it('renders the shell with the dashboard placeholder at /', async () => {
    await harness.navigateByUrl('/');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-shell .brand')?.textContent?.trim()).toBe('My Cockpit');
    expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe('Dashboard');
  });

  it.each(NAV_SECTIONS.map((s) => [s.path, s.label]))(
    'lazy-loads a coming soon page for %s',
    async (path, label) => {
      await harness.navigateByUrl(path);
      const root = harness.routeNativeElement as HTMLElement;
      expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe(label);
      expect(root.querySelector('app-coming-soon p')?.textContent?.trim()).toBe(
        'This section is coming soon.',
      );
      expect(document.title).toBe(label);
      const active = root.querySelector('a.link.active');
      expect(active?.textContent?.trim()).toBe(label);
      expect(active?.getAttribute('aria-current')).toBe('page');
    },
  );

  it('shows a not found page for unknown URLs', async () => {
    await harness.navigateByUrl('/no-such-section');
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-not-found h1')?.textContent?.trim()).toBe('Page not found');
    expect(TestBed.inject(Router).url).toBe('/no-such-section');
  });

  it('wires the capture placeholder into the shell: Q opens its dialog', async () => {
    await harness.navigateByUrl('/');
    keydown({ key: 'q' });
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CapturePlaceholder);
  });

  // Regression guard for the route-level providers in app.routes.ts: the palette dialog must see
  // the PALETTE_PROVIDERS registered on the shell route, not only the root injector's empty default.
  it('wires the navigation commands into the palette: Ctrl+K, type, Enter navigates', async () => {
    await harness.navigateByUrl('/');
    keydown({ key: 'k', ctrlKey: true });
    expect(openDialogs()[0]).toBeInstanceOf(CommandPalette);
    const palette = await vi.waitFor(() => {
      const el = document.querySelector<HTMLElement>('app-command-palette');
      expect(el).not.toBeNull();
      return el!;
    });
    const labels = (): string[] =>
      Array.from(palette.querySelectorAll('[role="option"] .label')).map(
        (e) => e.textContent?.trim() ?? '',
      );
    await vi.waitFor(() => {
      TestBed.tick();
      expect(labels()).toEqual(NAV_SECTIONS.map((s) => s.label));
    });

    const input = palette.querySelector<HTMLInputElement>('input')!;
    input.value = 'trash';
    input.dispatchEvent(new Event('input'));
    await vi.waitFor(() => {
      TestBed.tick();
      expect(labels()).toEqual(['Trash']);
    });
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/trash'));
    await harness.fixture.whenStable();
    const root = harness.routeNativeElement as HTMLElement;
    expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe('Trash');
  });

  // CR-1/CR-2 follow-up: every dialog the shell opens (shortcut overview, capture placeholder)
  // resolves its providers through the shell route, like the palette.
  it('opens the shortcut overview and the capture dialog with the shell route providers', async () => {
    await harness.navigateByUrl('/');
    const routeProviders = (index = 0): readonly unknown[] =>
      TestBed.inject(MatDialog).openDialogs[index].componentRef!.injector.get(PALETTE_PROVIDERS);

    keydown({ key: '?', shiftKey: true });
    expect(openDialogs()[0]).toBeInstanceOf(ShortcutsDialog);
    expect(routeProviders()).toEqual([expect.any(NavigationPaletteProvider)]);
    TestBed.inject(MatDialog).closeAll();
    await vi.waitFor(() => expect(openDialogs()).toEqual([]));

    keydown({ key: 'q' });
    await vi.waitFor(() => expect(openDialogs()).toHaveLength(1));
    expect(openDialogs()[0]).toBeInstanceOf(CapturePlaceholder);
    expect(routeProviders()).toEqual([expect.any(NavigationPaletteProvider)]);
  });
});
