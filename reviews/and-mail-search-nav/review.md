# Code Review

Date: 2026-11-08

Task: and-mail-search-nav - Android mail search kept on return, previous/next, back to origin

Scope: full task diff `git diff 7fcf993cf..a35993cf6 -- android` (commits 5139d2ef0, 39e14ab44, a43a49ca9, a35993cf6), committed changes only. Focus of this pass: `git diff 39e14ab44..a35993cf6` (the test-writer's tests and the CR-1 fix).

Requirements:
- tasks/and-mail-search-nav.md acceptance criteria and recorded assumptions (#1-#4)
- SPEC.md, docs/ARCHITECTURE.md, docs/PARITY.md, docs/QUALITY.md, AGENTS.md project notes
- Previous review of revision 39e14ab44 (CR-1, CR-2) and reviews/and-mail-search-nav/fixes.md
- Existing Android conventions (MailListViewModel, MailReadViewModel, CockpitShell navigation)

Git range:
- Branch: main
- HEAD: a35993cf6
- Base/start: 7fcf993cfcc5ad9dba77130366e0b352403f767a
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Validation reported by the parent worker on a35993cf6 (Gradle cannot run in this sub-agent): `mail.list.*`, `mail.read.*` and `ui.*` unit tests -> 414 tests, 0 failures; `ktlintCheck` and `lintDebug` clean. I did not run them myself.

## Status of earlier findings

- CR-1 (next-page request from the read view runs only once): resolved. `MailRoute` (`android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:180-188`) now keys the effect on `atLoadedEnd` and the open mail, calls `loadMore()` once on opening (also after an earlier failure), and then collects `viewModel.uiState` mapped to `canLoadAfter(current)` with `distinctUntilChanged`, calling `loadMore()` each time it turns true. Checked:
    - `MailListViewModel.loadMore` sets `loadingMore = true` synchronously before launching, so the first emission after the direct call is false and no duplicate request starts.
    - A refresh (`load`) clears `message` on success and sets `loading = false`, so the condition flips to true once a refresh running at open time ends, which is the skipped case from the scenario. The new in-app test drives exactly this with a pending `CompletableDeferred` refresh started by STARTED to RESUMED and checks one page-2 call before and two after, plus "2 of 3" and a working Next.
    - A failed page load sets `message` OFFLINE/FAILED, `pageFailed` keeps the condition false, so there is no retry loop while offline. The second new test checks exactly two page-2 calls and Next disabled. The user can still recover by leaving and reopening the last mail or by the list's Retry. This is a deliberate choice documented in the code comment, not a defect.
    - `canLoadAfter` also requires `search == null` and the open mail to be the last loaded one, so the collector stays idle for search results and after the list grows. The effect is cancelled when `atLoadedEnd` or `current` changes or the read view closes.
    - The `pageFailed` extension is shared with the list's own near-end loading in `Messages`, and the condition there is unchanged.
- CR-2 (missing tests for closing after removing the last mail, and for previous/next disabled while busy): resolved. `MailReadNavigationInAppTest` has `deleting the last mail of the mailbox returns to the list` and `deleting the last search result returns to the same search`. `MailReadNavigationScreenTest` has `the last mail archived closes the view instead of showing the previous one`, `the only mail of the list deleted closes the view` and `previous and next wait while an action runs, the position stays shown` (busy = true, both buttons not enabled, clicks do nothing).

Also reviewed in this pass: the new `MailReadNavigationTest`, `MailReadNavigationScreenTest` and `MailReadNavigationGermanTest` and the added in-app tests (spam shows the next mail, removal at the end). They assert user-visible behaviour (position text, enabled state, subject, list rows, repository call counts), use fictitious data and do not weaken existing tests. The previous review's checks of the production code from 5139d2ef0 and 39e14ab44 still hold, because only `MailScreen.kt` changed since then.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
