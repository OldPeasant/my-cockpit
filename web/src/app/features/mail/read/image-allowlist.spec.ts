import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Settings, SettingsPatch } from '../../../api/generated';
import { SettingsService } from '../../settings/settings.service';
import { fakeSettings } from '../../settings/testing';
import { IMAGE_ALLOWLIST_KEY, ImageAllowlist, senderListed } from './image-allowlist';

class FakeSettingsService {
  readonly current = signal<Settings | null>(null);
  readonly settings = this.current.asReadonly();
  stored = fakeSettings();
  patches: SettingsPatch[] = [];
  loadError?: Error;
  updateError?: Error;

  load() {
    if (this.loadError) return Promise.reject(this.loadError);
    this.current.set(this.stored);
    return Promise.resolve(this.stored);
  }
  async update(patch: SettingsPatch) {
    this.patches.push(patch);
    await Promise.resolve();
    if (this.updateError) throw this.updateError;
    this.stored = { ...this.stored, ...(patch as Partial<Settings>) };
    this.current.set(this.stored);
    return this.stored;
  }
}

describe('senderListed', () => {
  it.each([
    [['max@example.test'], ' MAX@Example.test ', true],
    [['example.test'], 'max@example.test', true],
    [['@example.test'], 'max@EXAMPLE.test', true],
    [['example.test'], 'max@sub.example.test', false],
    [['other@example.test'], 'max@example.test', false],
    [['max@example.test'], '', false],
    [['max@example.test'], undefined, false],
    [[''], 'max@example.test', false],
  ] as const)('%j lists %j: %s', (senders, address, listed) => {
    expect(senderListed(senders, address)).toBe(listed);
  });
});

