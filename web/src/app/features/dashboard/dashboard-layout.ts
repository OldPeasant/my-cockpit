import { computed, inject, Injectable } from '@angular/core';
import { SettingsService } from '../settings/settings.service';

/** The setting holding the layout (server: `DashboardSettings.DASHBOARD_WIDGETS`). */
export const LAYOUT_SETTING = 'dashboardWidgets';
/** Suffix of a hidden widget's entry in {@link LAYOUT_SETTING}. */
const HIDDEN = ':hidden';

/** The widgets of the dashboard (SPEC.md §8.7), in their default order. */
export const WIDGET_IDS = [
  'today',
  'inbox',
  'upcoming',
  'waitingOverdue',
  'stalledProjects',
  'weeklyReview',
  'readLater',
  'pinnedNotes',
] as const;

export type WidgetId = (typeof WIDGET_IDS)[number];

/** One widget's place on the dashboard. */
export interface WidgetSlot {
  readonly id: WidgetId;
  readonly visible: boolean;
}

/** The default layout: every widget, shown, in {@link WIDGET_IDS} order. */
export const DEFAULT_LAYOUT: readonly WidgetSlot[] = WIDGET_IDS.map((id) => ({
  id,
  visible: true,
}));

/**
 * Reads the stored setting: the listed widgets in their order (unknown or repeated ids are
 * skipped), then the widgets missing from it (added in a later version), shown.
 */
export function parseLayout(stored: unknown): WidgetSlot[] {
  const slots: WidgetSlot[] = [];
  const seen = new Set<WidgetId>();
  for (const entry of Array.isArray(stored) ? stored : []) {
    if (typeof entry !== 'string') continue;
    const hidden = entry.endsWith(HIDDEN);
    const id = hidden ? entry.slice(0, -HIDDEN.length) : entry;
    if (!isWidgetId(id) || seen.has(id)) continue;
    seen.add(id);
    slots.push({ id, visible: !hidden });
  }
  for (const id of WIDGET_IDS) {
    if (!seen.has(id)) slots.push({ id, visible: true });
  }
  return slots;
}

/** The setting value of `layout`. */
export function serializeLayout(layout: readonly WidgetSlot[]): string[] {
  return layout.map((slot) => (slot.visible ? slot.id : `${slot.id}${HIDDEN}`));
}

/** `layout` with the widget `id` moved by `offset` places (clamped to the ends). */
export function moveWidget(
  layout: readonly WidgetSlot[],
  id: WidgetId,
  offset: number,
): WidgetSlot[] {
  const result = [...layout];
  const from = result.findIndex((slot) => slot.id === id);
  if (from < 0) return result;
  const to = Math.min(Math.max(from + offset, 0), result.length - 1);
  const [slot] = result.splice(from, 1);
  result.splice(to, 0, slot);
  return result;
}

/** `layout` with the widget `id` shown or hidden. */
export function setVisible(
  layout: readonly WidgetSlot[],
  id: WidgetId,
  visible: boolean,
): WidgetSlot[] {
  return layout.map((slot) => (slot.id === id ? { id, visible } : slot));
}

function isWidgetId(id: string): id is WidgetId {
  return (WIDGET_IDS as readonly string[]).includes(id);
}

/**
 * Which dashboard widgets show, in which order: stored in the user settings (SPEC.md §4.7), so
 * every device has the same dashboard. Changes are optimistic like every settings change.
 */
@Injectable({ providedIn: 'root' })
export class DashboardLayout {
  private readonly settings = inject(SettingsService);

  /** Every widget in its order; the default layout while the settings are not loaded. */
  readonly slots = computed((): readonly WidgetSlot[] => {
    const settings = this.settings.settings();
    return settings ? parseLayout(settings[LAYOUT_SETTING]) : DEFAULT_LAYOUT;
  });
  /** The ids of the shown widgets, in order. */
  readonly visible = computed(() =>
    this.slots()
      .filter((slot) => slot.visible)
      .map((slot) => slot.id),
  );
  /** Whether the settings are loaded (before, {@link slots} is only the default). */
  readonly loaded = computed(() => this.settings.settings() !== null);

  /** Loads the settings unless they are loaded (the shell loads them after login). */
  async ensureLoaded(): Promise<void> {
    if (!this.settings.settings()) await this.settings.load();
  }

  move(id: WidgetId, offset: number): Promise<unknown> {
    return this.change((layout) => moveWidget(layout, id, offset));
  }

  setVisible(id: WidgetId, visible: boolean): Promise<unknown> {
    return this.change((layout) => setVisible(layout, id, visible));
  }

  /** Back to the default layout (the setting's default, an empty list). */
  reset(): Promise<unknown> {
    return this.settings.update({ [LAYOUT_SETTING]: null });
  }

  /**
   * Saves `change` applied to the stored layout. The settings are loaded first: a layout built
   * from the default would overwrite the stored one.
   */
  private async change(change: (layout: readonly WidgetSlot[]) => WidgetSlot[]): Promise<unknown> {
    await this.ensureLoaded();
    return this.settings.update({ [LAYOUT_SETTING]: serializeLayout(change(this.slots())) });
  }
}
