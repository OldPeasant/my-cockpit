# Fix Report

Date: 2026-11-14

Task: and-mail-live-refresh (Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume)

Scope: current task, Android only, within the task's touches. Findings TW-1 (tests.md), CR-2 and CR-3 (review.md).

## Fixed findings

### TW-1 — Dashboard mail events reloaded the Today widget instead of the unread-mail count

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt`

Problem:
`followMailChanges()` reloaded `WidgetId.TODAY`, but the unread-mail count is `WidgetData.Inbox.unreadMail`, loaded by
`DashboardRepository.inbox()` for `WidgetId.INBOX`. The count never updated on a mail event.

Fix:
The event reload (new private `reloadInbox()`) now checks that `WidgetId.INBOX` is shown and reloads only that widget;
KDoc updated to say "Inbox widget".

Validation:
- `DashboardMailRefreshTest` "an email event updates the unread mail count" and "a failed event reload keeps the
  unread mail count and shows no error" — Pass (failed before the fix)

### CR-2 — A quiet reload could be orphaned by loadMore/loadOlder and overwrite a newer refresh or mailbox

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewMailLiveRefreshTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt`

Problem:
First-page loads (manual and quiet) and paging (loadMore/loadOlder) shared `loadJob`. Paging could start during a
quiet reload and replace `loadJob`, so a later `refresh()`/`select()` no longer cancelled the quiet reload, whose
answer could then end a pull-to-refresh spinner early (New) or write the previous mailbox's mails and cursor into a
newly selected mailbox (Mail list).

Fix:
First-page loads now run in their own `firstPageJob`; `loadJob` holds only paging. Every first-page load cancels the
running `firstPageJob`; a manual load (refresh/select/action reload) also cancels paging as before, while a quiet one
leaves paging running (so `loadingMore`/`loadingOlder` are not left stuck). In the Mail list, the first-page answer is
also dropped when `current.mailbox` no longer equals the mailbox it loaded (the load body moved into a private
`loadFirstPage(mailbox, message, quiet)`, unchanged otherwise).

Validation:
- `NewMailLiveRefreshTest.aRefreshAfterLoadMoreDropsTheAnswerOfAnEarlierEventReload` — Pass (new regression test)
- `MailListLiveRefreshTest` "an event reload outrun by load more and a folder switch does not overwrite the new
  folder" — Pass (new regression test)

### CR-3 — A mail event arriving while any load ran was dropped

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt`
- the three test files named above plus `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardMailRefreshTest.kt`

Problem:
The guard `loadJob?.isActive != true` also skipped events during loadMore/loadOlder, and an event during a first-page
load (which may have read the server before the mail was stored) was forgotten.

Fix:
New and Mail list: an event while `firstPageJob` runs sets `reloadPending`; the running first-page job loops once more
quietly (for the Mail list on the mailbox shown then) as long as the flag was set meanwhile. An event during paging
starts the quiet first-page reload at once. Dashboard: an event during a refresh or an event reload sets `mailPending`;
the event reload loops while it is set, and a refresh ends by reloading the Inbox widget if it is set (a refresh start
clears it). Coalescing is kept: never two first-page loads (or two Inbox reloads) at the same time, and a burst during
a load gives exactly one follow-up reload.

Validation:
- `NewMailLiveRefreshTest.anEventDuringALoadMoreStillReloadsTheNewestMails` — Pass (new)
- `MailListLiveRefreshTest` "an event during load more still reloads the first page" — Pass (new)
- `DashboardMailRefreshTest` "an event during an event reload reloads the count once more afterwards" — Pass (new)
- Changed tests, because the behavior intentionally changed from "dropped" to "deferred":
  `NewMailLiveRefreshTest.anEventDuringARunningLoadDoesNotStartASecondOneButReloadsOnceItEnds`,
  `MailListLiveRefreshTest` "an event during a running load does not start a second one but reloads once it ends",
  `DashboardMailRefreshTest` "an event while the dashboard loads starts no second load but reloads the count once it
  ends". They still assert no second concurrent load while the first runs (two events during it), and now also that
  exactly one follow-up reload runs after it and nothing more later — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat` then `./gradlew ktlintCheck` — Pass
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.dashboard.*' --tests 'ch.sonensei.cockpit.gtd.data.*'`
  — Pass, BUILD SUCCESSFUL, 77 suites, 0 failures, 0 errors (NewMailLiveRefreshTest 14, MailListLiveRefreshTest 15,
  DashboardMailRefreshTest 7, NewViewModelTest 44, DashboardViewModelTest 9, MailListViewModelTest 6,
  MailListViewModelEdgeCasesTest 20)

Full test suite:
- Not run by the fixer (whole Android unit suite runs in `.taskcheck`; left to the parent)

Lint/type-check/build:
- ktlintCheck Pass; Kotlin compile of main and test sources Pass (part of the run above); lintDebug not run

`.taskcheck`:
- Not run (parent runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

Notes for the parent:
- Two recorded assumptions no longer match the code: "a signal arriving while any load (incl. load more) runs is
  dropped rather than queued" (now: an event during paging reloads at once; an event during a first-page load or
  event reload is deferred to one follow-up reload), and "the Today dashboard reloads only its Today widget" (now: only
  the Inbox widget, which holds the unread-mail count).
- The code changed after the review, so test-writer and code-reviewer evidence is stale.

## Final status

All validated findings fixed and validation passed.
