# Code Review

Date: 2026-10-25

Task: web-journal - Journal UI

Scope: production and e2e changes of the task, `git diff fc87c85c..HEAD -- web/src/app/features/journal web/e2e/journal web/src/app/app.spec.ts web/src/app/app.i18n.spec.ts` (unit spec files that the test-writer is adding at the same time under `features/journal` are not part of this review)

Requirements:
- tasks/web-journal.md acceptance criteria and recorded assumptions (Notes)
- SPEC.md §8.5 (Journal), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project conventions
- api/openapi.yaml (journal endpoints) and the generated client (`putJournalEntry` and others)
- Server contract in `server/.../journal/entries/api/JournalEntryController.kt` and `core/web/ETags.kt`

Git range:
- Branch: main
- HEAD: f8b80211eab0db32cc9da0d185b2a3a800bcc818
- Base/start: fc87c85caf88a97d6b5fe23b30f101f024dd45aa
- Commits reviewed: 3 (236096dc1, ff18f4fac, f8b80211e)
- Uncommitted changes: untracked `web/src/app/features/journal/journal.service.spec.ts` and `testing.ts` (test-writer, not reviewed)

Summary: 0 P0, 2 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | An entry created elsewhere after the day was opened ends in a 428 loop that cannot be recovered**
    - File: `web/src/app/features/journal/journal-page.ts:360`, `web/src/app/features/journal/journal.service.ts:59`
    - Category: Correctness
    - Problem: When the day had no entry at load time, `entry()` stays `null` and every save is a PUT without `If-Match`. If the entry was created in the meantime (Android app, another tab, or the overlapping instance from CR-3), the server does not answer 409 or 412. `JournalEntryController.put` calls `ETags.checkIfMatch(null, version)` for an existing entry, and that throws `PreconditionRequiredException` (428). `persist` treats only 409/412 as a conflict, so 428 sets `saveState = 'error'`. "Try again" (`retry()` -> `flush()` -> `persist(date, this.entry() /* still null */, ...)`) sends the same request without `If-Match` and gets 428 again every time. The conflict banner ("Discard my edits" / "Save my version"), whose `keepMine()` would fetch the entry and its version, never appears. Leaving the day in that state is also blocked: `open()` sends the user back while `saveState === 'error' && dirty()`.
    - Why it matters: Writing the same day on two devices is a normal journal flow (SPEC.md §8.5, with the Android client). The user cannot save the edits, and the only way out is a page reload, which drops them.
    - Suggested fix: Treat 428 as a conflict too (at least when `entry` is null), so the existing `keepMine`/`useTheirs` flow loads the current version. Add a unit test: entry null, PUT answers 428 -> conflict state, and "Save my version" sends `If-Match`.

- [ ] **CR-2 | Acceptance criterion 2 has no committed unit tests at the reviewed revision**
    - File: `web/src/app/features/journal/` (no `*.spec.ts` at HEAD f8b80211e)
    - Category: Tests
    - Problem: Criterion 2 asks for Vitest component/service tests of creating today's entry and of the heatmap. HEAD only changes `app.spec.ts` and `app.i18n.spec.ts` (shell routing) and adds a Playwright spec. The journal service and component specs are still untracked work by the test-writer.
    - Why it matters: Criterion 2 is not met by the committed code yet. The race- and error-prone logic (`flush`/`persist`, If-Match vs. create, conflict handling, upload placeholders, `heatmapWeeks` padding and week start, `isDate`, future-date redirect) has no unit coverage.
    - Suggested fix: Commit the test-writer's specs before `done`. Make sure they cover first-edit creation without `If-Match`, later saves with `If-Match: "<version>"`, 409/412 (and 428, see CR-1) -> conflict, `heatmapWeeks` for different `firstDay` values and range edges, and the redirect for future or invalid dates. Then re-run the review on the final code.

## P2 Medium

- [ ] **CR-3 | Moving between `/journal` and `/journal/:date` recreates the page, which skips the "save before opening the next day" order**
    - File: `web/src/app/features/journal/journal.routes.ts:3-14`, `web/src/app/features/journal/journal-page.ts:120-123`, `web/src/app/features/journal/journal-page.ts:269-290`
    - Category: Correctness
    - Problem: `''` and `':date'` are two route configs. Angular's default `RouteReuseStrategy` only reuses a component when `routeConfig` is the same, so every move between today and another day ("Previous day" from today, "Next day" to today, a heatmap click on today, the "Today" link) destroys the `JournalPage` instance and creates a new one. `open()` (`await this.leave()`, `openSeq`) only serializes moves inside `:date`. For the others, the old instance saves in the background from `onDestroy` (and waits there for pending image uploads), while the new instance already GETs the day. Example: type today, press "Previous day", then quickly "Next day". The new instance can load today before the old PUT lands. It then shows text without the last edits, or "Not written yet" for a just-created entry. The next edit then gets 412 (conflict), where "Save my version" overwrites the text the old instance just saved, or 428 (CR-1).
    - Why it matters: Edits can be lost in a normal navigation flow, and the waiting for uploads that `leave()` was designed for makes the race window longer.
    - Suggested fix: Use one route config for both URLs so the instance is reused and `open()` handles every move, for example a parent `''` route with the component and `':date'` / `''` children, or a `UrlMatcher` with an optional segment. Another option is to let the service own the pending save per date, so a new instance waits for it before its GET.

