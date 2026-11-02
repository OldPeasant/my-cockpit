# Code Review

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Scope: final re-review after fixes: `git diff a97a99a7d..58a429a48 -- android` (implementation e0d70f811, tests
9d788c645, fix 58a429a48). Reviewed revision: 58a429a486014d13c8b741b98df40e6f2860dec6. Commits after it up to
a0c6abef7 touch only `reviews/`.

Requirements:
- tasks/and-dashboard-inbox-to-mail.md (acceptance criteria and the recorded CR-1 assumption)
- SPEC.md §8.7 (Dashboard)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- prior review (this file, revision 9486c4e28), reviews/and-dashboard-inbox-to-mail/fixes.md and tests.md
- existing code: DashboardScreen.kt, DashboardViewModel.kt, DashboardRepository.kt, CockpitDestination.kt

Git range:
- Branch: main
- HEAD: 58a429a486014d13c8b741b98df40e6f2860dec6 (reviewed code; working HEAD a0c6abef7 adds only the fix report)
- Base/start: a97a99a7d1382ba5d2e0d04563b41970fe977561
- Commits reviewed: 3 (e0d70f811, 9d788c645, 58a429a48)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification of the acceptance criteria against the code:
- Criterion 1: `WidgetId.destinationFor(data)` (DashboardScreen.kt:119-124) returns `CockpitDestination.Mail` only
  for the INBOX widget with `WidgetData.Inbox` data, `tasks.count == 0` and `unreadMail.count > 0`; everything else
  (tasks present, no unread mail, `null` data while not loaded or after a failed first load) returns `destination`
  (GTD Inbox). `DashboardContent` passes the composed `WidgetState.data` (DashboardScreen.kt:168-169). A failed
  refresh that keeps earlier data follows that data; this reading is recorded as an assumption in the task file
  (prior CR-1), so it is a documented decision, not a defect. Met.
- Criterion 2: the card icon still comes from `widget.destination.icon` (DashboardScreen.kt:184), independent of data;
  the added `this == WidgetId.INBOX` guard (fix for TW-1) makes the redirect impossible for any other widget. Met.
- Criterion 3: `DashboardDestinationTest` (pure unit) covers mail-only, exactly one unread, no unread, tasks present
  (with and without mail, "N+" counts), null/not-loaded/failed-without-data, stale data after FAILED/OFFLINE refresh,
  other widgets' destinations loaded and unloaded, the INBOX-only guard, and the icon. `DashboardInboxCardTest`
  (Robolectric Compose) taps the real card for the same cases, including the tap following changed data. Tests
  assert behavior through `onOpen`, not implementation details. I ran
  `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`
  -> exit 0. `lintDebug` and the full `testDebugUnitTest` were not run by me; the parent records them.

Prior findings: CR-1 closed by the recorded assumption (and now pinned by tests); CR-2 fixed (KDoc of
`destination` reworded, DashboardScreen.kt:101). No architecture, integration or security concerns: the change stays in
the dashboard feature folder within the task's `touches`, uses the existing `CockpitDestination.Mail` and `onOpen`,
adds no dependency and no API change.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | KDoc of `destinationFor` says data is null "while loading or after a failure"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:116`
    - Category: Quality
    - Problem: `DashboardViewModel.load` keeps the previous data during a reload (`copy(loading = true)`,
      DashboardViewModel.kt:63) and after a failed or offline refresh (`before.copy(...)`, DashboardViewModel.kt:80-81).
      Data is null only before the first successful load. The comment suggests the opposite of the recorded
      assumption that a failed refresh follows the kept data.
    - Why it matters: a later reader may "fix" the code to match the comment and silently change the decided behavior.
    - Suggested fix: reword to e.g. "(null until its first successful load; a failed refresh keeps the last data)".

## Review conclusion

No material issues found.
