# Fix Report

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: The two P3 findings CR-1 and CR-2 in the current `reviews/cal-ics/review.md` (review of the final code, "P3 Low" section). Base commit ebe36ae2d85364068ad6e8a3a844c91396ee30e1, task commits ebe36ae2..HEAD (HEAD 176a8e3f before this round).

Earlier round: a previous fix round (fix commit 5dbc71b2, report commit 22bc4d64) resolved the earlier review's CR-1, CR-2, CR-4 and CR-5 and the nominal-days DURATION test. That report is replaced by this one; see the git history for its content.

## Fixed findings

### CR-1 - A test comment still quotes the old per-request timeout contract

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClientTests.kt`

Problem:
The trickle test quoted `IcsFeedProperties.timeout` as "Time for one request, from sending it to the end of the body.", which contradicts the current whole-fetch KDoc.

Fix:
The comment now quotes the current KDoc: "Time for a whole fetch: from sending the first request, through any redirects, to the end of the last body."

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass
- `./gradlew ktlintCheck` - Pass

### CR-2 - `timeout` and `connect-timeout` are not validated like the other properties

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedProperties.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClientTests.kt`

Problem:
A zero or negative `cockpit.calendar.ics.timeout` made every fetch fail with a misleading "did not answer in time"; a zero or negative `connect-timeout` failed bean creation with an exception that did not name the property.

Fix:
The `init` block now has `require(timeout.isPositive) { "cockpit.calendar.ics.timeout must be positive" }` and `require(connectTimeout.isPositive) { "cockpit.calendar.ics.connect-timeout must be positive" }`. The existing properties test (renamed to "... a non-positive poll interval or timeout") now checks zero and negative values of both, including the exact messages, and that a small positive timeout is accepted.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass (IcsFeedClientTests 42, IcsFeedAdapterTests 13, IcsParserTests 16, IcsEventMappingTests 38, IcsFeedSyncTests 11; 0 failures)
- `./gradlew ktlintCheck` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - no changes needed beyond the edits
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' ktlintCheck` - BUILD SUCCESSFUL, 120 tests, 0 failures

Full test suite:
- Not run by the fixer (the change is limited to the ICS properties class and its test; the parent runs `.taskcheck`).

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass

`.taskcheck`:
- Not run (left to the parent worker as part of final validation).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
