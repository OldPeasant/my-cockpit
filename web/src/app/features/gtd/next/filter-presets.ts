import { computed, inject, Injectable } from '@angular/core';
import { SettingsService } from '../../settings/settings.service';
import { NextFilter, normalizeFilter, parseFilter } from './next-filter';

/** The setting holding the presets (server: `GtdSettings.NEXT_ACTION_PRESETS`), one JSON text each. */
export const PRESETS_SETTING = 'nextActionPresets';
/** Limits of the setting: at most 50 presets of at most 2000 characters each. */
export const MAX_PRESETS = 50;
export const MAX_PRESET_NAME = 80;

/** A saved filter of the Next Actions view, e.g. "Low energy @home ≤ 15 min". */
export interface FilterPreset {
  readonly name: string;
  readonly filter: NextFilter;
}

/**
 * The saved filter presets of the Next Actions view, stored in the user settings (SPEC.md §4.7), so
 * every device has them. Changes are optimistic like every settings change.
 */
@Injectable({ providedIn: 'root' })
export class FilterPresets {
  private readonly settings = inject(SettingsService);

  /** The presets in the order they were saved; entries that can't be read are skipped. */
  readonly presets = computed((): readonly FilterPreset[] => {
    const stored: unknown = this.settings.settings()?.[PRESETS_SETTING];
    return Array.isArray(stored) ? stored.flatMap(readPreset) : [];
  });

  /** Loads the settings unless they are loaded (the shell loads them after login). */
  async ensureLoaded(): Promise<void> {
    if (!this.settings.settings()) await this.settings.load();
  }

  /**
   * Saves `filter` as `name`; a preset of the same name (any case) is replaced in place. The
   * settings are loaded first: a list built from unloaded settings would wipe the stored presets.
   */
  async save(name: string, filter: NextFilter): Promise<unknown> {
    await this.ensureLoaded();
    const preset: FilterPreset = { name: name.trim(), filter: normalizeFilter(filter) };
    const presets = [...this.presets()];
    const index = presets.findIndex((other) => sameName(other.name, preset.name));
    if (index >= 0) presets[index] = preset;
    else presets.push(preset);
    return this.store(presets);
  }

  /** Deletes the preset called `name`; like {@link save}, it loads the settings first. */
  async remove(name: string): Promise<unknown> {
    await this.ensureLoaded();
    return this.store(this.presets().filter((preset) => !sameName(preset.name, name)));
  }

  private store(presets: readonly FilterPreset[]): Promise<unknown> {
    return this.settings.update({
      [PRESETS_SETTING]: presets.map((preset) => JSON.stringify(preset)),
    });
  }
}

export function sameName(a: string, b: string): boolean {
  return a.trim().toLocaleLowerCase() === b.trim().toLocaleLowerCase();
}

function readPreset(text: unknown): FilterPreset[] {
  if (typeof text !== 'string') return [];
  try {
    const value = JSON.parse(text) as { name?: unknown; filter?: unknown };
    if (typeof value.name !== 'string' || !value.name.trim()) return [];
    const filter = value.filter && typeof value.filter === 'object' ? value.filter : {};
    return [{ name: value.name, filter: parseFilter(filter as Record<string, unknown>) }];
  } catch {
    return [];
  }
}
