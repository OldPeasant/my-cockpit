import { DatePipe } from '@angular/common';
import { HttpContext } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AndroidAppVersion,
  Api,
  downloadAndroidApp,
  getAndroidAppVersion,
} from '../../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../../core/http';
import { formatBytes } from '../../files/upload/format-bytes';

type State =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly app: AndroidAppVersion }
  | { readonly kind: 'none' }
  | { readonly kind: 'failed' };

/**
 * The Android app this server offers (SPEC.md §11): its version from `GET /download/android/version`
 * and a link to `GET /download/android`, which sends the APK. A server without an APK (404) says so
 * instead of offering a link that would fail. Errors are shown here, not toasted.
 */
@Component({
  selector: 'app-android-app-download',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  template: `
    @switch (state().kind) {
      @case ('loading') {
        <p class="note" role="status" i18n="@@settings.androidApp.loading">Checking…</p>
      }
      @case ('none') {
        <p class="note" i18n="@@settings.androidApp.none">
          No Android app is available on this server.
        </p>
      }
      @case ('failed') {
        <p class="note" role="alert">
          <ng-container i18n="@@settings.androidApp.failed"
            >The Android app version could not be loaded.</ng-container
          >
          <button type="button" class="retry" (click)="load()" i18n="@@settings.retry">
            Try again
          </button>
        </p>
      }
      @case ('ready') {
        @if (app(); as app) {
          <p class="version">
            @if (app.versionName) {
              <ng-container i18n="@@settings.androidApp.version"
                >Version {{ app.versionName }}</ng-container
              >
            } @else {
              <ng-container i18n="@@settings.androidApp.versionUnknown"
                >Version unknown</ng-container
              >
            }
          </p>
          <p class="note" i18n="@@settings.androidApp.details">
            {{ size(app) }}, updated {{ app.updatedAt | date: 'mediumDate' }}
          </p>
          <a class="download" [href]="downloadUrl" download i18n="@@settings.androidApp.download"
            >Download APK</a
          >
        }
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }

    p {
      margin: 0;
    }

    .note {
      color: var(--app-fg-muted);
      font: var(--mat-sys-body-medium);
    }

    .version {
      font: var(--mat-sys-title-small);
    }

    .download {
      display: inline-block;
      margin-top: var(--app-space-2);
      color: var(--app-accent);
      font: var(--mat-sys-label-large);
    }

    .retry {
      margin-left: var(--app-space-2);
      padding: 0;
      border: 0;
      background: none;
      color: var(--app-accent);
      font: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
  `,
})
export class AndroidAppDownload {
  private readonly api = inject(Api);

  protected readonly state = signal<State>({ kind: 'loading' });
  protected readonly downloadUrl = `${this.api.rootUrl}${downloadAndroidApp.PATH}`;

  constructor() {
    void this.load();
  }

  protected app(): AndroidAppVersion | null {
    const state = this.state();
    return state.kind === 'ready' ? state.app : null;
  }

  protected size(app: AndroidAppVersion): string {
    return formatBytes(app.size);
  }

  protected async load(): Promise<void> {
    this.state.set({ kind: 'loading' });
    try {
      const app = await this.api.invoke(
        getAndroidAppVersion,
        undefined,
        new HttpContext().set(SILENT_ERRORS, true),
      );
      this.state.set({ kind: 'ready', app });
    } catch (error) {
      this.state.set({
        kind: error instanceof ApiError && error.status === 404 ? 'none' : 'failed',
      });
    }
  }
}
