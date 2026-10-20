import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { App } from './app';
import { routes } from './app.routes';
import { NAV_SECTIONS } from './shell/nav-sections';

describe('App', () => {
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    harness = await RouterTestingHarness.create();
  });

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
});
