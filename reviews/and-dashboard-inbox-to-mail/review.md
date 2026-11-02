# Code Review

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Scope: final re-review after the CR-3 fix: `git diff a97a99a7d..472f9c23c -- android` (implementation e0d70f811,
tests 9d788c645, fix 58a429a48, CR-3 fix 472f9c23c). Reviewed revision: 472f9c23cbb23555e62a3fa2dd92a433d8dcc341.
`git diff 58a429a48..472f9c23c` changes in code only the KDoc of `destinationFor` (DashboardScreen.kt:116-117,
comment-only); the other commits in that range (a0c6abef7, feb4d19ea, 09388e291, e644244a0) touch only `reviews/` and
`tasks/`. Commit 199497166 after it touches only `reviews/`.

Requirements:
- tasks/and-dashboard-inbox-to-mail.md (acceptance criteria and the recorded CR-1 assumption)
- SPEC.md §8.7 (Dashboard)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- prior reviews (this file, revisions 9486c4e28 and 58a429a48), reviews/and-dashboard-inbox-to-mail/fixes.md and tests.md
- existing code: DashboardScreen.kt, DashboardViewModel.kt, DashboardRepository.kt, CockpitDestination.kt

Git range:
- Branch: main
- HEAD: 472f9c23cbb23555e62a3fa2dd92a433d8dcc341 (reviewed code; working HEAD 199497166 adds only the fix report)
- Base/start: a97a99a7d1382ba5d2e0d04563b41970fe977561
- Commits reviewed: 4 code commits (e0d70f811, 9d788c645, 58a429a48, 472f9c23c)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification of the acceptance criteria against the code:
- Criterion 1: `WidgetId.destinationFor(data)` (DashboardScreen.kt:120-125) returns `CockpitDestination.Mail` only
  for the INBOX widget with `WidgetData.Inbox` data, `tasks.count == 0` and `unreadMail.count > 0`; everything else
  (tasks present, no unread mail, `null` data while not loaded or after a failed first load) returns `destination`
  (GTD Inbox). `DashboardContent` passes the composed `WidgetState.data` (DashboardScreen.kt:170). A failed
  refresh that keeps earlier data follows that data; this reading is recorded as an assumption in the task file
  (prior CR-1), so it is a documented decision, not a defect. Met.
- Criterion 2: the card icon still comes from `widget.destination.icon` (DashboardScreen.kt:185), independent of data;
  the added `this == WidgetId.INBOX` guard (fix for TW-1) makes the redirect impossible for any other widget. Met.
- Criterion 3: `DashboardDestinationTest` (pure unit) covers mail-only, exactly one unread, no unread, tasks present
  (with and without mail, "N+" counts), null/not-loaded/failed-without-data, stale data after FAILED/OFFLINE refresh,
  other widgets' destinations loaded and unloaded, the INBOX-only guard, and the icon. `DashboardInboxCardTest`
  (Robolectric Compose) taps the real card for the same cases, including the tap following changed data. Tests
  assert behavior through `onOpen`, not implementation details. I ran
  `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`
  -> exit 0 on 58a429a48. The code logic of 472f9c23c is identical (comment-only change), so that run still applies;
  per the parent's instruction I did not run Gradle again in this re-review (the test-writer runs the Android build
  next). `lintDebug` and the full `testDebugUnitTest` were not run by me; the parent records them.

Prior findings: CR-1 closed by the recorded assumption (and now pinned by tests); CR-2 fixed (KDoc of
`destination` reworded, DashboardScreen.kt:101); CR-3 fixed in 472f9c23c: the KDoc of `destinationFor`
(DashboardScreen.kt:116-117) now says data is null until the card's first successful load and that a reload or failed
refresh keeps the last data, which matches `DashboardViewModel.refresh` (`copy(loading = true)`) and `load`
(`before.copy(...)` on OFFLINE/FAILED) and the recorded assumption. No architecture, integration or security concerns: the change stays in
the dashboard feature folder within the task's `touches`, uses the existing `CockpitDestination.Mail` and `onOpen`,
adds no dependency and no API change.

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