describe('ImageAllowlist', () => {
  let settings: FakeSettingsService;
  let list: ImageAllowlist;

  beforeEach(() => {
    localStorage.removeItem(IMAGE_ALLOWLIST_KEY);
    settings = new FakeSettingsService();
    TestBed.configureTestingModule({
      providers: [{ provide: SettingsService, useValue: settings }],
    });
    list = TestBed.inject(ImageAllowlist);
  });
  afterEach(() => localStorage.removeItem(IMAGE_ALLOWLIST_KEY));

  it('blocks until the settings are loaded, then follows them', async () => {
    settings.stored = fakeSettings({
      mailImagePolicy: 'block',
      mailImageAllowedSenders: ['max@example.test'],
    });
    expect(list.loadsImagesFrom('max@example.test')).toBe(false);
    await list.ready();
    expect(list.loadsImagesFrom('max@example.test')).toBe(true);
    expect(list.loadsImagesFrom('erika@example.test')).toBe(false);

    settings.current.set(fakeSettings({ mailImagePolicy: 'allow' }));
    expect(list.loadsImagesFrom('erika@example.test')).toBe(true);
    expect(list.has('erika@example.test')).toBe(false);
  });

  it('is ready without settings when they cannot be loaded', async () => {
    settings.loadError = new Error('offline');
    await expect(list.ready()).resolves.toBeUndefined();
    expect(list.loadsImagesFrom('max@example.test')).toBe(false);
  });

  it('adds a sender lower case, once, after loading the settings', async () => {
    settings.stored = fakeSettings({ mailImageAllowedSenders: ['erika@example.test'] });
    await list.add(' Max@Example.test ');
    await list.add('max@example.test');
    expect(settings.patches).toEqual([
      { mailImageAllowedSenders: ['erika@example.test', 'max@example.test'] },
    ]);
  });

  it('runs changes one after the other, each on the list the previous one saved', async () => {
    await list.ready();
    await Promise.all([list.add('a@example.test'), list.add('b@example.test')]);
    expect(settings.patches).toEqual([
      { mailImageAllowedSenders: ['a@example.test'] },
      { mailImageAllowedSenders: ['a@example.test', 'b@example.test'] },
    ]);
  });

  it('rejects a failed save and keeps going with the next change', async () => {
    await list.ready();
    settings.updateError = new Error('refused');
    await expect(list.add('a@example.test')).rejects.toThrow('refused');
    settings.updateError = undefined;
    await list.add('b@example.test');
    expect(settings.stored.mailImageAllowedSenders).toEqual(['b@example.test']);
  });

  it('remove takes every entry that allows the sender; removeEntry exactly one', async () => {
    settings.stored = fakeSettings({
      mailImageAllowedSenders: ['max@example.test', 'example.test', 'erika@example.org'],
    });
    await list.removeEntry('example.test');
    expect(settings.stored.mailImageAllowedSenders).toEqual([
      'max@example.test',
      'erika@example.org',
    ]);

    settings.stored = {
      ...settings.stored,
      mailImageAllowedSenders: ['MAX@example.test', '@example.test', 'x@y.test'],
    };
    settings.current.set(settings.stored);
    await list.remove('max@example.test');
    expect(settings.stored.mailImageAllowedSenders).toEqual(['x@y.test']);
  });

  it('does not save when nothing changes', async () => {
    await list.remove('nobody@example.test');
    expect(settings.patches).toEqual([]);
  });

  it('merges the old local list once, without duplicates, and removes it', async () => {
    settings.stored = fakeSettings({ mailImageAllowedSenders: ['example.test'] });
    localStorage.setItem(
      IMAGE_ALLOWLIST_KEY,
      JSON.stringify(['max@example.test', 'Erika@Example.org', ' ', 7]),
    );
    await list.ready();
    await list.ready();
    expect(settings.patches).toEqual([
      { mailImageAllowedSenders: ['example.test', 'erika@example.org'] },
    ]);
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
  });

  it.each(['not json', '{"a":1}', '[]'])(
    'removes an old local list %s without saving',
    async (value) => {
      localStorage.setItem(IMAGE_ALLOWLIST_KEY, value);
      await list.ready();
      expect(settings.patches).toEqual([]);
      expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
    },
  );

  it('does not reload settings that are already loaded', async () => {
    await list.ready();
    const load = vi.spyOn(settings, 'load');
    await list.ready();
    await list.add('max@example.test');
    expect(load).not.toHaveBeenCalled();
  });

  it('merges the old local list only once when several pages get ready at the same time', async () => {
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max@example.test']));
    await Promise.all([list.ready(), list.ready(), list.ready()]);
    expect(settings.patches).toEqual([{ mailImageAllowedSenders: ['max@example.test'] }]);
  });

  it('merges duplicates of the old local list (any case) into one entry', async () => {
    localStorage.setItem(
      IMAGE_ALLOWLIST_KEY,
      JSON.stringify(['max@example.test', 'MAX@example.test', ' max@example.test ']),
    );
    await list.ready();
    expect(settings.patches).toEqual([{ mailImageAllowedSenders: ['max@example.test'] }]);
  });

  it('removes an old local list whose entries are all allowed already, without saving', async () => {
    settings.stored = fakeSettings({ mailImageAllowedSenders: ['example.test'] });
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max@example.test']));
    await list.ready();
    expect(settings.patches).toEqual([]);
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
  });

  it('adds a sender after the old local list is merged, keeping both', async () => {
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['old@example.test']));
    await list.add('new@example.test');
    expect(settings.patches).toEqual([
      { mailImageAllowedSenders: ['old@example.test'] },
      { mailImageAllowedSenders: ['old@example.test', 'new@example.test'] },
    ]);
  });

  it('is ready when the browser storage cannot be read', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem');
    try {
      await expect(list.ready()).resolves.toBeUndefined();
      expect(settings.patches).toEqual([]);
      expect(list.policy()).toBe('block');
      expect(removeItem).not.toHaveBeenCalled();
    } finally {
      getItem.mockRestore();
      removeItem.mockRestore();
    }
  });

  it('does not replace the stored senders with a list built before the settings were loaded', async () => {
    settings.stored = fakeSettings({ mailImageAllowedSenders: ['erika@example.test'] });
    // The first load fails (a network blip); like SettingsService.update, the save loads the
    // settings itself when none are loaded, and by then the network is back.
    settings.loadError = new Error('offline');
    const update = settings.update.bind(settings);
    vi.spyOn(settings, 'update').mockImplementation(async (patch) => {
      settings.loadError = undefined;
      if (!settings.settings()) await settings.load();
      return update(patch);
    });
    await list.add('max@example.test').catch(() => undefined);
    // Saving ['max@example.test'] would drop erika@example.test on the server.
    expect(settings.stored.mailImageAllowedSenders).toContain('erika@example.test');
  });

  it('rejects a change without saving when the settings cannot be loaded', async () => {
    settings.loadError = new Error('offline');
    await expect(list.add('max@example.test')).rejects.toThrow();
    await expect(list.remove('max@example.test')).rejects.toThrow();
    expect(settings.patches).toEqual([]);
  });

  it('rejects removing a settings entry without saving, then saves from the loaded list once the settings load', async () => {
    settings.stored = fakeSettings({
      mailImageAllowedSenders: ['erika@example.test', 'example.org'],
    });
    settings.loadError = new Error('offline');
    await expect(list.removeEntry('example.org')).rejects.toThrow();
    expect(settings.patches).toEqual([]);

    settings.loadError = undefined;
    await list.add('max@example.test');
    expect(settings.patches).toEqual([
      { mailImageAllowedSenders: ['erika@example.test', 'example.org', 'max@example.test'] },
    ]);
  });

  it('keeps the old local list when reading it fails and merges it on the next page load', async () => {
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max@example.test']));
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    try {
      await list.ready();
    } finally {
      getItem.mockRestore();
    }
    expect(settings.patches).toEqual([]);
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBe('["max@example.test"]');

    // A new page load creates a new instance, which can read the storage again.
    const next = TestBed.runInInjectionContext(() => new ImageAllowlist());
    await next.ready();
    expect(settings.patches).toEqual([{ mailImageAllowedSenders: ['max@example.test'] }]);
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
  });

  it('keeps the old local list when merging fails and tries again next time', async () => {
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify(['max@example.test']));
    settings.updateError = new Error('refused');
    await list.ready();
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).not.toBeNull();

    settings.updateError = undefined;
    await list.ready();
    expect(settings.stored.mailImageAllowedSenders).toEqual(['max@example.test']);
    expect(localStorage.getItem(IMAGE_ALLOWLIST_KEY)).toBeNull();
  });
});
