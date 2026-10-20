// `public/theme-init.js` runs before Angular starts (index.html loads it synchronously) and applies
// the stored theme before the first paint. It must agree with ThemeService on key and values.
import { TestBed } from '@angular/core/testing';
import { THEME_STORAGE_KEY, ThemeService } from './theme.service';

/** Executes the script again, as a fresh page load would. */
async function runThemeInit(): Promise<void> {
  vi.resetModules();
  // @ts-expect-error: plain browser script in public/, it has no type declarations
  await import('../../../public/theme-init.js');
}

function appliedTheme(): string | null {
  return document.documentElement.getAttribute('data-theme');
}

describe('theme-init.js (theme before first paint)', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it.each(['light', 'dark', 'system'])('applies the stored mode %s to <html>', async (mode) => {
    localStorage.setItem(THEME_STORAGE_KEY, mode);
    await runThemeInit();
    expect(appliedTheme()).toBe(mode);
  });

  it('falls back to system when nothing is stored', async () => {
    await runThemeInit();
    expect(appliedTheme()).toBe('system');
  });

  it.each(['neon', '', 'DARK', '"dark"'])(
    'falls back to system for the invalid stored value %j',
    async (value) => {
      localStorage.setItem(THEME_STORAGE_KEY, value);
      await runThemeInit();
      expect(appliedTheme()).toBe('system');
    },
  );

  it('falls back to system without throwing when localStorage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError: storage disabled');
    });
    await expect(runThemeInit()).resolves.toBeUndefined();
    expect(appliedTheme()).toBe('system');
  });

  it('reads what ThemeService remembers, so the next page load starts with the chosen theme', async () => {
    TestBed.inject(ThemeService).setMode('dark');
    document.documentElement.removeAttribute('data-theme');
    await runThemeInit();
    expect(appliedTheme()).toBe('dark');
  });
});
