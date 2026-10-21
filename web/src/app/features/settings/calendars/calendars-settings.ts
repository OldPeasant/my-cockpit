import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import {
  CalendarPatchRequest,
  CalendarResponse,
  CalendarSourceResponse,
  OAuthProviderResponse,
} from '../../../api/generated';
import { ApiError } from '../../../core/http';
import { CalendarSourcesService, OAuthSlug } from './calendar-sources.service';

/** What the "Add a calendar" form adds. */
export type AddKind = 'LOCAL' | 'CALDAV' | 'ICS_FEED' | 'GOOGLE' | 'MICROSOFT';

/** The color a new local calendar starts with. */
const NEW_CALENDAR_COLOR = '#3f7fbf';

/** Calendars of one source, in the order of the sources. */
interface SourceGroup {
  readonly source: CalendarSourceResponse | undefined;
  readonly name: string;
  readonly calendars: readonly CalendarResponse[];
}

/**
 * Settings → Calendars (SPEC.md §6.1): every calendar by source with its color, visibility and
 * the default for new events, and adding a local calendar, a CalDAV account, an ICS feed or a
 * Google/Microsoft account (OAuth redirect; the Integrations page finishes it).
 */
@Component({
  selector: 'app-calendars-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    ReactiveFormsModule,
  ],
  templateUrl: './calendars-settings.html',
  styleUrl: './calendars-settings.scss',
})
export default class CalendarsSettings implements OnInit {
  private readonly service = inject(CalendarSourcesService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly calendars = signal<CalendarResponse[] | null>(null);
  protected readonly sources = signal<CalendarSourceResponse[]>([]);
  protected readonly providers = signal<OAuthProviderResponse[]>([]);
  protected readonly loadError = signal<string | null>(null);
  /** Ids of the calendars with a change in flight. */
  protected readonly saving = signal<ReadonlySet<string>>(new Set());
  /** The last failed change, with the name of its calendar. */
  protected readonly saveError = signal<{ name: string; message: string } | null>(null);

  protected readonly kind = signal<AddKind>('LOCAL');
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  protected readonly addStatus = signal<string | null>(null);

  /** The last full reload started, and the newest one applied: an older answer is dropped. */
  private reloadsStarted = 0;
  private reloadApplied = 0;

  protected readonly local = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    color: [NEW_CALENDAR_COLOR],
  });
  protected readonly caldav = this.fb.group({
    url: ['', [Validators.required, Validators.pattern(/^\s*https?:\/\/\S+\s*$/i)]],
    username: ['', Validators.required],
    password: ['', Validators.required],
    name: ['', Validators.maxLength(200)],
  });
  protected readonly ics = this.fb.group({
    url: ['', [Validators.required, Validators.pattern(/^\s*(https?|webcals?):\/\/\S+\s*$/i)]],
    name: ['', Validators.maxLength(200)],
  });

  /** The calendars grouped by source, sources in creation order. */
  protected readonly groups = computed<SourceGroup[]>(() => {
    const calendars = this.calendars() ?? [];
    const sources = this.sources();
    const known = new Set(sources.map((s) => s.id));
    const groups: SourceGroup[] = sources
      .map((source) => ({
        source,
        name: source.type === 'LOCAL' ? $localize`:@@calendars.local:In the cockpit` : source.name,
        calendars: calendars.filter((c) => c.sourceId === source.id),
      }))
      .filter((group) => group.calendars.length > 0 || group.source.type !== 'LOCAL');
    const orphans = calendars.filter((c) => !known.has(c.sourceId));
    if (orphans.length) {
      groups.push({
        source: undefined,
        name: $localize`:@@calendars.other:Other`,
        calendars: orphans,
      });
    }
    return groups;
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    this.calendars.set(null);
    const id = ++this.reloadsStarted;
    try {
      const [calendars, sources, providers] = await Promise.all([
        this.service.calendars(),
        this.service.sources(),
        this.service.providers().catch(() => []),
      ]);
      this.providers.set(providers);
      this.apply(id, calendars, sources);
    } catch (error) {
      if (id >= this.reloadApplied) this.loadError.set(message(error));
    }
  }

  protected setColor(calendar: CalendarResponse, color: string): void {
    if (color.toLowerCase() === calendar.color?.toLowerCase()) return;
    void this.change(calendar, { color });
  }

  protected setVisible(calendar: CalendarResponse, visible: boolean): void {
    void this.change(calendar, { visible });
  }

  protected makeDefault(calendar: CalendarResponse): void {
    void this.change(calendar, { isDefault: true });
  }

  protected selectKind(kind: AddKind): void {
    this.kind.set(kind);
    this.addError.set(null);
    this.addStatus.set(null);
  }

  protected providerConfigured(provider: OAuthProviderResponse['provider']): boolean {
    return this.providers().some((p) => p.provider === provider && p.configured);
  }

  protected connect(provider: OAuthSlug): void {
    this.service.connect(provider);
  }

  protected async addLocal(): Promise<void> {
    if (!this.ready(this.local)) return;
    const { name, color } = this.local.getRawValue();
    await this.add(async () => {
      const created = await this.service.createLocal({ name: name.trim(), color });
      this.local.reset({ name: '', color: NEW_CALENDAR_COLOR });
      return (await this.reloadAfterAdd())
        ? $localize`:@@calendars.add.localDone:${created.name}:name: added.`
        : notShown(created.name);
    });
  }

  protected async addCaldav(): Promise<void> {
    if (!this.ready(this.caldav)) return;
    const { url, username, password, name } = this.caldav.getRawValue();
    await this.add(async () => {
      const source = await this.service.addSource({
        type: 'CALDAV',
        url: url.trim(),
        username: username.trim(),
        password,
        name: name.trim() || undefined,
      });
      this.caldav.reset();
      return this.fetchCalendars(source);
    });
  }

  protected async addIcs(): Promise<void> {
    if (!this.ready(this.ics)) return;
    const { url, name } = this.ics.getRawValue();
    await this.add(async () => {
      const source = await this.service.addSource({
        type: 'ICS_FEED',
        url: url.trim(),
        name: name.trim() || undefined,
      });
      this.ics.reset();
      return this.fetchCalendars(source);
    });
  }

  protected colorLabel(calendar: CalendarResponse): string {
    return $localize`:@@calendars.colorAria:Color of ${calendar.name}:name:`;
  }

  protected visibleLabel(calendar: CalendarResponse): string {
    return $localize`:@@calendars.visibleAria:Show ${calendar.name}:name: in the calendar`;
  }

  protected defaultLabel(calendar: CalendarResponse): string {
    return $localize`:@@calendars.defaultAria:Use ${calendar.name}:name: for new events`;
  }

  /** Syncs a new source at once, so its calendars show up here without waiting for the scheduler. */
  private async fetchCalendars(source: CalendarSourceResponse): Promise<string> {
    this.addStatus.set(
      $localize`:@@calendars.add.fetching:Added ${source.name}:name:. Fetching its calendars…`,
    );
    let synced = source;
    try {
      synced = await this.service.sync(source.id);
    } catch {
      // The scheduler syncs it later; the Integrations page shows how that went.
    }
    const shown = await this.reloadAfterAdd();
    const error = synced.sync?.lastError;
    if (error) {
      throw new SourceSyncError(
        $localize`:@@calendars.add.syncFailed:${source.name}:name: was added, but its calendars could not be fetched: ${error}:error:. Check the settings, then use Sync now under Integrations.`,
      );
    }
    return shown
      ? $localize`:@@calendars.add.sourceDone:${source.name}:name: added.`
      : notShown(source.name);
  }

  /**
   * Shows what an add created. The add itself succeeded, so a failed reload is not an add error
   * (the user would add it again); returns whether the list is current.
   */
  private async reloadAfterAdd(): Promise<boolean> {
    try {
      await this.reload();
      return true;
    } catch {
      return false;
    }
  }

  private ready(form: { invalid: boolean; markAllAsTouched(): void }): boolean {
    if (this.adding()) return false;
    if (form.invalid) {
      form.markAllAsTouched();
      return false;
    }
    return true;
  }

  private async add(run: () => Promise<string>): Promise<void> {
    this.adding.set(true);
    this.addError.set(null);
    this.addStatus.set(null);
    try {
      this.addStatus.set(await run());
    } catch (error) {
      this.addStatus.set(null);
      this.addError.set(error instanceof SourceSyncError ? error.message : addMessage(error));
    } finally {
      this.adding.set(false);
    }
  }

  private async change(calendar: CalendarResponse, body: CalendarPatchRequest): Promise<void> {
    this.saving.update((ids) => new Set(ids).add(calendar.id));
    this.saveError.set(null);
    try {
      const saved = await this.service.update(this.current(calendar), body);
      // A new default takes the flag from the old one (a new version there too): read both again.
      if (body.isDefault) await this.reload();
      else this.calendars.update((all) => all?.map((c) => (c.id === saved.id ? saved : c)) ?? null);
    } catch (error) {
      this.saveError.set({ name: calendar.name, message: saveMessage(error) });
      // Show what the server has: the toggle or color goes back, a concurrent change shows up.
      await this.reload().catch(() => undefined);
    } finally {
      this.saving.update((ids) => {
        const rest = new Set(ids);
        rest.delete(calendar.id);
        return rest;
      });
    }
  }

  private current(calendar: CalendarResponse): CalendarResponse {
    return this.calendars()?.find((c) => c.id === calendar.id) ?? calendar;
  }

  private async reload(): Promise<void> {
    const id = ++this.reloadsStarted;
    const [calendars, sources] = await Promise.all([
      this.service.calendars(),
      this.service.sources(),
    ]);
    this.apply(id, calendars, sources);
  }

  /**
   * Applies the answer of reload `id`, unless a newer reload was applied already. A calendar
   * saved while the reload was on its way has a newer version here than in the answer: it stays.
   */
  private apply(
    id: number,
    calendars: CalendarResponse[],
    sources: CalendarSourceResponse[],
  ): void {
    if (id < this.reloadApplied) return;
    this.reloadApplied = id;
    const shown = new Map((this.calendars() ?? []).map((c) => [c.id, c]));
    this.sources.set(sources);
    this.calendars.set(
      calendars.map((c) => {
        const mine = shown.get(c.id);
        return mine && mine.version > c.version ? mine : c;
      }),
    );
  }
}

/** The add worked, but the list could not be read again. */
function notShown(name: string): string {
  return $localize`:@@calendars.add.notShown:${name}:name: added. The list could not be updated; reload the page to see it.`;
}

/** A source was added but its first sync failed. */
class SourceSyncError extends Error {}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@calendars.error:Something went wrong.`;
}

function addMessage(error: unknown): string {
  if (error instanceof ApiError && error.errors.length) {
    return error.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
  }
  return message(error);
}

function saveMessage(error: unknown): string {
  if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
    return $localize`:@@calendars.changedElsewhere:It was changed elsewhere. The list now shows the current state; try again.`;
  }
  return message(error);
}
