# Code Review

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff origin/main HEAD` excluding `tasks/` and `reviews/` (final code after the fixes in reviews/gtd-links-everywhere/fixes.md). Uncommitted working-tree test changes from the parallel test-writer were not reviewed.

Requirements:
- tasks/gtd-links-everywhere.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.3 (linked-items panel on every detail view), §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md conventions (i18n en+de, lint, tests), AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 3f534445dfe04d71b7f0956e63e44d293c5f145a
- Base/start: origin/main ce03deae60ce4cf8fa0a4e33c08789101bea00b2
- Commits reviewed: 17 (including merges)

Summary: 0 P0, 0 P1, 3 P2, 1 P3

Criteria check (from code and tests):
- 1 Project detail panel: web `project-detail-page.html` renders `app-linked-items` (PROJECT); Android `ProjectsScreen.kt` has an icon toggle in the header and a `LinkedItemsSection(PROJECT)`. Tests: projects-states.spec.ts, ProjectLinksToggleTest. Met.
- 2 File preview panel: web preview dialog has a "Linked items" toggle with a side panel (FILE); Android `FilePreviewView` has a toggle that shows the links instead of the content. Tests: preview-dialog.spec.ts, FilePreviewLinksTest. Met, but see CR-2 for what happens when a link is opened on the web.
- 3 Draft email: web uses the linked contact, then the waiting-for text, then nothing. Android uses only the waiting-for text (see CR-3). The web parsing is weaker than the Android parsing (see CR-1).
- 4 Strings en+de: the new units are in messages.xlf and messages.de.xlf; the Android strings are in values and values-de. Met.
- 5 Tests: new tests on every changed part. Ran the changed web specs at HEAD with `npx ng test --watch=false --include` (lists, preview, projects-states, compose): the committed specs pass. The only 3 failures come from the test-writer's uncommitted additions to list-views.spec.ts, and they confirm CR-1. Gradle was not run, as the parent asked.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Web emailIn keeps surrounding parentheses or brackets, so the To address is wrong**
    - File: `web/src/app/features/gtd/lists/list-views.ts:69`
    - Category: Correctness
    - Problem: The character class `[^\s<>"',;]` does not exclude `(`, `)`, `[` or `]`. For a waiting-for text like "Anna (anna@example.org)", `emailIn` returns `(anna@example.org)` (checked with node). For "[bob@example.org]" it returns `[bob@example.org]`. The Android version was fixed for this in TW-1 (`WaitingScreen.kt:115` excludes `()[]`), but the web version was not. The uncommitted test-writer cases in list-views.spec.ts fail for this reason.
    - Why it matters: "Draft email" pre-fills compose with an address that `isValidAddress` (recipient-input.ts:35) rejects. The recipient is then invalid or gets filtered out when the draft is saved, so the criterion's "prefilled To from the waiting-for text" fails for a common way of writing an address. Web and Android also behave differently for the same task.
    - Suggested fix: Use the same character class as Android (also exclude `()[]`) and add the parentheses and brackets cases to list-views.spec.ts.

- [ ] **CR-2 | Opening a linked item from the web file preview leaves the full-window dialog on top**
    - File: `web/src/app/features/files/preview/preview-dialog.html:109` (with `openPreview` in `preview-dialog.ts:122`)
    - Category: Correctness
    - Problem: The panel's rows are `routerLink`s (`shared/links/linked-items.html:119`). The preview is a 100vw x 100vh MatDialog using the default `closeOnNavigation`. In the CDK this maps to `disposeOnNavigation: true`, which only reacts to `Location.subscribe`, that is popstate (`@angular/cdk` `_overlay-module-chunk.mjs:784-786`). A router navigation from a link click does not close it, and no code in the app closes dialogs when the router navigates. So the route changes underneath while the preview still covers the whole window.
    - Why it matters: Clicking a linked item seems to do nothing. The user only sees the item after closing the preview, and Back then behaves unexpectedly. The other `app-linked-items` hosts are pages, not modal dialogs, so this case is new with this task.
    - Suggested fix: Close the dialog when a link in the panel is followed. For example, close it on a click inside `.links a`, or subscribe to `Router.events` `NavigationStart` in `PreviewDialog` and call `dialogRef.close()`. Add a spec that clicks a linked row and expects the dialog to close.

- [ ] **CR-3 | Android "Draft email" ignores the linked contact (criterion 3 only partly met)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt:121` (with `gtd/data/GtdModels.kt:42`)
    - Category: Integration
    - Problem: Criterion 3 asks for To "from the linked contact or waiting-for text" on web and Android. `followUpMailUri` and `followUpMailIntent` use only `emailIn(task.waitingForText)`. The Android `GtdTask` model does not map `waitingForContactId`, although the API exposes it (`api/openapi.yaml`, TaskResponse). The deviation is recorded as an assumption ("switch to in-app compose prefill later"), but no follow-up task exists for it.
    - Why it matters: A task delegated to a contact without an address in its text gets an empty To on Android but a filled one on the web, which breaks the parity the task was planned for (docs/PARITY.md).
    - Suggested fix: Map `waitingForContactId` into `GtdTask` and look up the contact's first email, or keep the recorded assumption and add a follow-up task (`tasks.py new`) so the gap stays visible. The parent worker decides.

## P3 Low

- [ ] **CR-4 | Compose page doc comment line is far longer than the rest of the comment**
    - File: `web/src/app/features/mail/compose/mail-compose-page.ts:61`
    - Category: Quality
    - Problem: The inserted text "a new one may start with `?to=` and `?subject=`." was added to an existing line without re-wrapping it. The line is now about 140 characters, while the surrounding comment wraps at about 100.
    - Why it matters: Small readability and style inconsistency (Prettier does not re-wrap comments).
    - Suggested fix: Re-wrap the paragraph.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 are web defects in the behavior this task adds. CR-3 needs the parent worker to decide (implement it, or keep the assumption and add a follow-up task).