- [ ] **CR-4 | An image whose upload ends while Preview is shown is lost, and its placeholder stays in the text**
    - File: `web/src/app/features/journal/journal-page.ts:93-99`, `web/src/app/features/journal/journal-page.ts:296-311`, `web/src/app/features/journal/journal-page.html:166-182`
    - Category: Correctness
    - Problem: Switching to Preview destroys `<app-markdown-editor>` (it is inside `@if (preview())`). The editor's `replaceMarker` then does nothing (`this.view !== view`), and no `uploadFailed` is emitted. The page only puts the image Markdown in place of the placeholder in `leave()`, and only for uploads still in `this.uploads`. But `done.then(() => this.uploads.delete(placeholder))` removes the entry as soon as the upload ends. So an upload that ends during Preview leaves `![Uploading name…](uploading:N)` in `body()`. The recreated editor shows it as stale text, `withoutUploadPlaceholders` drops it from every save, and the uploaded file is orphaned in `/Attachments/Journal`. A failed upload during Preview is also never reported.
    - Why it matters: Image paste is part of criterion 1. A photo that takes a few seconds to upload is silently not saved, and nothing tells the user.
    - Suggested fix: Let the page replace the placeholder in `body()` (and schedule a save) when each upload ends, not only in `leave()`. Or keep the editor mounted while Preview is shown (hide it instead of removing it). Add a test that toggles Preview during an upload.

## P3 Low

- [ ] **CR-5 | "Today" does not move on at midnight in a page left open**
    - File: `web/src/app/features/journal/journal-page.ts:132`, `web/src/app/features/journal/journal-page.ts:313-317`
    - Category: Correctness
    - Problem: `today` comes from the heatmap, which is fetched once per instance (`if (!this.today()) await this.loadHeatmap()`). A tab left open on `/journal` overnight keeps labelling the previous day "Today", "Next day" stays disabled, and new writing goes into yesterday's entry.
    - Why it matters: Small, but it contradicts "'Today' opens today's entry" for a page that is often left open.
    - Suggested fix: Refresh the heatmap and `today` when the page becomes visible again (`visibilitychange`) or on a long timer, and reopen the day when it was today.

- [ ] **CR-6 | Edits in conflict are dropped without a prompt when the user leaves the day**
    - File: `web/src/app/features/journal/journal-page.ts:214`, `web/src/app/features/journal/journal-page.ts:279`
    - Category: Correctness
    - Problem: `flush()` returns without saving while `saveState === 'conflict'`. `open()` only keeps the user on the day for `'error' && dirty()`, so in conflict state it goes on to `show()`, which replaces the edits. Leaving the journal section (destroy) drops them the same way. `onBeforeUnload` only covers closing or reloading the tab.
    - Why it matters: The banner says "Your edits are not saved yet", but one click on another day throws them away.
    - Suggested fix: Treat `'conflict' && dirty()` like `'error'` in `open()`, so the user stays on the day until they choose "Discard my edits" or "Save my version".

## Checked without findings

- ETag/If-Match: `If-Match: "<version>"` matches `ETags.of` (strong comparison) and the convention of other features. Creating an entry sends no `If-Match`, as the API requires. Saves are serialized through `inFlight`, and edits made during a save are scheduled again.
- Results that arrive late: `loadSeq` makes loads, on-this-day and conflict reloads for a day the user already left ignored. `persist` ignores results for a day that is no longer shown.
- Heatmap date math: UTC-only date arithmetic (`addDays`, `weekdayOf`), padding to `firstDay` from settings `weekStart`, month labels, cells limited to the range. Roving tabindex with arrow keys stays inside the range. Days have `aria-label`, and `aria-current` marks the selected day.
- "Today" and time zone: the server's `heatmap.to`, as recorded in the assumption. Future and invalid dates redirect to `/journal`.
- On this day: called with the shown date, links to `/journal/:date`, excerpt is plain text through interpolation (no `innerHTML`).
- API usage: every call goes through the generated `Api` functions with matching params. Errors are silent and shown inline.
- i18n: all user texts use `$localize`/`i18n` with ids. `messages.xlf` is left to a dedicated extract, the same as other features.
- Security: no logging of entry contents, no unsafe HTML, the preview uses the existing read-only `NoteMarkdownView`.
- Shell specs: the updated `app.spec.ts`/`app.i18n.spec.ts` still check the placeholder text through a test-only route, and now check that every section is built.

## Review conclusion

Material issues found; fix before completing the task.
