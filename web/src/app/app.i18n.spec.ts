import { clearTranslations, loadTranslations } from '@angular/localize';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideFakeSession } from './core/auth/testing';
import { provideSilentEventSource } from './core/events/testing';

// Kept in its own spec file: Angular caches a component's i18n template constants on its first
// render, and the nav labels are evaluated when their module loads, so the translations must be
// loaded before the routes are imported. `isolate: true` on the test target in angular.json gives
// each spec file a fresh module graph.
describe('App i18n', () => {
  beforeEach(() => {
    // Fictitious translations keyed by the custom message ids.
    loadTranslations({
      'app.title': 'Mein Cockpit',
      'nav.someday': 'Irgendwann',
      'comingSoon.text': 'Dieser Bereich kommt bald.',
    });
  });

  afterEach(() => {
    clearTranslations();
  });

  // Uses a section that still shows the placeholder (the Inbox is built since web-inbox).
  it('translates the shell and placeholder texts through their message ids', async () => {
    const { routes } = await import('./app.routes');
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideFakeSession(), provideSilentEventSource()],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/someday');
    const root = harness.routeNativeElement as HTMLElement;

    expect(root.querySelector('.brand')?.textContent?.trim()).toBe('Mein Cockpit');
    expect(root.querySelector('a.link.active')?.textContent?.trim()).toBe('Irgendwann');
    expect(root.querySelector('app-coming-soon h1')?.textContent?.trim()).toBe('Irgendwann');
    expect(root.querySelector('app-coming-soon p')?.textContent?.trim()).toBe(
      'Dieser Bereich kommt bald.',
    );
  });
});
