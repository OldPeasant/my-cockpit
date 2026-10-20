import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { Api, AreaResponse, listAreas, TaskResponse } from '../../../api/generated';
import { ContextStore, localDate, ProjectStore, TaskStore } from '../data';
import {
  CREATIVITY_OPTIONS,
  ENERGY_OPTIONS,
  ESTIMATE_OPTIONS,
  estimateLabel,
  Segmented,
  SegmentedOption,
  TaskRow,
} from '../ui';
import {
  FilterPreset,
  FilterPresets,
  MAX_PRESET_NAME,
  MAX_PRESETS,
  sameName,
} from './filter-presets';
import {
  DueWindow,
  filterParams,
  inArea,
  isEmptyFilter,
  NextFilter,
  parseFilter,
  sameFilter,
  taskQuery,
} from './next-filter';

/**
 * The Next Actions view (SPEC.md §5.3): open NEXT tasks, filtered by context, energy, creativity,
 * maximum estimate, area/project and due window. The filter lives in the URL query; saved presets
 * live in the settings ({@link FilterPresets}).
 */
@Component({
  selector: 'app-next-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    Segmented,
    TaskRow,
  ],
  providers: [TaskStore],
  templateUrl: './next-view.html',
  styleUrl: './next-view.scss',
})
export default class NextView {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(Api);
  protected readonly store = inject(TaskStore);
  protected readonly projects = inject(ProjectStore);
  protected readonly contexts = inject(ContextStore);
  protected readonly presetStore = inject(FilterPresets);

  /** The filter of the URL. */
  protected readonly filter = toSignal(
    this.route.queryParamMap.pipe(
      map((params) =>
        parseFilter(Object.fromEntries(params.keys.map((key) => [key, params.get(key)]))),
      ),
    ),
    { initialValue: {} as NextFilter },
  );
  protected readonly filtered = computed(() => !isEmptyFilter(this.filter()));
  protected readonly today = localDate();

  protected readonly areas = signal<readonly AreaResponse[]>([]);
  /** Active projects, of the chosen area if there is one. */
  protected readonly projectChoices = computed(() => {
    const area = this.filter().area;
    return this.projects
      .items()
      .filter((project) => project.status === 'ACTIVE' && (!area || project.areaId === area));
  });

  /** The tasks shown: the server's answer for the filter, narrowed to the area. */
  protected readonly tasks = computed(() => {
    const filter = this.filter();
    const projects = this.projects.byId();
    return this.store.items().filter((task) => inArea(task, filter, projects));
  });

  protected readonly contextOptions = computed((): SegmentedOption<string>[] =>
    this.contexts.items().map((context) => ({ value: context.id, label: context.name })),
  );
  protected readonly energyOptions = ENERGY_OPTIONS;
  protected readonly creativityOptions = CREATIVITY_OPTIONS;
  protected readonly estimateOptions: readonly SegmentedOption<number>[] = ESTIMATE_OPTIONS.map(
    (minutes) => ({ value: minutes, label: `≤ ${estimateLabel(minutes)}` }),
  );
  protected readonly dueOptions: readonly SegmentedOption<DueWindow>[] = [
    { value: 'overdue', label: $localize`:@@gtd.next.due.overdue:Overdue` },
    { value: 'today', label: $localize`:@@gtd.next.due.today:Today` },
    { value: 'week', label: $localize`:@@gtd.next.due.week:Next 7 days` },
  ];
  protected readonly labels = {
    context: $localize`:@@gtd.next.filter.context:Context`,
    energy: $localize`:@@gtd.next.filter.energy:Energy`,
    creativity: $localize`:@@gtd.next.filter.creativity:Creativity`,
    estimate: $localize`:@@gtd.next.filter.estimate:Time available`,
    due: $localize`:@@gtd.next.filter.due:Due`,
  };

  /** The preset matching the current filter, if any. */
  protected readonly activePreset = computed(() =>
    this.presetStore.presets().find((preset) => sameFilter(preset.filter, this.filter())),
  );
  /** Name typed into the "save preset" form; `null` while the form is closed. */
  protected readonly presetName = signal<string | null>(null);
  protected readonly presetError = signal<string | null>(null);
  protected readonly maxPresetName = MAX_PRESET_NAME;

  constructor() {
    effect(() => {
      const query = taskQuery(this.filter());
      untracked(() => void this.store.load(query));
    });
    void this.projects.ensureLoaded();
    void this.contexts.ensureLoaded();
    void this.presetStore.ensureLoaded().catch(() => undefined);
    void this.api
      .invoke(listAreas)
      .then((areas) => this.areas.set(areas.items))
      .catch(() => undefined);
  }

  /** Changes one filter field (`undefined` removes it); the URL changes, the list follows. */
  protected set<K extends keyof NextFilter>(key: K, value: NextFilter[K]): void {
    const next: NextFilter = { ...this.filter(), [key]: value ?? undefined };
    if (key === 'area' && value) {
      // A project of another area would show nothing.
      const project = next.project ? this.projects.find(next.project) : undefined;
      if (project && project.areaId !== value) delete (next as { project?: string }).project;
    }
    this.apply(next);
  }

  protected apply(filter: NextFilter): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: filterParams(filter),
      queryParamsHandling: 'merge',
    });
  }

  protected clear(): void {
    this.apply({});
  }

  protected applyPreset(preset: FilterPreset): void {
    this.apply(this.activePreset() === preset ? {} : preset.filter);
  }

  protected openSave(): void {
    this.presetError.set(null);
    this.presetName.set(this.activePreset()?.name ?? '');
  }

  protected cancelSave(): void {
    this.presetName.set(null);
    this.presetError.set(null);
  }

  protected async savePreset(): Promise<void> {
    const name = (this.presetName() ?? '').trim();
    if (!name) {
      this.presetError.set($localize`:@@gtd.next.preset.nameRequired:Enter a name for the preset.`);
      return;
    }
    const presets = this.presetStore.presets();
    if (presets.length >= MAX_PRESETS && !presets.some((preset) => sameName(preset.name, name))) {
      this.presetError.set(
        $localize`:@@gtd.next.preset.tooMany:You can save up to ${MAX_PRESETS}:max: presets. Delete one first.`,
      );
      return;
    }
    try {
      await this.presetStore.save(name, this.filter());
      this.presetName.set(null);
      this.presetError.set(null);
    } catch {
      this.presetError.set(
        $localize`:@@gtd.next.preset.saveFailed:The preset could not be saved. Try again.`,
      );
    }
  }

  protected async removePreset(preset: FilterPreset): Promise<void> {
    await this.presetStore.remove(preset.name).catch(() => undefined);
  }

  protected toggleDone(task: TaskResponse): void {
    void (task.completedAt ? this.store.uncomplete(task) : this.store.complete(task)).catch(
      () => undefined,
    );
  }

  protected open(task: TaskResponse): void {
    void this.router.navigate(['/tasks', task.id]);
  }

  protected removeLabel(name: string): string {
    return $localize`:@@gtd.next.preset.remove:Delete preset “${name}:name:”`;
  }
}
