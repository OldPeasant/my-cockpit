import { clearTranslations, loadTranslations } from '@angular/localize';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

// Kept in its own spec file: Angular caches a component's i18n template constants on its first
// render, so the translations must be loaded before App is rendered for the first time.
// `isolate: true` on the test target in angular.json gives each spec file a fresh module graph.
describe('App i18n', () => {
  beforeEach(async () => {
    // Fictitious translations keyed by the custom message ids (@@app.title, @@app.scaffold).
    loadTranslations({
      'app.title': 'Mein Cockpit',
      'app.scaffold': 'Gerüst. Die Anwendungsschale wird von den Aufgaben gebaut.',
    });
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  afterEach(() => {
    clearTranslations();
  });

  it('translates the title through $localize using the app.title message id', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent?.trim()).toBe('Mein Cockpit');
  });

  it('translates the i18n template text using the app.scaffold message id', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('main.placeholder p')?.textContent?.trim()).toBe(
      'Gerüst. Die Anwendungsschale wird von den Aufgaben gebaut.',
    );
  });
});
