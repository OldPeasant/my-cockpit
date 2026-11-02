# Fix Report

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Scope: current task; findings approved by the parent worker: CR-2 (review.md) and TW-1 (tests.md observation). CR-1 excluded by parent decision.

## Fixed findings

### CR-2 - KDoc of `WidgetId.destination` overstates its role

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`

Problem:
The KDoc said `destination` is "The screen a widget's card opens", but the card now opens `destinationFor(data)`.

Fix:
Reworded to: "The widget's own screen: the source of its card's icon, and what its card opens by default (see [destinationFor])."

Validation:
- `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass

### TW-1 - `destinationFor` does not check that the receiver is the Inbox widget

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardDestinationTest.kt`

Problem:
Any widget given `WidgetData.Inbox` data with 0 tasks and unread mail would have returned Mail; only the data flow prevented it.

Fix:
Added `this == WidgetId.INBOX` to the redirect condition. Added regression test `only the Inbox card redirects to Mail, even if another card got Inbox data`, which passes mail-only Inbox data to every other widget and expects its own `destination`.

Validation:
- `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass (exit 0; 80 dashboard tests in 9 classes, 0 failures, 0 errors, 0 skipped; new test included)

## Not fixed

### CR-1 - Inbox card follows stale data after a failed refresh

Status:
Requires decision (decided by the parent worker: not to be fixed)

Reason:
The parent decided the card follows the data it shows; recorded as an assumption in `tasks/and-dashboard-inbox-to-mail.md`. No code change.

## Validation summary

Focused tests:
- `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` - Pass

Full test suite:
- Not run by the fixer (focused run as instructed by the parent; the parent runs `.taskcheck` and `lintDebug`).

Lint/type-check/build:
- `ktlintCheck` - Pass (in the command above); compilation of main and test sources - Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 (CR-1 closed by parent decision)

## Final status

All validated findings fixed and validation passed.

Code fix commit: 58a429a48
