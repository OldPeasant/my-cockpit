# Code Review

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, calendar, on this day

Scope: `git diff 3a37b31d..62026e6a` (base = `git merge-base HEAD origin/main`), the whole task diff. This is the final re-review. It checks the fixes for the two P3 findings of the previous round (CR-1: a camera photo goes to today after process death; CR-2: on this day says "none" while loading, fixed in bc8e86ff) and the date picker tests added in 62026e6a. Only committed code was reviewed, and the working tree was clean.

Requirements:
- `tasks/and-journal-full.md`: the six acceptance criteria and three recorded assumptions (save before navigating, with DAY_NOT_SAVED and no navigation during an upload; the week starts on the device locale's first day; the `save`/`saveEntry` split)
- SPEC.md §8.5 (journal: "Today", calendar heatmap, on this day), docs/ARCHITECTURE.md, AGENTS.md project notes (EN and DE strings, lint, conventions)
- Previous rounds in `reviews/and-journal-full/` (`review.md`, `fixes.md`, `tests.md`)

Git range:
- Branch: main
- HEAD: 62026e6a9c73c9db0f8a0dbd346fb2b33d3e920c
- Base/start: 3a37b31d (merge-base with origin/main)
- Commits reviewed: 7 (9cb2c85d, 75ffde56, 60018728, 41597913, 4ef85c16, bc8e86ff, 62026e6a)

Commands run by the reviewer:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug --console=plain`: BUILD SUCCESSFUL. All tasks were up to date for HEAD 62026e6a. The test results add up to 4584 tests, with 0 failures, 0 errors and 0 skipped.
- `python tasks.py verify and-journal-full` (listing only): all 6 criteria are still unverified.
- `sh scripts/check.sh` was not run. Its server part needs Docker for Testcontainers, and Docker is not available here.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of previous findings

- **CR-1 (camera photo after process death): fixed.**
    - `JournalRoute` stores the day the camera or gallery was opened for in a `rememberSaveable` `photoDay` (`JournalScreen.kt:187`, set at `:215`).
    - `upload` passes that day to `addPhoto` (`:191-193`).
    - `addPhoto` refuses a day that is not shown with PHOTO_DAY_CHANGED (`JournalViewModel.kt:404-407`).
    - `JournalPhotoAfterRestoreRouteTest` covers both sides with `StateRestorationTester` and a stand-in result registry:
        - recreated on today: the photo is refused;
        - the same day is still shown: the photo is added.
    - When the camera or gallery is cancelled, `photoDay` is left stale. This does no harm, because every launch overwrites it before a result can arrive.
- **CR-2 (on-this-day loading state): fixed.**
    - `onThisDayLoading` is set in `load()` for the requested day (`JournalViewModel.kt:167`).
    - Success and failure both clear it, but only for the shown day (`:193-201`). Earlier requests are cancelled through `onThisDayJob`.
    - `OnThisDay` shows a labelled progress indicator before it checks for failure or no entries (`JournalScreen.kt:650-658`).
    - The view-model tests in `JournalLateAnswersViewModelTest` cover the loading flag, a failed answer, and a late answer for a day already left. The screen tests are in `JournalDaysScreenStatesTest`.
    - The new EN and DE string exists in both files.
- **62026e6a**: `JournalDatePickerScreenTest` checks that:
    - choosing a different day in the picker opens that day;
    - days after today are disabled.
  This fills the gap noted in `tests.md`.

## Whole-diff check against the criteria

1. **Previous/next day, Today, date picker; other days editable.** `openDay` limits navigation to today. It saves pending changes in the app-wide `saves` scope before showing another day; a cancelled navigation does not cancel that save. If the save fails or a conflict waits, the day stays open with DAY_NOT_SAVED, as the recorded assumption says. It ignores clicks while a photo uploads. `show()` resets the per-day state, and `loadJob` plus the date guards drop late answers. Met.
2. **Mood and tags.**
    - The mood can be set, changed and cleared.
    - The tags are parsed as a comma list, with no duplicates (case ignored) and a leading `#` removed. They are compared without regard to order or case.
    - A conflict resolution keeps or replaces mood and tags together with the text.
    - `saveEntry` sends mood and tags.
    - `save(date, base, text)` keeps `ReflectionSaver`'s behavior: it keeps the base's mood and tags.
   Met.
3. **Month calendar.**
    - Each new request cancels the previous one through `monthJob`, and an answer for a different month is ignored.
    - Saves made after a month request starts are merged into its answer through `savedSince`, as on the web.
    - Days after today are not clickable, and the next-month button stops at the current month.
    - Weekday names, the month title and each day's label use the composition locale.
   Met.
4. **On this day.** The repository keeps only earlier dates. The list shows the year, how many years ago (as a plural), and an excerpt. A tap opens that day. Met.
5. **EN and DE strings.** Every new key, including the plural and the loading text, is in both `values/` and `values-de/`. `JournalGermanTest` checks that each language has the same keys, and lint `MissingTranslation` passes. Met.
6. **Tests and checks.**
    - The new behavior is covered in depth, including race, late-answer, locale and process-death tests.
    - The changes to the existing `JournalViewModelTest` and `JournalRouteInAppTest` only move them from `save(...)` to `saveEntry(...)`. They match the draft by its text and keep their assertions.
    - The Android command passes (see above).
    - `sh scripts/check.sh` could not be run here because Docker is missing. The parent must record that part as skipped with this reason, not as passed.

Also checked and found sound:
- Only one owner changes each piece of state: the view model.
- The `saving` mutex and `isSaved()` prevent duplicate saves.
- `savedSince` and `state` are only touched on the main dispatcher.
- No secrets and no logging of note contents.
- The changes stay inside the task's `touches`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Today" goes stale when the screen stays open past midnight**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:131` and `:246-247` (used in `JournalScreen.kt:358` and `:379-384`)
    - Category: Correctness
    - Problem: `JournalUiState.today` is set when the view model is created and is only refreshed in `openDay`. The view model is scoped to the navigation entry, so it can live overnight. After midnight, until the user navigates:
        - the heading still labels the previous day "Today";
        - `isToday` stays true, so the Today button is hidden;
        - the next-day button stays disabled (`canGoForward`);
        - the date picker and the calendar refuse the new day.
      Getting there takes a detour: go to the previous day, then tap Today. The test `after midnight Today opens the new day...` calls `openToday()` on the view model directly, so it does not catch that the screen hides that button.
    - Why it matters: a confusing label and navigation for anyone who keeps the app open overnight. No data is lost.
    - Suggested fix: refresh `today` from the clock when the screen resumes, or derive `isToday`/`canGoForward` from the clock each time state is emitted. Any lifecycle-resume hook works.

- [ ] **CR-2 | `JournalDraft.isEmpty` is unused in production code**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalRepository.kt:45-46`
    - Category: Quality
    - Problem: the property's doc says "a day without an entry stays without one", but no production code reads it. That rule is actually enforced by `isSaved()`, which compares the draft with `null.draft`. `isEmpty` is only exercised by `JournalTextTest`. `tests.md` noted the same.
    - Why it matters: dead API with a doc that claims a behavior it does not enforce. That misleads the next person who changes the "create only with content" rule.
    - Suggested fix: remove `isEmpty` and its test, or use it in `isSaved()` for the `entry == null` case.

## Notes for the parent worker (not findings)

- All six criteria are still unverified in `tasks.py verify`. Record evidence for each.
- For criterion 6, record the Android command result as passed. Record the `sh scripts/check.sh` part as skipped, with Docker missing for the server Testcontainers as the reason, unless it can be run somewhere with Docker.

## Review conclusion

No material issues found. The previous round's CR-1 and CR-2 are correctly fixed and have regression tests, and the date picker tests close the remaining test gap. The two new P3 items are optional cleanups that the parent worker may fix or accept.
