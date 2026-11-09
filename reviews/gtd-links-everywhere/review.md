# Code Review

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff c46d3f72e..HEAD -- web android` (task commits 241f7a769, fec367b90, bcbc79893, 3c08648c0); committed changes only

Requirements:
- tasks/gtd-links-everywhere.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (linked-items panel on every detail view), §5.3 (waiting follow-up)
- AGENTS.md project notes and docs/ARCHITECTURE.md conventions (i18n en+de, lint, tests)
- Parent instructions: i18n on both clients, mailto/URL encoding, query-param prefill, tests

Git range:
- Branch: main
- HEAD: 3c08648c05a120d1b91312aba5ec7c268d20a6cb
- Base/start: c46d3f72e3a34da1322e3a9b0ac71be454a725e2
- Commits reviewed: 4

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New behavior on web and Android project/file views has no unit tests**
    - File: `web/src/app/features/gtd/lists/waiting-page.ts:54`, `web/src/app/features/gtd/lists/list-views.ts:67`, `web/src/app/features/mail/compose/mail-compose-page.ts:303`, `web/src/app/features/files/preview/preview-dialog.ts:53`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:417`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:143`
    - Category: Tests
    - Problem: Criterion 5 requires unit tests for the new behavior in each changed part. The only new tests are the Android waiting tests (`ListScreensTest`) and one web assertion that `app-linked-items` exists on the project detail page (`projects-states.spec.ts:409`). Nothing in the web specs references `draftEmail`, `followUpMail`, `emailIn`, the compose `?to=`/`?subject=` prefill or the preview `showLinks` toggle. No Android test references `ProjectsTags.LINKS_TOGGLE`, `PreviewTags.LINKS_TOGGLE` or the `linksPanel` slot of `FilesContent`/`FilePreviewView`.
    - Why it matters: Criterion 5 is not met. The untested parts include the contact lookup and its fallback in `draftEmail` (getContact fails, contact without emails, no contact), the prefill on the compose page (`to` blank or missing, subject only), arrow keys ignored inside `.links` in the preview, and on Android the toggles and the slot hidden when `linksPanel` is null.
    - Suggested fix: Add web specs for `emailIn`/`followUpMail` (address given, address in text, none), `WaitingPage.draftEmail` (navigates with the contact's first email, falls back when getContact rejects), compose prefill from query params, and the preview toggle (panel shown, ArrowLeft/Right inside it do not step). Add Robolectric tests that the project-detail links toggle and the file-preview links toggle show and hide a fake panel, and that there is no toggle when `linksPanel` is null.

## P2 Medium

- [ ] **CR-2 | A linked item opened from the Android file preview is drawn on top of the still-composed files screen instead of replacing it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:216-220`
    - Category: Correctness
    - Problem: `FilesRoute` always emits `FilesContent` (with its preview) and then, when `linked` is set, emits `SearchItemScreen` as a sibling with no `modifier`. Every other call site (`ContactsScreen.kt:209-211`, `MailReadScreen.kt:116-118`, this task's `ProjectsScreen.kt:379-387`) replaces the screen with an if/else or early `return`, and passes `modifier`. Here, how the screen looks depends on the parent layout stacking its children (a Column parent would place the item below the files). The item screen also does not get the route's `modifier` (insets/padding), and the files/preview stays composed and can receive touches wherever the item screen does not consume them.
    - Why it matters: Opening a linked note, mail, contact or task from a file preview can show a broken layout, or let the preview underneath react to touches. No test covers this path.
    - Suggested fix: Follow the existing pattern. When `linked` is set, render only `SearchItemScreen(type, id, onClose, modifier = modifier)` (with its `BackHandler`) and skip `FilesContent`. The preview state stays in the view model, so Back returns to the preview.

## P3 Low

- [ ] **CR-3 | Android "Draft email" fails silently without a mail app**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt:54`
    - Category: Quality
    - Problem: `ContactIntents.launch` returns `false` on `ActivityNotFoundException`, and the result is ignored, so the tap does nothing. The Files screen shows a "no app" toast in the same situation (`FilesScreen.kt:144-145`).
    - Why it matters: On devices without a mail app the button appears broken.
    - Suggested fix: If `launch` returns false, show a short message (new en/de string).

## Review conclusion

Material issues found; fix before completing the task.

Verified without findings:
- Criterion 1: web project detail has `<app-linked-items PROJECT>`, and Android project detail has a collapsible `LinkedItemsSection(PROJECT)`.
- Criterion 2: web preview has a "Linked items" toggle with a side panel (FILE), and Android preview has a top-bar toggle.
- Criterion 3: web To is the contact's first email, else an address in the waiting-for text, and the subject is the task title. Android To comes from the waiting-for text only, because the Android `GtdTask` has no `waitingForContactId`. This matches the recorded assumption.
- Criterion 4: all new strings have English and German versions in `messages.xlf`/`messages.de.xlf` and `values{,-de}`.
- Encoding: Router `queryParams` encode the web prefill. Android encodes the mailto with `Uri.encode`, and the regex keeps `?`, `&` and the like out of To or encodes them. The compose prefill only fills fields of a new, unsent draft.
