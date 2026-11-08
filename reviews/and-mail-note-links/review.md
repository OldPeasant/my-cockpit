# Code Review

Date: 2026-11-08

Task: and-mail-note-links - Android mail: create note and linked items

Scope: committed diff `49b1d117a..f2c4f9e82`, task commits only (423c4110d, 2f887b549, 3e75cb196, d6d6e39e5, ed6e3a240, f2c4f9e82). Board-only commits by other agents in the range (tasks/and-journal-full.md, tasks/and-mail-chip-a11y.md) are outside the task and were not reviewed. Re-review of the final revision after the fixes for CR-1 and CR-2 of the first review.

Requirements:
- tasks/and-mail-note-links.md (acceptance criteria and the three recorded assumptions, including the superseding one: "Create note" is the first item of the More menu)
- AGENTS.md project notes (conventions, strings EN/DE, tests, lint)
- Web reference: `POST /api/v1/mail/messages/{id}/to-note` (same endpoint as web), web read view notice then links reload
- Existing Android patterns: ContactsScreen.kt ContactDetailRoute, core/links/LinkedItemsPanel.kt, search/SearchItemScreen.kt
- Build/test result supplied by the parent: at f2c4f9e82, `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL, all tests pass (this reviewer cannot run Gradle; static review)

Git range:
- Branch: main
- HEAD: f2c4f9e82
- Base/start: 49b1d117ae2928436695d299aa85451676ed42f4
- Commits reviewed: 6 task commits (17 commits in the range, 11 are board commits of other tasks)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of earlier findings

- **CR-1 (P1, missing screen/VM tests): resolved.** New tests cover the behavior the first review listed:
  - `MailReadNoteLinksTest`: Create note in the menu calls `gtd(NOTE)` and closes the menu; English label; not in the toolbar and placed above "Mark unread" in the menu; unreachable while busy, without a mail and after a failed load (More disabled); NOTE created and failed notice texts; `linksPanel` slot rendered under `MailReadTags.LINKS` below the body, also for text-only mails and while busy; no panel without a mail or after OFFLINE/FAILED/GONE; route-level create note success and failure through the real `MailReadViewModel` with a mocked repository; route renders the links area.
  - `MailReadNoteGermanTest`: German menu label and both German notices.
  - `MailReadNoteViewModelTest`: success notice with title and busy toggling, failure for `Failed(500)`, `Failed(null)` and `Offline`, double tap creates one note, no call before a mail is shown, a late answer for a closed mail is not shown on the next one.
  - `MailReadRepositoryTest`: NOTE posts to `to-note`.
  - Remaining gap (Hilt-only route wiring) is reported as CR-1 below at P3; it is acknowledged in tests.md and fixes.md.
- **CR-2 (P2, 7-icon toolbar clips at 320dp): resolved.** `MailReadScreen.kt:319-336` now holds back, To task, To Waiting, Archive, Delete and More (6 buttons, 6 x 48dp + 8dp padding = 296dp, the same width as before the task), and "Create note" is the first `MenuItem` of `MoreMenu` (`MailReadScreen.kt:360-362`) with tag `MailReadTags.gtd(NOTE)` and the existing `pick` close-then-act pattern. The test `on a 320dp wide window the menu button is shown at full size` (`@Config(qualifiers = "w320dp-h470dp")`) asserts every toolbar button is displayed, at least 40dp wide and inside the root bounds, which would have failed with the squeezed More button. The placement differs from the web (next to the GTD buttons); this is recorded as an assumption in the task file, so it is not reported.

Verified as correct in the final code:
- `MailApi.toNote` posts to `api/v1/mail/messages/{id}/to-note` returning `MailGtdItemDto`; `MailReadRepository.gtd(NOTE)` routes to it; the exhaustive `when` blocks in `Notice` cover NOTE.
- `MenuItem` now takes a `tag: String`; every existing call site was converted to `MailReadTags.action(...)`, so the existing menu tags (unread, flag, unflag, move, spam) are unchanged.
- The menu stays gated: `DropdownMenu(expanded = open && enabled)` with `enabled = state.message != null && !state.busy`, so Create note cannot run without a mail or during another action, matching the toolbar actions.
- The three new strings exist in `values/strings_mail.xml` and `values-de/strings_mail.xml` with matching `%1$s` placeholders.
- Links panel: `ItemRef("EMAIL", message.id)`, Hilt guard via `GeneratedComponentManagerHolder`, keyed `hiltViewModel(linksViewModelKey(...))`, reload on `MailReadNotice.Created`, in-place linked item with `rememberSaveable(id)` plus `BackHandler`, all as in ContactDetailRoute; the panel area is capped at 240dp and scrollable below the body (recorded assumption).
- No secrets, logging, or new dependencies introduced; changes stay inside the task's `touches` apart from the one-line `MailApi.toNote` addition, which is recorded as an assumption.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Hilt-only route wiring of the links panel has no automated test**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:113-126`
    - Category: Tests
    - Problem: `mailLinks()` returns `null` outside a Hilt activity, so the Robolectric route tests never exercise the real `LinkedItemsSection` for the mail, the `links?.reload()` after a `Created` notice (line 126), or the linked-item overlay branch (`SearchItemScreen` plus `BackHandler`, lines 113-120). `the route shows the links area for a loaded mail` only asserts the empty container exists.
    - Why it matters: A regression in the reload trigger or the open/back handling would not be caught by `testDebugUnitTest`. Impact is limited: the code mirrors the already-used ContactDetailRoute pattern, and `LinkedItemsSection` has its own tests from and-links-panel. The gap is documented in tests.md and fixes.md.
    - Suggested fix: Optional; accept the documented gap, or cover it later with a Hilt test activity or an instrumented/e2e flow (create a note from a mail, see it in the panel, open it, press back). Note that tests.md still describes "Create note" as a toolbar button (written before the CR-2 fix); the re-run of test-writer on the final code should refresh it.

## Review conclusion

No material issues found.
