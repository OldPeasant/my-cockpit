# Code Review

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: third pass, `git diff 4f351d00b 9535590b -- android` (whole task diff). The focus is the change since the previous review revision, `git diff af2f5113a 9535590b -- android`.

Requirements:
- tasks/and-mail-search.md acceptance criteria and recorded assumptions
- AGENTS.md project notes, existing Android mail list conventions (MailListViewModel, MailScreen)
- Previous review of revision af2f5113a (no findings)

Git range:
- Branch: main
- HEAD: 9535590b17461d540e8af6eb4828070b5f416d4b
- Base/start: 4f351d00b
- Commits reviewed: 3 code commits (7cc2dddb0 implementation, af2f5113a fixes, 9535590b version handling and tests). The other commits in the range touch only `tasks/`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Change since the previous review

- Production change: in MailListViewModel.kt:230 (`search`) and MailListViewModel.kt:251 (`searchOnServer` with an edited query), the fresh `MailSearchState` now carries over `search.version` instead of the default 0. `retrySearch` and `searchOnServer` for the shown query already used `copy` and kept the version.
- Effect on the scroll collector (MailScreen.kt:123-129, `map { search?.version }.distinctUntilChanged().drop(1)`): before this change, starting a new search after an answer emitted 0 and scrolled to the top while loading, then scrolled again on the answer. Now only the answer scrolls, because `version` changes only on an `Ok` answer (`version = generation`, MailListViewModel.kt:290). That is the documented intent ("each answer replacing the results starts at the top").
- No regression found:
  - `searchGeneration` increases monotonically and `closeSearch` takes one too. Every `Ok` answer therefore gets a version that differs from every earlier one, including the version carried over from a previous search and the first answer after a reopened search, which starts at 0 again through `openSearch`.
  - Failures (`Offline`, `Failed`) and stale or cancelled answers leave the version unchanged, as before.
  - An edited query clears the results while loading. The list keeps its old offset over an empty list until the answer arrives, and the answer's new version then calls `requestScrollToItem(0)`. A failed edited search shows the error state over empty results, so the stale offset has no visible effect.
- Tests: `MailSearchViewModelEdgeCasesTest` adds two tests:
  - the version is unchanged when a search starts, fails, or a result is read or removed, and it changes with each answer, including a repeated identical query and a successful retry;
  - a late answer of an older search does not change the version, and a reopened search gets a new non-zero version.

  `MailSearchRouteInAppTest` adds a route test: a failed server search keeps the scroll place, and the successful retry shows the newer first row. These tests assert behavior through public state and UI tags, not internals.

## Other checks without findings

- The rest of the task diff matches the revision reviewed before, and its earlier conclusions still apply: API use of `GET /api/v1/mail/search`, loading, empty and error states, the failure and hasMore/truncated hints, strings in `values` and `values-de`, out-of-order protection, and no logging of secrets or mail content.
- Note for the parent's evidence: the run reported on this revision was `ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`. Criterion 5 names the full `ktlintCheck testDebugUnitTest lintDebug`. Record that run's result once it finishes, before ticking the criterion.

## Review conclusion

No material issues found.
