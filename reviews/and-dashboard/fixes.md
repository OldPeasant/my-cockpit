# Fix Report

Date: 2026-11-01

Task: and-dashboard (Android dashboard screen)

Scope: current task. This round: the P2 finding CR-1 of the review of the final code in `reviews/and-dashboard/review.md` (start screen does not reload on return to the foreground). Earlier round: the two P3 findings of the first review, kept below as Earlier CR-1 and Earlier CR-2.

## Fixed findings

### CR-1 — The start screen does not reload when the app returns to the foreground

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardInAppTest.kt`

Problem:
`DashboardRoute` reloaded with `LaunchedEffect(Unit) { viewModel.refresh() }`, which runs only when the Dashboard enters composition. When the app came back from the background the NavHost composition survived, so the start screen kept old data (for example yesterday's Today card) until a manual pull.

Fix:
`DashboardRoute` now uses the project convention from `CalendarRoute` (`calendar/agenda/CalendarScreen.kt`): `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }`. The first resume is the first load, so the first showing still loads once; returning from another tab (new composition) or from a screen opened on top (back stack entry resumed again) still reloads once; and a pause/resume of the activity now reloads too. `refresh()` already cancels a running load, so a resume during a load is safe. The `LaunchedEffect` import stays because the scroll-to-top effect still uses it.

New test in `DashboardInAppTest`: `the dashboard loads again when the app comes back to it` launches the real Hilt activity, waits for the first load to finish (layout and each widget loaded once), moves the activity to `Lifecycle.State.STARTED` (no reload yet) and back to `RESUMED` (as `ReminderUiTest` does with `scenario.moveToState`), then checks that the layout and the Inbox, Today and Weekly Review widgets were loaded exactly twice. The existing counting tests still hold by reasoning: the app start resumes the Dashboard entry once (`exactly = 1` layout call), pull to refresh adds one (`exactly = 2`), and the tab round trip adds one resume of the newly composed Dashboard (`exactly = 2`). `DashboardScreenTest` renders `DashboardContent` only and is not affected.

Validation:
- Not run by the fixer: Gradle cannot run in this sub-agent environment. The parent worker must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` and `.taskcheck`.

### Earlier CR-1 — Upcoming event dates use a fixed, non-localized pattern

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardScreenTest.kt`

Problem:
`Events()` formatted the day with `DateTimeFormatter.ofPattern("EEE d MMM")`. That pattern fixes the English field order and punctuation and uses the JVM default locale without saying so.

Fix:
The pattern now comes from `android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`, with the locale from `LocalConfiguration.current.locales[0]` (the same source `CalendarScreen` uses). The formatter is created with that locale. The screen test that checked the old fixed pattern now builds its expected prefix the same locale-aware way. Its assertion is unchanged: five rows with the day and time prefix.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` — Pass

### Earlier CR-2 — `DashboardEvent.key` is computed but never used by the UI

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`

Problem:
The `DashboardEvent.key` field (`eventId:occurrence`) was only read by a repository test. The UI rendered event rows without a key.

Fix:
`Events()` now wraps each row in Compose `key(event.key) { ... }`, so the field gives each event row its identity. This needed fewer changes than removing the field, which is built positionally in several tests. The model and the repository test are unchanged.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- This round (CR-1): not run. Gradle cannot run in the fixer environment; the code was written to follow `CalendarRoute` and the `moveToState` pattern of `ReminderUiTest`. Parent to run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug`.
- Earlier round: `cd android && ./gradlew ktlintFormat` — Pass (no further changes)
- Earlier round: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` — BUILD SUCCESSFUL. The 6 dashboard test classes ran 55 tests with 0 failures, 0 errors and 0 skipped.

Full test suite:
- Not run (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- This round: not run (Gradle not available to the fixer). Earlier round: ktlintCheck and lintDebug — Pass

`.taskcheck`:
- Not run (the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed. Validation of the CR-1 fix is pending: the parent worker must run the dashboard Gradle tests, lint and `.taskcheck`.
