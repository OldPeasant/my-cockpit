import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SETTINGS_SECTIONS } from './settings-sections';

/** The settings page: the list of sections beside the open one. */
@Component({
  selector: 'app-settings-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="settings">
      <header class="head">
        <h1 i18n="@@nav.settings">Settings</h1>
      </header>
      <nav class="sections" i18n-aria-label="@@settings.sections" aria-label="Settings sections">
        <ul>
          @for (section of sections; track section.path) {
            <li>
              <a
                [routerLink]="section.link"
                routerLinkActive="active"
                [routerLinkActiveOptions]="{ exact: true }"
                ariaCurrentWhenActive="page"
                >{{ section.label }}</a
              >
            </li>
          }
        </ul>
      </nav>
      <div class="section">
        <router-outlet />
      </div>
    </div>
  `,
  styles: `
    .settings {
      display: grid;
      grid-template-columns: 13rem minmax(0, 52rem);
      grid-template-areas:
        'head head'
        'nav section';
      column-gap: var(--app-space-5);
      padding: var(--app-space-5) var(--app-space-4);
    }
    .head {
      grid-area: head;
      margin-bottom: var(--app-space-4);
    }
    h1 {
      margin: 0;
      font: var(--mat-sys-headline-medium);
    }
    .sections {
      grid-area: nav;
    }
    ul {
      position: sticky;
      top: var(--app-space-4);
      margin: 0;
      padding: 0;
      list-style: none;
      border-left: 1px solid var(--app-border);
    }
    a {
      display: block;
      margin-left: -1px;
      padding: var(--app-space-2) var(--app-space-3);
      border-left: 2px solid transparent;
      color: var(--app-fg-muted);
      font: var(--mat-sys-body-large);
      text-decoration: none;
    }
    a:hover {
      color: var(--app-fg);
    }
    a.active {
      border-left-color: var(--app-accent);
      color: var(--app-fg);
      font-weight: 500;
    }
    a:focus-visible {
      outline: 2px solid var(--app-accent);
      outline-offset: -2px;
    }
    .section {
      grid-area: section;
      min-width: 0;
    }
    @media (max-width: 720px) {
      .settings {
        grid-template-columns: minmax(0, 1fr);
        grid-template-areas: 'head' 'nav' 'section';
        padding: var(--app-space-4) var(--app-space-3);
      }
      ul {
        position: static;
        display: flex;
        gap: var(--app-space-1);
        overflow-x: auto;
        margin-bottom: var(--app-space-4);
        border-left: 0;
        border-bottom: 1px solid var(--app-border);
      }
      a {
        margin: 0 0 -1px;
        border-left: 0;
        border-bottom: 2px solid transparent;
        white-space: nowrap;
      }
      a.active {
        border-bottom-color: var(--app-accent);
      }
    }
  `,
})
export default class SettingsLayout {
  protected readonly sections = Object.values(SETTINGS_SECTIONS).map((section) => ({
    ...section,
    link: section.path ? `/settings/${section.path}` : '/settings',
  }));
}
