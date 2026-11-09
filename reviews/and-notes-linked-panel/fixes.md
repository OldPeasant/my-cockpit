# Fix Report

Date: 2026-11-09

Task: and-notes-linked-panel - Note editor: LinkedItemsPanel

Scope: current task; findings CR-1 and CR-2 from `reviews/and-notes-linked-panel/review.md`

## Fixed findings

### CR-1 - Links panel stays stale after the note's inline links change (edit, revision restore, reopen)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorLinksRouteInAppTest.kt`

Problem:
The panel's `LinkedItemsViewModel` (key `links:NOTE:{id}`) outlives the editor, and `open()` is a no-op for the same item. Nothing in the note route called `reload()`. So after a save that changed the body's inline links (or a revision restore, or reopening the note), the panel kept showing the old links.

Fix:
`NoteEditorRoute` now gets the links view model itself through a new private `noteLinks(state.note?.id)`. It follows the MailReadScreen/ContactsScreen pattern: `hiltViewModel(key = linksViewModelKey(ItemRef("NOTE", id)))`, used only when there is a saved note and `LocalActivity.current is GeneratedComponentManagerHolder`, and `null` otherwise. The route passes this view model to `LinkedItemsSection(..., viewModel = it)`. A `LaunchedEffect(links, state.note?.version)` calls `reload()` only when the panel's view model already shows this note. That covers a new saved version, including revision restore, and the note opened again. While the panel has not shown the note yet, the effect does nothing and the panel's own `open()` does the first load, so the first open still causes exactly one `list` call. The existing first-open test asserts this with `exactly = 1`.

Two route tests were added in the Hilt activity with the fake `LinksRepository`:
- `a new version of the note saved with an inline link loads the panel's links again`: the test switches to Edit, replaces the body with one that has `[[task:…]]`, and switches back to View. That saves version 4, and the panel then shows the new task link. The test checks for exactly 2 `list` calls.
- `the note opened again loads its links again`: the test goes back to the list, opens the note again, and checks that the panel shows the updated links (2 `list` calls).

Both new tests failed when the `reload()` call was disabled on purpose ("8 tests completed, 2 failed"). They pass with the fix. This shows they catch the bug.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' lintDebug` - Pass (NoteEditorLinksRouteInAppTest: 8 tests, 0 failures, 0 errors)
- Same test class with `reload()` disabled on purpose - Fail as expected (2 new tests failed); the change was then reverted

### CR-2 - Route wiring for opening a linked item in place is untested

Files changed:
- none (already covered)

Problem:
The review said the route behavior had no tests: an InApp target saves and then opens in place, back/close returns to the note, and Screen targets are ignored.

Fix:
No code change was needed. `NoteEditorLinksRouteInAppTest.kt` (round 1, commit 41b899452) already covers this through the real route in MainActivity:
- `a linked note opens in place, and its close returns to the note instead of the list`
- `the system back from a linked note returns to the note`
- `a change not saved yet is saved before the linked note is opened` (the order of events is "saved" first, then "get Packing list")
- `a linked item without an Android screen keeps the note open` (Screen target ignored, no `get` call)

All of them pass in the run above.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' lintDebug` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' lintDebug` - Pass (no output besides JVM CDS warnings; NoteEditorLinksRouteInAppTest 8/8)

Full test suite:
- Not run (the parent worker runs `sh scripts/check.sh`)

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (part of the command above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
