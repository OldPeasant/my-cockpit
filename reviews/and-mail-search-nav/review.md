# Code Review

Date: 2026-11-08

Task: and-mail-search-nav - Android mail search kept on return, previous/next, back to origin

Scope: `git diff 7fcf993cf..39e14ab44 -- android` (task commits 5139d2ef0 and 39e14ab44), committed changes only

Requirements:
- tasks/and-mail-search-nav.md acceptance criteria and recorded assumptions (#1-#4)
- SPEC.md, docs/ARCHITECTURE.md, docs/PARITY.md, docs/QUALITY.md, AGENTS.md project notes
- Existing Android conventions (MailListViewModel, MailReadViewModel, CockpitShell navigation)

Git range:
- Branch: main
- HEAD: 39e14ab44691121750edec799e6e446f31c967c8
- Base/start: 7fcf993cfcc5ad9dba77130366e0b352403f767a
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Files reviewed in full or in their relevant context: `mail/list/MailScreen.kt` (MailRoute), `mail/list/MailListViewModel.kt` (loadMore, removed, markSeen, search state), `mail/read/MailReadScreen.kt`, `mail/read/MailReadViewModel.kt` (open/reset/session handling, act, busy), `ui/CockpitShell.kt` (item and task-detail routes, new `close(entry)`), `search/SearchItemScreen.kt` (why the double close happened), both `strings_mail.xml` files, and the three new test classes.

Checked and found correct:
- Removal flow: the `LaunchedEffect(state.removed)` block keeps the `navigation` from the composition where `removed` turned true, so `next` comes from the list before `onRemoved` filters it out. `open(next)` changes `id`, `MailReadViewModel.open` resets the state (`removed = false`, new session), and the effect does not fire twice.
- Switching mails: `open(id)` moves the session forward, so late answers for the previous mail (load, act, images) are dropped. Previous/next are disabled while an action runs (`busy`), so a write for one mail cannot land while another mail is shown.
- `MailListViewModel.removed` filters both the mailbox and the search results, so after a delete the neighbours and the "n of m" count stay correct in both lists.
- Search kept on return and across rotation: the state lives in the Activity-retained `MailListViewModel` and the open mail in `rememberSaveable`. The tests cover the toolbar back, system back and recreation, and check that the search is not run again.
- The shell guard `close(entry)` only pops when the entry is still the current one. This fixes the double pop that happened because `SearchItemScreen` passes `onRemoved = { onClose() }` and `close()` also calls `onClose`. It also stops a stale close from an item screen with a task detail above it from popping the wrong screen. `navigateUp` updates the back stack synchronously, so the second call sees the new current entry.
- Strings: the en and de entries match (`mail_read_previous`, `mail_read_next`, `mail_read_position` with positional args).
- The changes stay inside the task's `touches`. No new dependencies, no API changes, nothing sensitive logged, and test data is fictitious.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The next-page request from the read view runs only once: if it fails or is skipped, Next stays disabled at the loaded end with no notice**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:177`
    - Category: Correctness
    - Problem: `LaunchedEffect(atLoadedEnd) { if (atLoadedEnd) viewModel.loadMore() }` runs only when `atLoadedEnd` turns true. `MailListViewModel.loadMore` (MailListViewModel.kt:132-153) returns without doing anything while `loading` (a refresh) or `loadingMore` is running. On failure it only sets the list's `message` (OFFLINE/FAILED), which the read view does not show. In both cases `atLoadedEnd` stays true, so the effect does not run again. Scenario: the Mail tab is shown again and `LifecycleResumeEffect` starts `refresh()`. While it runs, the user opens the last loaded mail, so `loadMore` is skipped. Or the device is briefly offline when the user reaches that mail. The read view then shows "50 of 50" with Next disabled, although `nextCursor` says more mail exists. The only recovery is to step Previous and then Next again, or to go back to the list.
    - Why it matters: Next looks like the end of the mailbox when it is not, and nothing tells the user why. Assumption #3 ("Opening the last loaded mail of a mailbox fetches the next page so Next can go on") does not hold in these cases.
    - Suggested fix: Also key the effect on the list's `loading`/`loadingMore` state (for example `LaunchedEffect(atLoadedEnd, state.loading, state.loadingMore)`), so it runs again once a refresh or an earlier page load ends. Optionally keep Next enabled while `nextCursor != null` and load on tap. Add a test where the first `loadMore` is skipped or fails while the read view is open.

- [ ] **CR-2 | No test for "remove the last mail of the list closes the view", or for previous/next being disabled while an action runs**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailReadNavigationInAppTest.kt:184-213`
    - Category: Tests
    - Problem: The tests cover delete and archive when a next mail exists. They do not cover the other branch of `MailReadScreen.kt:92` (`else close()`), which assumption #2 makes required behaviour: at the end of the list, archive/delete/spam returns to the list. They also do not cover the `enabled = !state.busy` gate in `MailReadContent` (MailReadScreen.kt:194). That gate keeps a pending archive/delete for mail A from being applied while mail B is shown.
    - Why it matters: A regression in either branch (for example closing on every removal, or allowing navigation during an action) would not be caught by the suite.
    - Suggested fix: Add an in-app test that deletes the last mailbox mail (or the last search result) and checks that the list shows without that mail. Add a `MailReadContent` Compose test with `busy = true` that checks PREVIOUS and NEXT are not enabled.

## Review conclusion

No material issues found.
