import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createHabit,
  HabitRequest,
  HabitResponse,
  listHabits,
  setHabitCheck,
  updateHabit,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/**
 * Habits (SPEC.md §8.5). Changes send the habit's version as `If-Match`; a check answers with the
 * habit and its new stats. Errors are not toasted: the Habits view shows them next to the habit.
 */
@Injectable({ providedIn: 'root' })
export class HabitsService {
  private readonly api = inject(Api);

  /** The active habits, or the archived ones, by name. */
  async list(archived: boolean): Promise<HabitResponse[]> {
    return (await this.api.invoke(listHabits, { archived }, silent())).items;
  }

  create(body: HabitRequest): Promise<HabitResponse> {
    return this.api.invoke(createHabit, { body }, silent());
  }

  update(habit: HabitResponse, body: HabitRequest): Promise<HabitResponse> {
    return this.api.invoke(
      updateHabit,
      { id: habit.id, 'If-Match': `"${habit.version}"`, body },
      silent(),
    );
  }

  /** Sets how often `habit` was done on `date` (`yyyy-MM-dd`); 0 removes the check. */
  setCheck(habit: HabitResponse, date: string, count: number): Promise<HabitResponse> {
    return this.api.invoke(setHabitCheck, { id: habit.id, date, body: { count } }, silent());
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
