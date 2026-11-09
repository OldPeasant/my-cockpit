# Code Review

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff 9453c6284..50ccac4f8 -- web android` (committed changes only; final reviewed revision 50ccac4f886e80d0cd77d9e79c36e1e5eaa95226). This is a re-review after the fixes in b2068fad9 and the messages.xlf re-extraction in 50ccac4f8.

Requirements:
- tasks/gtd-links-everywhere.md acceptance criteria and recorded assumptions (web compose `?to=&subject=` prefill, web preview side panel, Android collapsible project section, Android `mailto:` ACTION_SENDTO)
- SPEC.md §4.3 (linked-items panel on every detail view), §5.3 (waiting follow-up)
- AGENTS.md project notes (i18n en+de, lint, tests, feature folders)
- Parent instructions: re-check previous CR-2/CR-3, review the whole final code, static review of Android (no Gradle run)

Git range:
- Branch: main
- HEAD: 50ccac4f886e80d0cd77d9e79c36e1e5eaa95226
- Base/start: 9453c6284581d85d7b0fab7c92fcf28e9931aff0
- Commits reviewed: the task commits in 9453c6284..50ccac4f8 (the merge of origin/wip/gtd-links-everywhere, the b2068fad9 fix commit and the 50ccac4f8 xlf re-extraction). Git log was not available to this reviewer; the scope was resolved from the diff between the two hashes.

Summary: 0 P0, 1 P1, 0 P2, 2 P3

## Status of the previous findings

- Previous CR-1 (missing tests): partly resolved. The web specs now cover `emailIn`/`followUpMail` (list-views.spec.ts), `WaitingPage.draftEmail` with contact, contact without email, contact failure, and no contact (list-pages.spec.ts), compose prefill from `?to=`/`?subject=`, including a blank `to` (mail-compose-page.spec.ts), the preview toggle and arrow keys inside the panel (preview-dialog.spec.ts), and the project panel (projects-states.spec.ts). On Android only the waiting behavior is tested. See CR-1 below.
- Previous CR-2 (linked item drawn over FilesContent): resolved. `FilesScreen.kt:173-178` now renders only `SearchItemScreen(..., modifier = modifier)` with its own `BackHandler` and returns early, like `ProjectsScreen.kt:379-383`.
- Previous CR-3 (no feedback without a mail app): resolved. `WaitingScreen.kt:56-60` shows the `waiting_no_mail_app` toast (en/de) when `ContactIntents.launch` returns false. `launch` catches `ActivityNotFoundException`.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The Android project-detail and file-preview links toggles have no unit tests**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:417-430`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:125-156`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:232-240`
    - Category: Tests
    - Problem: Criterion 5 asks for unit tests of the new behavior in each changed part. In the reviewed range, the only Android test change is `ListScreensTest` (waiting "Draft email"). No test under `android/app/src/test` references `ProjectsTags.LINKS_TOGGLE`, `PreviewTags.LINKS_TOGGLE`, `PreviewTags.LINKS` or the `linksPanel` parameter of `FilesContent`/`FilePreviewView`. Untested: toggling shows and hides the panel, the panel replaces the preview content, the toggle is missing when `linksPanel` is null, and the panel is keyed by file when stepping. The parent says a test-writer is adding these tests in parallel. They are not in the reviewed revision.
    - Why it matters: Criterion 5 is not met for the Android projects and files parts until those tests are committed and pass.
    - Suggested fix: Add Robolectric tests. `FilesContent`/`FilePreviewView` get a fake `linksPanel`: toggle shows the fake panel instead of the content and hides it again, and with `linksPanel = null` there is no `LINKS_TOGGLE`. The project-detail toggle flips between the show and hide labels. `LinkedItemsSection` renders nothing without Hilt, so this test can only check the toggle state, not the panel. Then re-review the final revision.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Returning from a linked item collapses the linked-items panel it was opened from (Android)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:378-384`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:173-178`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:125`
    - Category: Correctness
    - Problem: Both screens return early while `linked` is set. That removes the detail or preview from composition. `showLinks` (a `rememberSaveable` in `ProjectDetail` after the early return, and in `FilePreviewView`) is discarded with it, so Back shows the project or preview with the panel collapsed. In the file preview, the PDF page position (`PdfPagesView`'s `page`) is lost too, and the PDF is opened again. The comments promise "Back returns to the project/preview". The user does come back, but not to the panel they used.
    - Why it matters: Opening several linked items one after another means re-opening the panel each time. This is a small UX regression introduced by the CR-2 fix, and no test covers it.
    - Suggested fix: Move the toggle state above the early return. In `ProjectDetail`, declare `showLinks` before the `linked?.let { ... return }`. In Files, keep `showLinks` in `FilesRoute`, or pass it into `FilesContent`/`FilePreviewView` as state plus a callback. Optionally keep the page index in the preview state.

- [ ] **CR-3 | Web "Draft email" can navigate to compose after the user has left the page**
    - File: `web/src/app/features/gtd/lists/waiting-page.ts:397-408`
    - Category: Correctness
    - Problem: `draftEmail` awaits `getContact` and then always calls `router.navigate(['/mail/compose'], ...)`. Suppose the contact request is slow (bad network, waiting for a timeout) and the user navigates elsewhere meanwhile. When the request settles, the user is pulled to the compose page. There is no in-flight guard or busy state, so repeated clicks start parallel lookups and navigations.
    - Why it matters: An unexpected navigation away from the page the user chose. The impact is low because the lookup is usually fast.
    - Suggested fix: Skip the navigation if the component was destroyed meanwhile (`DestroyRef`), or if a newer draft request started. Optionally disable the button while the lookup runs.

## Review conclusion

Material issues found; fix before completing the task.

The blocking item is CR-1. It is resolved once the Android toggle tests that the parallel test-writer is adding are committed and pass. CR-2 and CR-3 are low priority.

Verified without findings:
- Criterion 1: web project detail renders `<app-linked-items [item]="{ type: 'PROJECT', id }">` (test asserts the PROJECT/p1 request). Android project detail has a collapsible `LinkedItemsSection(ItemRef("PROJECT", id))`. Linked items open via `SearchItemScreen`, and Back returns to the project.
- Criterion 2: web preview has an `aria-pressed` "Linked items" toggle and an `<aside id="preview-links">` with `app-linked-items` (FILE). It follows the current file, because `LinkedItems.item` is a signal input read in an effect. Arrow keys inside `.links` are ignored. Android preview has a top-bar toggle, and the panel is keyed by file id.
- Criterion 3: on the web, To is the linked contact's first email, else an address in the waiting-for text, else omitted. The subject is the task title. Router `queryParams` encode the values, and compose trims `to` and fills only a new draft. On Android, the `mailto:` To comes from the waiting-for text only, encoded with `Uri.encode`; the subject is set both in the URI and as `EXTRA_SUBJECT`. This matches the recorded assumption.
- Criterion 4: Android strings `files_links_show/hide`, `projects_links_show/hide`, `waiting_draft_email`, `waiting_draft_email_label` and `waiting_no_mail_app` exist in `values` and `values-de`. Web: `files.preview.links`, `gtd.waiting.draftEmail` and `gtd.waiting.draftEmailLabel` are in `messages.xlf` (restored by 50ccac4f8) and in `messages.de.xlf` with German targets.
- No secrets, no logging of user data, no unsafe HTML or URL construction introduced.
- Not run by this reviewer: `sh scripts/check.sh`, `npm run check` and the Android Gradle checks (per parent instruction; Bash access was limited to read-only inspection).
