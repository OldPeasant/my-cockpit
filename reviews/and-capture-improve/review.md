# Code Review

Date: 2026-11-08

Task: and-capture-improve - Android capture: syntax preview, save and add another, attachments

Scope: implementation commit e7c926f1f on top of base 9911c0d97 (`git diff 9911c0d..e7c926f1f -- android`), production code under `android/app/src/main/java/ch/sonensei/cockpit/capture/` and `res/values{,-de}/strings_share.xml`. Tests were being added at the same time by the test-writer and are not part of this review.

Requirements:
- tasks/and-capture-improve.md (acceptance criteria 1-5, and the three recorded assumptions)
- SPEC.md §4.5 (capture syntax), §9.3
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Reference implementation: server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt and CaptureService.parse

Git range:
- Branch: main
- HEAD: e7c926f1f
- Base/start: 9911c0d97130e1888803c2b9647ec982a80581b8
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## What was checked

- **Parser parity:** `CaptureSyntax.parse` matches `CaptureParser.parse` line by line: the token regex, trailing punctuation, the rule that the first `#`/`!`/`^` wins, contexts accumulating (deduplicated by id), the `-`/`_` normalization for project titles, context names with or without the leading `@`, the `!today`/`!tomorrow`/ISO date rules, and the fallback to the next line when the title line holds only tokens. `splitLines` differs (it does not drop blank lines around the rest), but the result is the same because the fallback looks for the next non-blank line again. Title cutting is left out, and that is fine because the preview never displays the title.
- **Project ordering:** the server filters ACTIVE, ON_HOLD and SOMEDAY projects that are not deleted, orders them by id in SQL (Postgres uuid order is byte order), then sorts them stably by status. `CaptureVocabularySource` sorts by the same status index and then by the id string. The ids are lowercase hex UUIDs, so string order equals byte order. Contexts are sorted by position and then id, as `findAllByOrderByPositionAscIdAsc` does on the server. The offline-cache limitation is recorded as an assumption.
- **Due dates:** the preview uses the device date. `CaptureQueue` pins `!today`/`!tomorrow` with the device zone when it stores the capture (`RelativeDueDates`), so the chip shows the date the server will store.
- **ViewModel concurrency:** `canSave` requires `!loadingFiles`, and `addFiles`/`removeFile` do nothing while `saving || saved`. So a save never starts while file names are still being read, and the reset to `ShareUiState(...)` after "save and add another" cannot discard a running file load (the `fileLoads` counter is 0 at that point). The text, notes, attach and remove controls are disabled while saving, so nothing typed during a save is lost by the reset. `lastSavedReadLater` is read from the unchanged in-flight state.
- **URI grant lifetime:** `OpenMultipleDocuments` grants are owned by the receiving activity record. The record survives configuration changes, and `CaptureQueue.capture` copies the files synchronously inside the save, before `saved` is set and the activity finishes. Display names are read right away on IO. The copy into the offline queue therefore always happens while the grant is still valid.
- **i18n:** all 7 new strings exist in `values/` and `values-de/`, with matching format arguments. The due date uses a locale-aware skeleton (`EEEMMMd`).
- **Accessibility:** the attach IconButton has a content description and a 48 dp target. The remove icon carries "Remove <name>". The preview chips' text is readable, and their icons are decorative (`contentDescription = null`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `\s`/`\S` in CaptureSyntax are Unicode-aware on the Android runtime but not on the server JVM**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSyntax.kt:62`
    - Category: Correctness
    - Problem: `TOKEN = Regex("""(^|\s)([#@!^])(\S+)""")` (and `WHITESPACE`) is copied from the server. On the server (OpenJDK `java.util.regex`) `\s` is only `[ \t\n\x0B\f\r]`. Android's regex engine is ICU-based, and there `\s` also matches Unicode spaces such as U+00A0 (no-break space) and U+202F. For a title like `Call @phone` the device shows an `@phone` chip, but the server treats the text as one word and creates no context. The JVM unit tests run on OpenJDK, so they cannot catch this.
    - Why it matters: criterion 1 asks for "same parsing rules as web". The difference only appears with pasted or shared text that contains no-break spaces, so the impact is small.
    - Suggested fix: use explicit ASCII classes in `TOKEN` and `WHITESPACE` (for example `[ \t\n\u000B\f\r]` and its negation) so both runtimes behave the same. `RelativeDueDates` has the same pattern from before this task.

- [ ] **CR-2 | Tapping anywhere on an attachment chip removes it immediately**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt:279-283`
    - Category: Quality
    - Problem: `FileChip` wires `onClick = { onRemove?.invoke(file.uri) }` on the whole `InputChip`. Only the trailing close icon looks like a remove action, but a tap on the file name removes the file too, with no undo. With TalkBack the whole chip is a single "activate" action. Before this change the chips were inert (`enabled = false`).
    - Why it matters: an accidental tap drops an attachment, and with a shared file there is no way to pick it again from the sheet's original source.
    - Suggested fix: make only the trailing icon remove the file (a small clickable or IconButton with the existing "Remove %1$s" description), and keep the chip body without an action, or add an undo.

- [ ] **CR-3 | No "Saved" toast after process death once "save and add another" has been used**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt:76-83`
    - Category: Correctness
    - Problem: `shown` is kept in `rememberSaveable`, but `ShareUiState.savedAndCleared` lives only in the ViewModel. After process death the ViewModel starts again at 0 while `shown` comes back as N. The next N saves with "save and add another" then fail `savedAndCleared > shown` and show no confirmation.
    - Why it matters: the user gets no feedback that the capture was queued, although the sheet clears. This is rare (the process has to be killed while the sheet is open), and no data is lost.
    - Suggested fix: keep `shown` in plain `remember`, or store the counter in `SavedStateHandle` so that both sides survive together.

## Review conclusion

No material issues found. The three P3 findings are optional improvements. Criterion 5 (tests, plus `ktlintCheck testDebugUnitTest lintDebug` and `scripts/check.sh`) was outside this review because the test-writer was still adding tests, and `tasks.py verify` showed no criterion verified yet at review time.
