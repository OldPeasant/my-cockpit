import { TaskResponse } from '../../../api/generated';

export type Energy = NonNullable<TaskResponse['energy']>;
export type Creativity = NonNullable<TaskResponse['creativity']>;

/** Energy levels, low to high, with their labels. */
export const ENERGY_OPTIONS: readonly { readonly value: Energy; readonly label: string }[] = [
  { value: 'LOW', label: $localize`:@@gtd.energy.low:Low` },
  { value: 'MEDIUM', label: $localize`:@@gtd.energy.medium:Medium` },
  { value: 'HIGH', label: $localize`:@@gtd.energy.high:High` },
];

/** Creativity levels with their default labels (SPEC.md §5.1). */
export const CREATIVITY_OPTIONS: readonly { readonly value: Creativity; readonly label: string }[] =
  [
    { value: 'ROUTINE', label: $localize`:@@gtd.creativity.routine:Routine` },
    { value: 'MIXED', label: $localize`:@@gtd.creativity.mixed:Mixed` },
    { value: 'CREATIVE', label: $localize`:@@gtd.creativity.creative:Creative` },
  ];

/** Quick picks of the time estimate in minutes (SPEC.md §5.1: 5, 15, 30, 60, 120+). */
export const ESTIMATE_OPTIONS: readonly number[] = [5, 15, 30, 60, 120];

export function energyLabel(energy: Energy): string {
  return ENERGY_OPTIONS.find((option) => option.value === energy)?.label ?? energy;
}

export function creativityLabel(creativity: Creativity): string {
  return CREATIVITY_OPTIONS.find((option) => option.value === creativity)?.label ?? creativity;
}

/** A time estimate such as `15 min`, `1 h`, `1 h 30 min`. */
export function estimateLabel(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return $localize`:@@gtd.estimate.minutes:${minutes}:minutes: min`;
  if (rest === 0) return $localize`:@@gtd.estimate.hours:${hours}:hours: h`;
  return $localize`:@@gtd.estimate.hoursMinutes:${hours}:hours: h ${rest}:minutes: min`;
}
