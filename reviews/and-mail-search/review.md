# Code Review

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: second pass, `git diff 4f351d00b af2f5113a -- android` (final code after the fixes and the parent's replacement of the scroll-to-top logic)

Requirements:
- tasks/and-mail-search.md acceptance criteria and recorded assumptions
- api/openapi.yaml `GET /api/v1/mail/search` and its response schemas
- AGENTS.md project notes, existing Android mail list conventions (MailListViewModel, MailScreen, MailRepository)
- First-pass report (CR-1, CR-2, CR-3) and reviews/and-mail-search/fixes.md

Git range:
- Branch: main
- HEAD: af2f5113a
- Base/start: 4f351d00b
- Commits reviewed: 2 code commits (7cc2dddb0 implementation, af2f5113a fixes); the other commits in the range touch only `tasks/`

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of earlier findings

- CR-1 (scroll position after the read view): resolved. `MailRoute` hoists `searchState` (MailScreen.kt:118) next to `listState` and passes it to `MailSearchContent`/`Results`. That keeps the place while `MailListContent` is out of composition. The scroll-to-top collector (MailScreen.kt:322-328) lives in `MailRoute`, so it stays active while the read view is open. It drops the value seen on (re)composition, so going back from a result or a configuration change keeps the place. `MailSearchState.version` is set to the search generation only on an `Ok` answer (MailListViewModel.kt:204). Generations increase monotonically and `closeSearch` takes one too, so every answer has a distinct version, including the first answer of a reopened search. StateFlow conflation cannot hide an answer. `markSeen`, `removed`, a search starting, and a failure all leave the version unchanged, so none of them scroll. `requestScrollToItem` drops the last known first-item key for the next measure. That fixes the case where the server answer prepends newer rows ([5] then [6, 5]) and key-based positioning would keep row 5 at the top. The route test `search on server asks the servers ...` (waits for row 6) covers that case. `MailSearchViewModelEdgeCasesTest` covers the version rules (lines 274-350).
- CR-2 (server search with an edited box): resolved. The box text is lifted into `MailSearchContent`. The server button sends that text and is disabled while it is blank. `searchOnServer(query)` trims and caps it. For the shown query it keeps the cached results; an edited query starts from an empty server state. The `canSearchServer` guard is still applied. Screen and ViewModel tests cover edited, blank and same-text cases.
- CR-3 (system Back untested): already covered. `MailSearchScreenEdgeCasesTest` `the system back closes an open search` uses the composition's `OnBackPressedDispatcher`. The route test closes search with the activity's back dispatcher.
- The swipe test now ignores `open` calls but still asserts that no archive or delete happened and that no custom accessibility actions exist. That is not a weakening, because rows open on a tap by design.

## Other checks without findings

- Out-of-order results: `searchGeneration` together with job cancellation still blocks stale answers, including failures and answers that arrive after close. An answer that arrives while the read view is open still applies `requestScrollToItem` when the list comes back. That is intended, because the results changed.
- Returning from the read view: the box text is rebuilt from `search.query`, so the box always shows the query of the listed results.
- API contract, loading/empty/error states, strings in `values` and `values-de`, and the absence of secret logging or unsafe input are unchanged since the first pass, which verified them.
- Note for the parent's evidence: the reported run was `ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`. Criterion 5 also names the full `testDebugUnitTest` and `lintDebug`, which must be run on this revision before the criterion is recorded.

## Review conclusion

No material issues found.
