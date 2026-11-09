# Code Review

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff 4a3afeb14 HEAD` (final pass after the fixer). tasks/ and reviews/ are excluded. Reviewed files: web project detail, file preview dialog, waiting page and list-views, the mail compose `?to=`/`?subject=` prefill, web xlf (en+de); Android FilePreviewView/FilesScreen, ProjectsScreen, WaitingScreen, en/de strings; the related web specs and Android tests.

Requirements:
- tasks/gtd-links-everywhere.md acceptance criteria and recorded assumptions (web compose prefill, Android mailto intent, CR-3 linked contact on Android deferred to and-waiting-contact-mail)
- SPEC.md §4.3 (Linked items panel on every detail view), §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md and AGENTS.md conventions (i18n en+de, lint, tests, feature folders)
- reviews/gtd-links-everywhere/fixes.md (earlier CR-1..CR-4, TW-1..TW-5)

Git range:
- Branch: main
- HEAD: 552099febf91af06cca83fba8b840e5968b5f8f8
- Base/start: 4a3afeb14
- Commits reviewed: 23 (including merges)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Verification done by the reviewer: `cd web && npx ng test --watch=false` with the five changed specs (list-pages, list-views, preview-dialog, projects-states, mail-compose-page) -> 5 files, 214 tests passed. Gradle was not run, as instructed.

Earlier findings checked again: the web `emailIn` now drops brackets and a trailing full stop (TW-4/TW-5, same regex on both clients); the preview dialog closes on `NavigationStart` (CR-2); draft-email navigation is guarded against a left page and against repeated clicks (`draftRequest`/`left`); the Android links panel state survives opening a linked item (`rememberSaveableStateHolder` in FilesRoute; state kept above the early return in ProjectDetail). CR-3 (Android ignores the linked waiting-for contact) is deferred by a recorded assumption and the follow-up task and-waiting-contact-mail, so it is not reported again.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Web: files without a preview have no linked-items panel anywhere**
    - File: `web/src/app/features/files/preview/preview-dialog.ts:49`, `web/src/app/features/files/browser/files-page.ts:141`
    - Category: Correctness
    - Problem: On web, the linked-items panel exists only inside the preview dialog, and the dialog lists only previewable files (`this.data.files.filter((file) => previewKind(...))`); `files-page.ts` downloads every other file instead of opening it ("Files open in the preview when there is one for their type, else they download"). A ZIP, DOCX or other file without a preview type therefore has no place on web where its linked items can be seen. Android does not have this gap: `FilesScreen.kt:779` opens every file in the preview, which shows an "Unsupported" notice and the links toggle.
    - Why it matters: SPEC.md §4.3 asks for the panel on every detail view, and the criterion names "Web file details/preview". For a whole class of files the panel is unreachable on web, and web differs from Android here (docs/PARITY.md lists this task as the parity fix).
    - Suggested fix: give non-previewable files a way to reach their links on web, for example a "Linked items" entry in the file row menu that opens a small dialog with `app-linked-items` for `{ type: 'FILE', id }`, or let the preview dialog accept a non-previewable file and show a "no preview" notice with the links toggle, as Android does. Add a spec for it. If this is deliberately out of scope, record it with `tasks.py assume` and a follow-up task.

## P3 Low

- [ ] **CR-2 | Android: the project links toggle and the file links toggle are labelled differently**
    - File: `android/app/src/main/res/values/strings_projects_lists.xml:149`, `android/app/src/main/res/values-de/strings_projects_lists.xml:149`
    - Category: Quality
    - Problem: The project toggle's "show" description is `projects_links_show` = "Linked items" / "Verknüpfte Elemente", while the file preview toggle uses `files_links_show` = "Show linked items" / "Verknüpfte Elemente anzeigen". Both are icon-only buttons with the same show/hide behavior, and both "hide" strings use the verb form.
    - Why it matters: screen-reader users hear a noun on one screen and an action on the other for the same control, and "Linked items" does not say that the button shows them. (The project toggle is an `IconToggleButton`, so its checked state is announced; the files toggle is a plain `IconButton`.)
    - Suggested fix: change `projects_links_show` to "Show linked items" / "Verknüpfte Elemente anzeigen" and update the assertions in `ProjectLinksToggleTest` that look up the label.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) is a gap in criterion 2 / SPEC.md §4.3 coverage on web. The parent worker may fix it or record it as an assumption with a follow-up task. CR-2 is optional cleanup. The other criteria are implemented and tested on both clients: project panel on web and Android, the file preview panel, Draft email with To and subject prefilled (Android uses only the waiting-for text, as recorded in the assumption), and en+de strings.
