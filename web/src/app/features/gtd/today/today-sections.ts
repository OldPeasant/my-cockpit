import {
  EnvironmentProviders,
  InjectionToken,
  makeEnvironmentProviders,
  Provider,
  Type,
} from '@angular/core';

/**
 * A section another feature adds to the Today view (SPEC.md §5.3: habits due; today's calendar
 * events are built in, above the tasks). The component renders the whole section, heading
 * included; sections show in `order` (lower first) after the task sections.
 */
export interface TodaySection {
  readonly id: string;
  readonly order: number;
  readonly component: Type<unknown>;
}

/** The extension slots of the Today view (multi provider), see {@link provideTodaySection}. */
export const TODAY_SECTIONS = new InjectionToken<readonly TodaySection[]>('TODAY_SECTIONS');

/**
 * Adds a section to the Today view, e.g. in the feature's providers (`app.routes.ts` shell
 * providers or `today.routes.ts`):
 *
 * ```ts
 * provideTodaySection({ id: 'habits', order: 10, component: TodayHabits })
 * ```
 */
export function provideTodaySection(section: TodaySection): EnvironmentProviders {
  return makeEnvironmentProviders([todaySectionProvider(section)]);
}

/** {@link provideTodaySection} as a plain provider (for component or test providers). */
export function todaySectionProvider(section: TodaySection): Provider {
  return { provide: TODAY_SECTIONS, useValue: section, multi: true };
}
