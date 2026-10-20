import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Settings, SettingsPatch } from '../../../api/generated';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import { FilterPresets, PRESETS_SETTING, sameName } from './filter-presets';

const PHONE = '0192f0c4-0000-7000-8000-0000000000c1';
const HOME = '0192f0c4-0000-7000-8000-0000000000c2';

/** A SettingsService stand-in: `update` applies the patch at once, like the optimistic original. */
class FakeSettings {
  readonly current = signal<Settings | null>(null);
  readonly settings = this.current.asReadonly();
  readonly load = vi.fn(async () => {
    const loaded = fakeSettings({ [PRESETS_SETTING]: [] });
    this.current.set(loaded);
    return loaded;
  });
  readonly update = vi.fn(async (patch: SettingsPatch) => {
    const next = { ...(this.current() ?? fakeSettings()), ...patch } as Settings;
    this.current.set(next);
    return next;
  });

  stored(): unknown[] {
    return (this.current()?.[PRESETS_SETTING] as string[]).map((text) => JSON.parse(text));
  }
}

describe('FilterPresets', () => {
  let settings: FakeSettings;
  let presets: FilterPresets;

  function withStored(texts: unknown[]): void {
    settings.current.set(fakeSettings({ [PRESETS_SETTING]: texts }));
  }

  beforeEach(() => {
    settings = new FakeSettings();
    TestBed.configureTestingModule({
      providers: [{ provide: SettingsService, useValue: settings }],
    });
    presets = TestBed.inject(FilterPresets);
  });

  it('has no presets before the settings are loaded or when the setting is missing', () => {
    expect(presets.presets()).toEqual([]);
    settings.current.set(fakeSettings());
    expect(presets.presets()).toEqual([]);
  });

  it('reads the presets from the setting in their saved order, with their filters parsed', () => {
    withStored([
      JSON.stringify({ name: 'Calls', filter: { context: PHONE, maxMinutes: 15 } }),
      JSON.stringify({ name: 'Deep work', filter: { energy: 'HIGH', creativity: 'CREATIVE' } }),
    ]);

    expect(presets.presets()).toEqual([
      { name: 'Calls', filter: { context: PHONE, maxMinutes: 15 } },
      { name: 'Deep work', filter: { energy: 'HIGH', creativity: 'CREATIVE' } },
    ]);
  });

  it('skips entries that are not valid presets and drops invalid filter values', () => {
    withStored([
      '{not json',
      'null',
      '"just text"',
      42,
      JSON.stringify({ filter: { context: PHONE } }),
      JSON.stringify({ name: '   ', filter: {} }),
      JSON.stringify({ name: 7, filter: {} }),
      JSON.stringify({ name: 'No filter' }),
      JSON.stringify({ name: 'Odd filter', filter: 'context=x' }),
      JSON.stringify({
        name: 'Old values',
        filter: { energy: 'HUGE', maxMinutes: -5, due: 'week' },
      }),
    ]);

    expect(presets.presets()).toEqual([
      { name: 'No filter', filter: {} },
      { name: 'Odd filter', filter: {} },
      { name: 'Old values', filter: { due: 'week' } },
    ]);
  });

  it('ignores a setting that is not a list', () => {
    settings.current.set(fakeSettings({ [PRESETS_SETTING]: 'oops' }));
    expect(presets.presets()).toEqual([]);
  });

  it('saves a new preset at the end with a trimmed name and a normalized filter', async () => {
    withStored([JSON.stringify({ name: 'Calls', filter: { context: PHONE } })]);

    await presets.save('  Errands  ', { due: 'today', context: HOME, area: undefined });

    expect(settings.update).toHaveBeenCalledTimes(1);
    const patch = settings.update.mock.calls[0][0] as Record<string, string[]>;
    // Keys in URL order, empty ones dropped.
    expect(patch[PRESETS_SETTING]).toEqual([
      JSON.stringify({ name: 'Calls', filter: { context: PHONE } }),
      JSON.stringify({ name: 'Errands', filter: { context: HOME, due: 'today' } }),
    ]);
    expect(presets.presets().map((preset) => preset.name)).toEqual(['Calls', 'Errands']);
  });

  it('replaces a preset of the same name in any case, in place', async () => {
    withStored([
      JSON.stringify({ name: 'Calls', filter: { context: PHONE } }),
      JSON.stringify({ name: 'Deep work', filter: { energy: 'HIGH' } }),
    ]);

    await presets.save('calls ', { context: PHONE, maxMinutes: 5 });

    expect(settings.stored()).toEqual([
      { name: 'calls', filter: { context: PHONE, maxMinutes: 5 } },
      { name: 'Deep work', filter: { energy: 'HIGH' } },
    ]);
  });

  it('saves the first preset when the setting is still empty', async () => {
    withStored([]);
    await presets.save('Quick', { maxMinutes: 15 });
    expect(settings.stored()).toEqual([{ name: 'Quick', filter: { maxMinutes: 15 } }]);
  });

  it('removes a preset by name in any case and keeps the others', async () => {
    withStored([
      JSON.stringify({ name: 'Calls', filter: { context: PHONE } }),
      JSON.stringify({ name: 'Deep work', filter: { energy: 'HIGH' } }),
    ]);

    await presets.remove(' CALLS');

    expect(settings.stored()).toEqual([{ name: 'Deep work', filter: { energy: 'HIGH' } }]);
  });

  it('rewrites unreadable entries away when it stores the presets', async () => {
    withStored(['{broken', JSON.stringify({ name: 'Calls', filter: { context: PHONE } })]);
    await presets.remove('Nothing of that name');
    expect(settings.stored()).toEqual([{ name: 'Calls', filter: { context: PHONE } }]);
  });

  it('passes a failed save on to the caller', async () => {
    withStored([]);
    const error = new Error('offline');
    settings.update.mockRejectedValueOnce(error);
    await expect(presets.save('Calls', { context: PHONE })).rejects.toBe(error);
  });

  it('refuses to save or delete while the settings cannot be loaded, so no preset is lost', async () => {
    const error = new Error('offline');
    settings.load.mockRejectedValue(error);

    await expect(presets.save('Calls', { context: PHONE })).rejects.toBe(error);
    await expect(presets.remove('Calls')).rejects.toBe(error);

    expect(settings.update).not.toHaveBeenCalled();
  });

  it('loads the settings before saving, keeping the presets stored on the server', async () => {
    settings.load.mockImplementationOnce(async () => {
      const loaded = fakeSettings({
        [PRESETS_SETTING]: [JSON.stringify({ name: 'Deep work', filter: { energy: 'HIGH' } })],
      });
      settings.current.set(loaded);
      return loaded;
    });

    await presets.save('Calls', { context: PHONE });

    expect(settings.load).toHaveBeenCalledTimes(1);
    expect(settings.update).toHaveBeenCalledTimes(1);
    expect(settings.stored()).toEqual([
      { name: 'Deep work', filter: { energy: 'HIGH' } },
      { name: 'Calls', filter: { context: PHONE } },
    ]);
  });

  it('loads the settings only when they are not loaded yet', async () => {
    await presets.ensureLoaded();
    expect(settings.load).toHaveBeenCalledTimes(1);

    await presets.ensureLoaded();
    expect(settings.load).toHaveBeenCalledTimes(1);
  });
});

describe('sameName', () => {
  it('compares names ignoring case and surrounding spaces', () => {
    expect(sameName(' Calls ', 'calls')).toBe(true);
    expect(sameName('Calls', 'Call')).toBe(false);
  });
});
