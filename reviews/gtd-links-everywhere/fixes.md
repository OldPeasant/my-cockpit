# Fix Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: current task. Findings TW-1..TW-3 from `reviews/gtd-links-everywhere/tests.md` and CR-2, CR-3 (P3) from `reviews/gtd-links-everywhere/review.md`. Base commit 13b10e74e8c696a6724f9096e03a34bb00710e8a. The parent worker fixed TW-1..TW-3 in commits 3a36e2834 and 99fe47199. This fixer checked those commits and fixed CR-2 and CR-3 in the working tree.

## Fixed findings

### TW-1 - Email regex keeps parentheses (fixed by the parent, checked here)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt`

Problem:
`emailIn("Anna (anna@example.org)")` returned `(anna@example.org)`.

Fix:
The `EMAIL` character classes now also exclude `(`, `)`, `[` and `]`, in all three parts of the address. Checked: the regex is correct and matches the function's KDoc example.

Validation:
- `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests '*ListScreensTest' ...` - Pass (includes the "follow-up mail takes the address from the waiting-for text" case)

### TW-2 - Project links toggle breaks three project layout tests (fixed by the parent, checked here)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectLinksToggleTest.kt`

Problem:
A full-width `TextButton` row above the task list took height from the list. As a result, `ProjectsScreenTest`, `ProjectTaskListTest` and `FabClearanceTest` failed.

Fix:
The toggle is now an `IconToggleButton` in the header row (Link icon, contentDescription = show/hide label, same test tag). The old row is removed. `ProjectLinksToggleTest` now checks the content description instead of the text. Its assertions still check the same labels, in English and German, so they were not weakened. Checked: the three earlier layout failures now pass, and no `TextButton` import is left unused (ktlint passes).

Validation:
- `./gradlew ... --tests '*ProjectsScreenTest' --tests '*ProjectTaskListTest' --tests '*FabClearanceTest' --tests '*ProjectLinksToggleTest'` - Pass

### TW-3 - Lint UseKtx error (fixed by the parent, checked here)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt`

Problem:
`Uri.parse(followUpMailUri(task))` raised lint error `UseKtx`.

Fix:
The call now uses `followUpMailUri(task).toUri()` (`androidx.core.net.toUri`). The `Uri` import stays because `Uri.encode` still uses it.

Validation:
- `./gradlew lintDebug` - Pass (no errors)

### CR-2 - Returning from a linked item collapses the linked-items panel (Android)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`

Problem:
While a linked item is shown, the project detail and the file preview are removed from composition. Their `rememberSaveable` state was discarded with them: the open panel and, in the preview, the PDF page. Back showed the panel collapsed.

Fix:
- `ProjectDetail`: `showLinks` (still keyed by `project.id`) is now declared before the early return for `linked`. It therefore stays in composition while the linked item is shown.
- `FilesRoute`: `FilesContent` is wrapped in `rememberSaveableStateHolder().SaveableStateProvider("files")`. When the linked item replaces the files, the holder saves their saveable UI state, including `FilePreviewView`'s `showLinks` and `PdfPagesView`'s `page`. The state is restored on Back. Closing a preview still resets its state as before, because the holder only saves the subtree while the subtree is removed as a whole. The PDF bitmap is still re-rendered, which the finding accepted.

No regression test: `linked` is set only through `LinkedItemsSection` (Hilt) and `SearchItemScreen` (Hilt). Neither renders in the Robolectric tests without Hilt, which the review already noted for CR-1. The existing project and file tests still pass.

Validation:
- `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests '*ProjectsScreenTest' --tests '*ProjectTaskListTest' --tests '*FabClearanceTest' --tests '*ProjectLinksToggleTest' --tests '*FilePreviewLinksTest' --tests '*FilesScreenTest' --tests '*ListScreensTest'` - Pass (BUILD SUCCESSFUL)

### CR-3 - Web "Draft email" can navigate to compose after the user has left the page

Files changed:
- `web/src/app/features/gtd/lists/waiting-page.ts`
- `web/src/app/features/gtd/lists/list-pages.spec.ts`

Problem:
`draftEmail` always navigated to `/mail/compose` after the contact lookup. This happened even if the page had been destroyed meanwhile. Repeated clicks started parallel lookups, and each one navigated.

Fix:
`WaitingPage` sets a `left` flag through `DestroyRef.onDestroy` and numbers each draft request. After the lookup it navigates only if the page is still alive and the request is the latest one. Two regression tests hold `getContact`. In one, the fixture is destroyed during the lookup: no navigation. In the other, the button is clicked twice: two lookups, exactly one navigation, with the expected query params. The spec's `render` helper now returns its fixture; existing callers are unaffected.

Validation:
- `npm run check` (web) - Pass (196 test files passed, lint clean)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests '*ListScreensTest' --tests '*ProjectsScreenTest' --tests '*ProjectTaskListTest' --tests '*FabClearanceTest' --tests '*ProjectLinksToggleTest' --tests '*FilePreviewLinksTest' --tests '*FilesScreenTest'` - BUILD SUCCESSFUL
- `cd web && npm run check` - Pass (196 test files)

Full test suite:
- `sh scripts/check.sh` - "all checks passed" (server, web, Android ktlint + unit tests + lint)

Lint/type-check/build:
- Included in the commands above (ktlint, Android lint, web lint and build) - Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

- TW-1: Fixed by the parent (3a36e2834). Checked correct and complete.
- TW-2: Fixed by the parent (3a36e2834, 99fe47199). Checked correct and complete; the tests were not weakened.
- TW-3: Fixed by the parent (3a36e2834, 99fe47199). Checked correct; lintDebug is clean.
- CR-2: Fixed by this fixer (ProjectsScreen.kt, FilesScreen.kt). No Robolectric regression test, because the linked-item path needs Hilt.
- CR-3: Fixed by this fixer (waiting-page.ts), with two regression tests in list-pages.spec.ts.

The code changed after the review (CR-2, CR-3), so the test-writer and code-reviewer evidence is stale. They must run again on the final revision.
