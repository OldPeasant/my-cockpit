# Code Review

Date: 2026-11-08

Task: and-capture-improve - Android capture: syntax preview, save and add another, attachments

Scope: the full task diff `git diff 7adc3abf4..016ce641d` (merge base with origin/main to HEAD). It covers production code under `android/app/src/main/java/ch/sonensei/cockpit/capture/{share,entry}`, `res/values{,-de}/strings_share.xml`, the five new test classes under `android/app/src/test/java/ch/sonensei/cockpit/capture/share/`, and the earlier review, fix and test reports. This is a re-review of the final code. It checks the fixes for CR-1..3 from the first review (done at a different commit hash before the rebase) and looks for new issues. No uncommitted changes.

Requirements:
- tasks/and-capture-improve.md (acceptance criteria 1-5 and the three recorded assumptions)
- SPEC.md §4.5 (quick capture, light syntax)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Reference for "same parsing rules": server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt
- reviews/and-capture-improve/fixes.md, reviews/and-capture-improve/tests.md

Git range:
- Branch: main
- HEAD: 016ce641d6c362ea47bcc928299b0ca658528ee5
- Base/start: 7adc3abf42d567c81db59173e481e988a4d835ce
- Commits reviewed: 4 (623e18915 implementation, 8a31f6fc7 WIP tests + first review, 6e73a222d fixes CR-1..3, 016ce641d final tests + test report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- **CR-1 fix (ASCII whitespace):** `CaptureSyntax.TOKEN` and `WHITESPACE` now use `[ \t\n\u000B\f\r]`, which is exactly OpenJDK's `\s`. ICU's `\s` is `[\t\n\f\r\p{Z}]`, so the fix removes U+00A0/U+202F and also adds VT to match the server. That is correct. `ISO_DATE` still uses `\d` (Unicode digits under ICU), but `LocalDate.parse` uses `DecimalStyle.STANDARD`, which rejects non-ASCII digits. The result is therefore the same as on the server. Other than this, a line-by-line comparison with `CaptureParser.parse` shows parity: first `#`/`!`/`^` wins, contexts accumulate and are deduplicated, punctuation takes the token's place, and the next line is used when the title line holds only tokens. The `splitLines` difference does not change the preview.
- **CR-2 fix (remove only via close button):** `FileChip` is now a non-clickable `Surface`. Only the 48 dp `IconButton` (`share:remove-file`, "Remove %1$s", disabled while saving) removes a file. The test "a tap on the attachment's name does not remove it, only its close button does" asserts that the chip has no `OnClick` action and that a tap on the name removes nothing.
- **CR-3 fix (toast after process death):** `SavedAndClearedToast` uses plain `remember { mutableIntStateOf(state.savedAndCleared) }`. On a rotation the ViewModel survives and the counter restarts at the current count, so no toast repeats. After process death both sides start at 0. The two `StateRestorationTester` tests check both paths, and the process-death test would fail with the former `rememberSaveable`.
- **ViewModel state:** `canSave` requires `!loadingFiles`. `addFiles`/`removeFile`/`onDestinationChange` do nothing while `saving || saved`. The `fileLoads` counter is 0 when `finish(addAnother = true)` replaces the state, so the reset cannot drop a file-name load that is still running. `lastSavedReadLater` is read from the in-flight state, whose destination cannot change during a save. A failed add-another save keeps the form and shows the error. The TOO_MANY_FILES error is set on pick, cleared when enough files are removed, and checked again in `save`.
- **Offline vocabulary:** `CaptureVocabularySource` reads only the Room GTD cache, filters ACTIVE/ON_HOLD/SOMEDAY, and orders projects by status index and then by id string. Lowercase UUID strings sort like Postgres uuid byte order. Contexts are ordered by position and then id. A cache error degrades to an empty vocabulary. The stale-cache limitation is recorded as an assumption.
- **Attach picker:** `OpenMultipleDocuments` with `*/*`. A cancelled pick (empty list) is ignored. The button is hidden for read-later, and attaching files hides the read-later choice (`offersReadLater`). Picked files take the same path into `CaptureQueue.capture` as shared files, so they are copied into the offline queue on save (covered by "picked files are copied into the offline queue on save and survive the source going away").
- **i18n:** all 7 new strings exist in `values/` and `values-de/` with matching `%1$s` arguments. German rendering is tested with `@Config(qualifiers = "de")`.
- **Tests:** the five test classes test behavior through public APIs and the real Hilt/Room/activity stack (`ShareAddAnotherActivityTest`). They contain no sleeps, ignores or weakened assertions. The two assertions the test-writer changed (`EditableText` instead of `assertTextEquals("")`) are correct, because an empty field's `Text` semantics contain the placeholder.
- **Security/privacy:** the change adds no logging and no network calls. File names are read from the provider only for display.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The widget/tile capture sheet's new wiring has no activity-level test**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureActivity.kt:43-53`
    - Category: Tests
    - Problem: the task's third assumption says both capture sheets (share target and widget/tile/shortcut) get "save and add another", the confirmation toast and the attach picker. `ShareActivity` has an end-to-end test of this wiring (`ShareAddAnotherActivityTest`). For `CaptureActivity`, no test uses `SAVE_ANOTHER`, `ATTACH` or `SavedAndClearedToast`: `CaptureEntryPointsTest` covers only the existing behavior. If someone deleted the three new lambdas or the toast call in `CaptureActivity`, every test would still pass.
    - Why it matters: criterion 5 asks for tests that cover the new behavior. The wiring is trivial and shares the tested composable and ViewModel, so the risk is low.
    - Suggested fix: add one Robolectric test to `CaptureEntryPointsTest` (or a sibling test in `capture/share`, to stay inside the task's touches) that launches `CaptureActivity`, types a title, taps "Save and add another", and then asserts that the activity stays open, the field is empty, one capture is queued and "Saved to Inbox" is toasted.

## Notes (not findings)

- `capture/queue/RelativeDueDates.kt:15` still uses `\s`/`\S`. On a device, a `!today` that follows a no-break space is therefore pinned to a date in the queued text, while the preview, which now matches the server, does not treat it as a token. This existed before the task, the file is outside the task's `touches`, and fixes.md already records it. A follow-up task could align it.

## Review conclusion

No material issues found. All three findings of the first review are fixed correctly and covered by regression tests. CR-1's regression test is limited by the JVM, as tests.md states. The only new finding is an optional P3 test gap.
