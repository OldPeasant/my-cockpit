# Code Review

Date: 2026-11-08

Task: and-mail-note-links - Android mail: create note and linked items

Scope: committed diff `49b1d117a..2fac1622b` (task commit only; uncommitted working-tree files such as test-writer output were not reviewed)

Requirements:
- tasks/and-mail-note-links.md (acceptance criteria and the two recorded assumptions)
- AGENTS.md project notes (conventions, strings EN/DE, tests)
- Web reference: web/src/app/features/mail/read/mail-read-page.ts gtd() (notice, then links reload) and the server endpoint `POST /api/v1/mail/messages/{id}/to-note` (MailGtdController, api/openapi.yaml)
- Existing Android patterns: ContactsScreen.kt ContactDetailRoute, TaskDetailScreen.kt, core/links/LinkedItemsPanel.kt, search/SearchItemScreen.kt

Git range:
- Branch: main
- HEAD: 2fac1622b0e1740ef4abe61610219c9dbf207c69
- Base/start: 49b1d117ae2928436695d299aa85451676ed42f4
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 1 P2, 0 P3

Static review only (Gradle cannot run in this sub-agent); the parent runs the build and test suites.

Verified as correct:
- `MailApi.toNote` posts to `api/v1/mail/messages/{id}/to-note`, the same server endpoint the web uses, returning `MailGtdItemDto`; no test fake implements `MailApi`, so the interface addition does not break test compilation.
- `MailReadRepository.gtd(NOTE)` routes to `toNote`; the exhaustive `when` blocks in `Notice` cover NOTE for created and failed texts.
- The three new strings exist in `values/strings_mail.xml` and `values-de/strings_mail.xml` with matching placeholders.
- The links panel uses `ItemRef("EMAIL", message.id)`, the same type the search and `SearchItemScreen` use for mails; the Hilt guard (`GeneratedComponentManagerHolder`) and the keyed `hiltViewModel(linksViewModelKey(...))` follow ContactDetailRoute.
- After a successful GTD action the panel reloads (`LaunchedEffect(state.notice)`), matching the web's `links()?.reload()` after gtd(); `run()` clears the notice before each action, so two equal `Created` notices in a row still re-trigger the effect.
- Opening a linked item in place (saveable `type:id`, BackHandler, SearchItemScreen) mirrors ContactDetailRoute. A linked EMAIL reuses the same `MailReadViewModel` instance (same ViewModelStore owner); its close resets the view model and the outer route's `LaunchedEffect(viewModel, id)` reloads the original mail on return, so state is consistent. Re-firing `onMarked` on return is idempotent (`MailListViewModel.marked`).
- `SearchTarget.Screen` being ignored is a recorded assumption consistent with Contacts; not reported.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New UI behavior has no Compose/screen tests (criterion 4)**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt:80`
    - Category: Tests
    - Problem: The only test added in the commit is the repository test `create note posts to to-note and returns the note's title` (MailReadRepositoryTest.kt). Nothing in the reviewed commit covers: the new "Create note" toolbar button (tag `MailReadTags.gtd(MailGtdAction.NOTE)`) invoking `callbacks.gtd(NOTE)` and being disabled while busy or without a message (the existing `actionTags` list at MailReadScreenTest.kt:80-87 still lists only TASK, WAITING, ARCHIVE, DELETE, MORE); the NOTE created and failed notices in English and German; the `linksPanel` slot being rendered below the body (`MailReadTags.LINKS`); the route-level panel reload after a `Created` notice; and opening a linked in-app item and returning with back.
    - Why it matters: Criterion 4 requires "Unit/Compose tests cover the new behavior". The screen and route changes (MailReadScreen.kt:113-193, 329-331, 455, 461, 571-580) are the bulk of the task and are currently unverified; a regression (button removed, wrong action, panel slot not rendered, German string missing) would pass the suite.
    - Suggested fix: Add NOTE to `actionTags` in MailReadScreenTest; add screen tests for the note button click, the NOTE created/failed notice texts (EN plus a German test like MailReadSpamGermanTest), and that `linksPanel` content appears under `MailReadTags.LINKS`; add a view-model test for `gtd(NOTE)` success/failure notices. If test-writer has already added such tests in the working tree, commit them and have the parent confirm they pass.

## P2 Medium

- [ ] **CR-2 | Toolbar now needs 344dp and squeezes the overflow menu on 320dp-wide windows**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:318`
    - Category: Correctness
    - Problem: The toolbar Row holds back plus six 48dp IconButtons (task, waiting, note, archive, delete, more) with 4dp horizontal padding: 7 x 48dp + 8dp = 344dp. Before this change it was 296dp. In a Row, unweighted children are measured in order against the remaining width, so on a 320dp-wide window (small phones, larger system display size, split-screen; also Robolectric's default `w320dp` configuration used by the screen tests) the last child, the "More" button that holds mark unread, flag, move and spam, gets only about 24dp or less and is clipped.
    - Why it matters: Four actions become hard or impossible to reach on narrow windows; the regression is caused by adding the 7th icon (the recorded assumption chose the placement but not the layout consequence).
    - Suggested fix: Keep the toolbar within 320dp, e.g. move "Create note" (or one of the GTD actions) into the overflow menu, or make the action row horizontally scrollable / collapse actions into the menu when width is short. Add a screen test asserting the MORE button is displayed at full size at 320dp.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
