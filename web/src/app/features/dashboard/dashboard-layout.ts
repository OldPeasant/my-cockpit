import { computed, inject, Injectable, signal } from '@angular/core';
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

/** A layout change waiting for its save to start. */
interface PendingChange {
  readonly apply: (layout: readonly WidgetSlot[]) => readonly WidgetSlot[];
}

/**
 * Which dashboard widgets show, in which order: stored in the user settings (SPEC.md §4.7), so
 * every device has the same dashboard. Changes are optimistic like every settings change.
 *
 * Changes are saved one after the other, and each one is applied to the layout its predecessors
 * left: quick clicks while a save is unanswered build on each other, and a refused change drops
 * out without taking the later ones with it. Until its save starts (a reset: until its save
 * settles), a change shows through {@link slots} on top of the stored layout.
 */
@Injectable({ providedIn: 'root' })
export class DashboardLayout {
  private readonly settings = inject(SettingsService);
  /** Changes made but not handed to the settings service yet, oldest first. */
  private readonly pending = signal<readonly PendingChange[]>([]);
  private saving: Promise<unknown> = Promise.resolve();

  /** The stored layout (shown optimistically while saved); the default before the settings load. */
  private readonly stored = computed((): readonly WidgetSlot[] => {
    const settings = this.settings.settings();
    return settings ? parseLayout(settings[LAYOUT_SETTING]) : DEFAULT_LAYOUT;
  });
  /** Every widget in its order, with the changes still waiting to be saved. */
  readonly slots = computed((): readonly WidgetSlot[] =>
    this.pending().reduce((layout, change) => change.apply(layout), this.stored()),
  );
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
    return this.change(() => DEFAULT_LAYOUT, true);
  }

  /**
   * Shows `apply` at once and saves it applied to the stored layout once the earlier changes are
   * saved (or refused). The settings are loaded first: a layout built from the default would
   * overwrite the stored one. `reset` saves `null`, the setting's default.
   */
  private change(apply: PendingChange['apply'], reset = false): Promise<unknown> {
    const change: PendingChange = { apply };
    this.pending.update((pending) => [...pending, change]);
    const loaded = this.ensureLoaded();
    loaded.catch(() => undefined);
    const result = this.saving
      .then(() => loaded)
      .then(() => {
        if (reset) {
          // The settings service cannot show a reset (only the response knows the default), so
          // the change keeps showing until its save settles; it is idempotent on any layout.
          return this.settings.update({ [LAYOUT_SETTING]: null });
        }
        const value = serializeLayout(apply(this.stored()));
        // The settings service shows the saved value from now on, and rolls it back on failure.
        this.drop(change);
        return this.settings.update({ [LAYOUT_SETTING]: value });
      })
      .finally(() => this.drop(change));
    this.saving = result.catch(() => undefined);
    return result;
  }

  private drop(change: PendingChange): void {
    if (this.pending().includes(change)) {
      this.pending.update((pending) => pending.filter((other) => other !== change));
    }
  }
}
