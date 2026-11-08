# Code Review

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, calendar, on this day

Scope: `git diff 97b3c6e6537cffaa9dc5c94b8bffc7c6d35c4295 6d24e9619cb22a8b11bd6b5db8f85515c732e9af -- android/` (commits 9a25ac62, 2050e337, 3de61858, 6d24e961). Only committed code at 6d24e961 was reviewed. Uncommitted test files a test-writer may be adding in the working tree were ignored. This is a re-review: it checks the fixes for CR-1 to CR-4 of the previous review (described in `reviews/and-journal-full/fixes.md`) and looks for new issues.

Requirements:
- `tasks/and-journal-full.md`: acceptance criteria and the three recorded assumptions (save before navigating, with DAY_NOT_SAVED and no navigation during an upload; the week starts on the device locale's first day; the `save`/`saveEntry` split)
- SPEC.md §8.5 (journal), docs/PARITY.md, AGENTS.md project notes (EN and DE strings, lint, conventions)
- Web reference `web/src/app/features/journal/journal-page.ts` (`savedSince`)
- The previous review and the fix report in `reviews/and-journal-full/`

Git range:
- Branch: main
- HEAD: 6d24e9619cb22a8b11bd6b5db8f85515c732e9af
- Base/start: 97b3c6e6537cffaa9dc5c94b8bffc7c6d35c4295
- Commits reviewed: 4 (9a25ac62, 2050e337, 3de61858, 6d24e961)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of previous findings

- **CR-1 (photo goes into the wrong day): fixed.**
    - `JournalRoute.upload` (`JournalScreen.kt:184-188`) reads the shown day before `NotePhotoFiles.read` runs.
    - `addPhoto` (`JournalViewModel.kt:392-428`) refuses with PHOTO_DAY_CHANGED when that day is no longer shown or a navigation is active.
    - The upload result is checked against the captured date again. In practice this cannot fail, because `openDay` refuses while `uploading` is set.
    - Three tests cover it: a photo during the navigation's save, a photo picked during a navigation, and a photo read for a day that is no longer shown (`JournalDaysRaceViewModelTest.kt:223`, `:257`, `:278`).
    - EN and DE strings were added and are mapped in `MessageBanner`.
- **CR-2 (missing tests for out-of-order answers and the navigation guards): fixed.**
    - `JournalDaysRaceViewModelTest` holds answers back with `CompletableDeferred` for:
        - a late load of the day that was left;
        - a late month answer;
        - navigation while a save runs;
        - Today cancelling a running navigation;
        - no navigation during an upload;
        - a stale on-this-day answer.
    - The production bug these tests exposed is fixed: a late on-this-day answer for the same day replaced the newer one. Each request now cancels the previous one through `onThisDayJob` (`JournalViewModel.kt:164-165`).
- **CR-3 (a save during a month load loses its mark): fixed.**
    - This mirrors the web: `savedSince` is cleared when a month request starts (`:213`), filled on each successful save (`:340`), and merged over the server answer (`:217-224`).
    - The merge set is read after the answer arrives, so saves that complete while the request runs are included.
    - Covered by the test at `:294`.
- **CR-4 (date labels in the default locale): fixed.** The heading (`JournalScreen.kt:385`) and each calendar day's label (`:521`) now use `dateFormat.withLocale(LocalLocale.current.platformLocale)`.
- **The two test changes in 6d24e961 are corrections, not weakenings.**
    - The tags test now uses a state that follows `setTags`, like the view model.
    - The German test scrolls to the calendar before checking that it is displayed.
    - Both keep their original assertions.

Also checked and found sound:
- **Navigation.** A save is joined in the `saves` scope, so cancelling a navigation never cancels the save. The `saving` mutex makes the second save a no-op. Going back to the shown day during a navigation cancels that navigation and stays on the day.
- **Stale answers.** The `loadJob`, `monthJob` and `onThisDayJob` cancellations and the date and month guards keep a late answer from replacing a newer one.
- **Conflicts.** Resolving a conflict keeps or replaces mood and tags together with the text.
- **Tags.** Tags are compared ignoring case and order, like the server.
- **Date picker and calendar.** Neither allows a day after today.
- **Strings.** Every new key exists in `values/` and `values-de/`, including the plural.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A camera photo returned after process death is added to today, not to the day it was taken for**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt:184-193` (with `JournalViewModel.kt:129`)
    - Category: Correctness
    - Problem: `cameraUri` and `insertAt` are kept in `rememberSaveable`, so a camera result still arrives after Android killed the process while the camera app was open. The shown day is not saved, though. The view model has no `SavedStateHandle` and starts again on `LocalDate.now(clock)`. `upload()` reads the day when the result arrives, not when the camera was launched. The recreated view model therefore accepts the photo as today's and inserts it into today's entry, at a cursor index taken from the other day's body, and saves it. Before this task the journal only showed today, so this could not happen.
    - Why it matters: the CR-1 fix guarantees that a photo is added to the day it was taken for or not at all. This path breaks that guarantee and puts a photo into another day's saved entry. It is rare (process death during a camera capture), and the photo appears in the visible entry, so the user can see what happened.
    - Suggested fix: remember the day the photo is for when the camera or gallery is launched, in `rememberSaveable` next to `cameraUri`/`insertAt`, and pass that day to `addPhoto`. `addPhoto` then refuses with PHOTO_DAY_CHANGED. Optionally, also keep the shown day in a `SavedStateHandle`.

- [ ] **CR-2 | "No entries from this day in earlier years" shows while the earlier years are still loading**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalScreen.kt:318` and `:643` (state in `JournalViewModel.kt:86-87`, `:164-165`)
    - Category: Quality
    - Problem: `OnThisDay` is shown as soon as the entry has loaded (`!state.loading`). `JournalUiState` has no loading flag for on-this-day, and `show()` starts every day with `onThisDay = emptyList()`. When `get` answers before `onThisDay`, the section says there are no earlier entries, and then entries appear. On a slow connection this can last seconds.
    - Why it matters: the section briefly claims something false. A user who opens a day and reads "none" may navigate away before the real entries appear.
    - Suggested fix: add an `onThisDayLoading` flag. Set it in `load()`, clear it in `loadOnThisDay`, and while it is set show nothing (or a small progress indicator) instead of the "none" text.

## Notes for the parent worker (not findings)

- `python tasks.py verify and-journal-full` shows all six criteria as not yet verified. Evidence still has to be recorded.
- According to `fixes.md`, `sh scripts/check.sh` fails here because Docker is missing for the server's Testcontainers tests. Criterion 6 names that command, so it has to pass, or the criterion has to be recorded as skipped with that reason. It cannot be claimed as passed.

## Review conclusion

No material issues found. All four earlier findings are correctly fixed and have regression tests. The two remaining P3 items are minor and may be fixed or accepted at the parent worker's discretion.
