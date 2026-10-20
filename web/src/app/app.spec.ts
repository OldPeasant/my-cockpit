import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('My Cockpit');
  });

  it('renders the English source title without the $localize message id metadata', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent?.trim()).toBe('My Cockpit');
  });

  it('renders the i18n-marked scaffold text in the English source locale', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('main.placeholder p')?.textContent?.trim()).toBe(
      "Scaffold. The application shell is built by the board's tasks.",
    );
  });

  it('provides the $localize runtime global (polyfill @angular/localize/init)', () => {
    expect(typeof $localize).toBe('function');
    expect($localize`:@@test.probe:Hello`).toBe('Hello');
  });
});
