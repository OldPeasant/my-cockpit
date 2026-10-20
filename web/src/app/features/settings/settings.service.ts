import { HttpContext } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Api, getSettings, Settings, SettingsPatch, updateSettings } from '../../api/generated';
import { ApiError } from '../../core/http';
import { ThemeService } from '../../shell/theme.service';

/** HTTP status of a stale `If-Match` (another tab or device changed the settings meanwhile). */
const PRECONDITION_FAILED = 412;

/**
 * The user's settings (SPEC.md §4.7): cached from `GET /api/v1/settings`, changed with
 * `PATCH` + `If-Match`. {@link update} is optimistic: the new values show (and the theme applies)
 * at once and are rolled back when the server refuses them.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly api = inject(Api);
  private readonly theme = inject(ThemeService);

  private readonly current = signal<Settings | null>(null);
  private etag: string | undefined;
  private saving: Promise<unknown> = Promise.resolve();
  /** Counts every change of {@link current}, so a load can tell whether it answers too late. */
  private version = 0;
  /** PATCHes sent and not answered yet. */
  private inFlight = 0;

  /** `null` until loaded. */
  readonly settings = this.current.asReadonly();

  /** Loads the settings from the server and applies its theme, which wins over the local one. */
  async load(context?: HttpContext): Promise<Settings> {
    const started = this.version;
    const response = await this.api.invoke$Response(getSettings, undefined, context);
    const current = this.current();
    // An answer sent before a change shown since (or while a save may still change the server)
    // is older than what is shown: it would bring back old values, the old theme and ETag.
    if (current && (this.version !== started || this.inFlight > 0)) return current;
    this.accept(response.body, response.headers.get('ETag'));
    this.theme.setMode(response.body.theme);
    return response.body;
  }

  /**
   * Shows `patch` at once, then saves it. On an error the previous values (and theme) come back
   * and the {@link ApiError} is rethrown; after a 412 the latest server state is reloaded, so the
   * next change starts from it.
   */
  update(patch: SettingsPatch): Promise<Settings> {
    // Saves run one after the other, so each sends the ETag the previous one returned.
    const result = this.saving.then(() => this.save(patch));
    this.saving = result.catch(() => undefined);
    return result;
  }

  private async save(patch: SettingsPatch): Promise<Settings> {
    const previous = this.current() ?? (await this.load());
    this.show({ ...previous, ...withoutResets(patch) });
    if (patch.theme) this.theme.setMode(patch.theme);
    this.inFlight++;
    try {
      const response = await this.api.invoke$Response(updateSettings, {
        'If-Match': this.etag,
        body: patch,
      });
      this.inFlight--;
      this.accept(response.body, response.headers.get('ETag'));
      this.theme.setMode(response.body.theme);
      return response.body;
    } catch (error) {
      this.inFlight--;
      this.show(previous);
      this.theme.setMode(previous.theme);
      if (error instanceof ApiError && error.status === PRECONDITION_FAILED) {
        await this.load().catch(() => undefined);
      }
      throw error;
    }
  }

  private accept(settings: Settings, etag: string | null): void {
    this.show(settings);
    this.etag = etag ?? undefined;
  }

  private show(settings: Settings): void {
    this.current.set(settings);
    this.version++;
  }
}

/** A `null` in a patch resets to the server default, which only the response knows. */
function withoutResets(patch: SettingsPatch): Partial<Settings> {
  return Object.fromEntries(Object.entries(patch).filter(([, value]) => value !== null));
}
