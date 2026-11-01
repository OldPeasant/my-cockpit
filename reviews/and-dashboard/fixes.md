# Fix Report

Date: 2026-11-01

Task: and-dashboard (Android dashboard screen)

Scope: current task; the two P3 findings CR-1 and CR-2 in `reviews/and-dashboard/review.md`

## Fixed findings

### CR-1 — Upcoming event dates use a fixed, non-localized pattern

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardScreenTest.kt`

Problem:
`Events()` formatted the day with `DateTimeFormatter.ofPattern("EEE d MMM")`. That pattern fixes the English field order and punctuation and uses the JVM default locale without saying so.

Fix:
The pattern now comes from `android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`, with the locale from `LocalConfiguration.current.locales[0]` (the same source `CalendarScreen` uses). The formatter is created with that locale. The screen test that checked the old fixed pattern now builds its expected prefix the same locale-aware way. Its assertion is unchanged: five rows with the day and time prefix.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` — Pass

### CR-2 — `DashboardEvent.key` is computed but never used by the UI

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
- `cd android && ./gradlew ktlintFormat` — Pass (no further changes)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*' lintDebug` — BUILD SUCCESSFUL. The 6 dashboard test classes ran 55 tests with 0 failures, 0 errors and 0 skipped.

Full test suite:
- Not run (the parent asked for the dashboard-scoped run; the parent runs `.taskcheck`)

Lint/type-check/build:
- ktlintCheck and lintDebug — Pass

`.taskcheck`:
- Not run (the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
