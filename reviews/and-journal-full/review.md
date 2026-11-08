# Code Review

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, calendar, on this day

Scope: `git diff 706394402751aa44c24e6a5627e2b23049d5df35 ee4102b9 -- android/` (implementation commit 4a655664 and test commit ee4102b9; `tasks/` assumption commits ignored). Committed changes only; the working tree was clean.

Requirements:
- `tasks/and-journal-full.md` acceptance criteria and recorded assumptions (save-before-navigate with DAY_NOT_SAVED, device-locale week start, `save`/`saveEntry` split)
- SPEC.md §8.5 (journal: one entry per day, mood, tags, calendar heatmap, on this day)
- docs/PARITY.md (row "Journal: today only ..." -> and-journal-full)
- AGENTS.md project notes (EN+DE strings, lint, conventions)
- Web reference `web/src/app/features/journal/journal-page.ts` (heatmap marking of saves, `savedSince`)
- Server contract `server/.../journal/entries/domain/JournalEntryService.kt` (tag normalization, heatmap days, on this day)

Git range:
- Branch: main
- HEAD: ee4102b90b2c23af781bbfa1ac48cff9f48c8364
- Base/start: 706394402751aa44c24e6a5627e2b23049d5df35
- Commits reviewed: 2 (4a655664, ee4102b9)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

What was checked and found sound: the save-before-navigate flow (`openDay` joins a save in the outliving `saves` scope, so a cancelled navigation never cancels the save; the `saving` mutex serializes it with a running autosave, and the second save sees `isSaved()` and does nothing); stale day loads (`loadJob` cancelled in `show`, plus the `it.date != date` guard); stale saves (`it.date != before.date` guard); stale month loads (`monthJob` cancelled plus the `it.month != month` guard); conflict resolution keeps or replaces mood and tags together with the text; tag comparison ignoring case and order matches the server, which keeps the spelling of unchanged tags; the date picker blocks days after today; all new string keys exist in `values/` and `values-de/`, including the plural.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A photo picked while another day is opening is inserted into the wrong day or silently dropped**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:381` (with `:229`, `:236` and `:251`)
    - Category: Correctness
    - Problem: `openDay` refuses navigation while `uploading` is set, but `addPhoto` doesn't check the reverse. While a navigation waits for its save (`saves.launch { save() }.join()`, a network round trip), the shown day is still `editable`, so `addPhoto` starts an upload. When the save succeeds, `show(target)` replaces the state with a fresh `JournalUiState` (`uploading = false`). The upload coroutine (`:383-398`) never captures the date it started for. When it finishes, it inserts the Markdown into whatever day is shown if that day is loaded (at a cursor index taken from the old day's body) and saves it there. If the new day is still loading, neither branch of the `when` matches and the uploaded image is dropped with no message. The same window opens when the user taps a day while `NotePhotoFiles.read` still runs on `Dispatchers.IO` (`JournalRoute.upload`), before `addPhoto` sets `uploading`. Because `show` resets `uploading`, a second upload can also start on the new day while the first is still running.
    - Why it matters: the recorded assumption says navigation is ignored while a photo uploads "so it lands in the day it was taken for". This path breaks that guarantee: a photo ends up in another day's entry, which is user data in the wrong place, or it is lost without notice.
    - Suggested fix: capture `state.value.date` when the upload starts. On completion, insert only if the state still shows that date (otherwise report it, e.g. as a photo message). Also refuse `addPhoto` while `navigation?.isActive == true`. Add a test with a deferred upload and a navigation in between.

- [ ] **CR-2 | The out-of-order and navigation-guard paths have no tests**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDaysViewModelTest.kt:62-68` (all repository mocks answer immediately)
    - Category: Tests
    - Problem: the new view-model tests stub `get`, `days`, `onThisDay` and `saveEntry` with immediate answers. No test holds a response back (`CompletableDeferred`/`coAnswers`) to check:
        - a slow `get` of the previous day arriving after another day opened (`load()`'s `it.date != date` guard and `loadJob` cancellation);
        - a slow `days` answer for an earlier month arriving after `showMonth` moved on (`it.month != month`);
        - a slow `onThisDay` of the old day;
        - navigation while a save is already in flight (mutex plus `join`);
        - a second `openDay` during the first one's save;
        - `openDay` ignored while `uploading`.

      Those are the concurrency behaviours this task adds, and a regression in any guard would not be caught.
    - Why it matters: stale results overwriting a newer day's state is exactly how data ends up in the wrong entry. The guards exist in the code, but nothing protects them against future edits.
    - Suggested fix: add `JournalDaysViewModelTest` cases that use `CompletableDeferred` for `repo.get(yesterday)`, `repo.days(...)` and `repo.onThisDay(...)`. In each, navigate on, complete the stale answer and assert the shown state is unchanged. Add one case each for "navigation while a save runs" and "navigation ignored while uploading".

## P3 Low

- [ ] **CR-3 | A month load answered after a save drops that save's calendar mark**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:207`
    - Category: Correctness
    - Problem: `loadMonth` replaces `monthDays` with the server answer. If an entry is saved (and marked through `marked()`, `:409`) while that request is in flight, and the server computed the answer before the save, the mark disappears. This happens when the user writes on a day without an entry while the initial or a retried month load is slow. The web handles this case explicitly with `savedSince` in `journal-page.ts` (around lines 137-141 and 476).
    - Why it matters: the calendar wrongly shows the day as having no entry until the month is loaded again. This is a display-only parity gap; no data is lost.
    - Suggested fix: remember the entries saved since the month request started and merge them into the answer, the same way the web does.

- [ ] **CR-4 | Calendar day labels use the process default locale, unlike the rest of the calendar**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt:518` (formatter at `:241`)
    - Category: Quality
    - Problem: commit ee4102b9 switched the calendar to `LocalLocale.current.platformLocale` (week start, weekday names, month title). The new accessibility label of each day (`contentDescription`, `journal_day_with_entry`) still uses the top-level `dateFormat`, which is `ofLocalizedDate(FormatStyle.FULL)` without `withLocale`. That formatter is bound to the default locale once, when the class loads.
    - Why it matters: after a per-app language change in the same process, the TalkBack label for each day ("..., has an entry" or "..., mit Eintrag") mixes languages, while the visible calendar follows the new locale.
    - Suggested fix: inside the composables, use `dateFormat.withLocale(locale)` with the same `LocalLocale` value for the day labels (and for the heading).

## Review conclusion

Material issues found; fix before completing the task. CR-1 can put a photo into the wrong day's entry or lose it, and CR-2 leaves the task's concurrency guards without regression tests. CR-3 and CR-4 are minor.
