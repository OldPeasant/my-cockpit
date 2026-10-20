# Feature folders

One folder per server module / navigation section (SPEC.md §9.1). `app.routes.ts` lazy-loads each
routes file inside the shell layout (`src/app/shell`); every routes file has a **default export**
(`export default [...] satisfies Routes`). Until its feature task replaces it, each routes file shows
the shell's `ComingSoon` placeholder. A feature task changes only its own folder.

| Section   | URL          | Routes file                                             |
| --------- | ------------ | ------------------------------------------------------- |
| Dashboard | `/`          | `dashboard/dashboard.routes.ts`                         |
| Inbox     | `/inbox`     | `gtd/gtd.routes.ts` → `gtd/inbox/inbox.routes.ts`       |
| Next      | `/next`      | `gtd/gtd.routes.ts` → `gtd/next/next.routes.ts`         |
| Projects  | `/projects`  | `gtd/gtd.routes.ts` → `gtd/projects/projects.routes.ts` |
| Waiting   | `/waiting`   | `gtd/gtd.routes.ts` → `gtd/lists/waiting.routes.ts`     |
| Someday   | `/someday`   | `gtd/gtd.routes.ts` → `gtd/lists/someday.routes.ts`     |
| Logbook   | `/logbook`   | `gtd/gtd.routes.ts` → `gtd/lists/logbook.routes.ts`     |
| Calendar  | `/calendar`  | `calendar/calendar.routes.ts`                           |
| Mail      | `/mail`      | `mail/mail.routes.ts`                                   |
| Notes     | `/notes`     | `notes/notes.routes.ts`                                 |
| Files     | `/files`     | `files/files.routes.ts`                                 |
| Contacts  | `/contacts`  | `contacts/contacts.routes.ts`                           |
| Bookmarks | `/bookmarks` | `bookmarks/bookmarks.routes.ts`                         |
| Journal   | `/journal`   | `journal/journal.routes.ts`                             |
| Habits    | `/habits`    | `habits/habits.routes.ts`                               |
| Review    | `/review`    | `review/review.routes.ts`                               |
| Trash     | `/trash`     | `trash/trash.routes.ts`                                 |
| Settings  | `/settings`  | `settings/settings.routes.ts`                           |

`gtd/gtd.routes.ts` is mounted at the root, after the other sections, and maps each GTD URL to the
routes file of its sub-folder, so GTD view tasks don't edit it. Add another GTD view (e.g. a
scheduled list) by adding its route there.

Not routed, but provided to the shell (see `app.routes.ts`):

| Folder      | File                                              | Provides                                                                                        |
| ----------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `capture/`  | `capture.providers.ts` → `provideCapture()`       | `CAPTURE_DIALOG` (`shell/shell-tokens.ts`), opened by '+', `Q`, `Ctrl+Shift+Space`              |
| `search/`   | `search.providers.ts` → `provideSearch()`         | extra `PALETTE_PROVIDERS` (multi) for the `Ctrl+K` palette; the shell adds the "Go to" commands |
| `settings/` | `settings.providers.ts` → `provideSettingsSync()` | loads `GET /settings` on every login and applies the server theme (`ThemeService.setMode`)      |

The theme lives in `shell/theme.service.ts` (`ThemeService.mode()` / `setMode()`); the settings
feature syncs it with the server setting.
