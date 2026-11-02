# Code Review

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Scope: production change of commit 9486c4e28 (`git diff a97a99a7d..9486c4e28 -- android`); tests are being added in parallel by the test-writer and are not part of this review

Requirements:
- tasks/and-dashboard-inbox-to-mail.md (acceptance criteria)
- SPEC.md §8.7 (Dashboard)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- existing code: DashboardScreen.kt, DashboardViewModel.kt, DashboardRepository.kt, CockpitDestination.kt, DashboardScreenTest.kt

Git range:
- Branch: main
- HEAD: 9486c4e28b014e14570cce7902bd5466c4c59187
- Base/start: a97a99a7d1382ba5d2e0d04563b41970fe977561
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Verification of the acceptance criteria against the code:
- Criterion 1: `WidgetId.destinationFor(data)` (DashboardScreen.kt:119-124) returns `CockpitDestination.Mail` only for
  `WidgetData.Inbox` with `tasks.count == 0` and `unreadMail.count > 0`; `null` data (not loaded yet, or first load
  failed), tasks present, or no unread mail fall back to `destination` (GTD Inbox). `Count.count` is the number of
  items on the first page, so "0+" tasks cannot occur (count 0 implies no more). The card's `onClick` reads the
  `WidgetState` of the current composition (DashboardScreen.kt:168-169), so it follows the shown data. Met.
- Criterion 2: the icon still comes from `widget.destination.icon` (DashboardScreen.kt:184), so it stays the Inbox
  icon; `destinationFor` returns `destination` unchanged for every other widget, as `data is WidgetData.Inbox` only
  holds for the INBOX widget. Met.
- Criterion 3: tests are owned by the parallel test-writer; the existing `each card opens the matching screen` test
  uses Inbox data with 4 tasks and still expects `Inbox`, which the change keeps. Not assessed here beyond that.

No architecture, integration or security concerns: the change is a pure UI routing decision inside the dashboard
feature folder, within the task's `touches`; it uses the existing `CockpitDestination.Mail` and the shell's
`onOpen`, no new dependencies, no API change.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Stale data after a failed reload decides the target; record this reading of "failed"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:169`
    - Category: Correctness
    - Problem: `DashboardViewModel.load` keeps the previous `data` when a later load fails or while a reload runs
      (`before.copy(loading = false, error = ...)`, DashboardViewModel.kt:76-82). The routing uses `widgetState.data`
      regardless of `error`, so a card that showed "0 tasks, N unread" and then failed to refresh still opens Mail.
      The criterion lists "failed" among the cases that open the GTD Inbox, but also says "its loaded data shows",
      and the card keeps showing that data. Both readings are defensible; the code picks the "what the card shows"
      one without saying so.
    - Why it matters: a human reading the criterion may expect a card with an error line to open the Inbox; the
      choice is not documented and not pinned by a test.
    - Suggested fix: keep the behavior (it matches what the card displays) and record it with
      `tasks.py assume`, and have a test cover data + error (stale) state; or, if the strict reading is wanted,
      check `widgetState.error == null` as well.

- [ ] **CR-2 | KDoc of `WidgetId.destination` now overstates its role**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:101`
    - Category: Quality
    - Problem: the comment still reads "The screen a widget's card opens.", but the card now opens
      `destinationFor(data)`; `destination` is the default target and the source of the card's icon.
    - Why it matters: a later change might call `destination` for navigation again and silently lose the Mail case.
    - Suggested fix: reword to e.g. "The widget's own screen: its icon, and what its card opens by default
      (see [destinationFor])."

## Review conclusion

No material issues found.
