# Fix Report

Date: 2026-11-08

Task: and-review-steps - Android weekly review: waiting, projects, next, someday steps

Scope: findings CR-2, CR-3, CR-4, CR-5 of `reviews/and-review-steps/review.md` (CR-1 is handled by the test-writer)

## Fixed findings

### CR-2 — Reloading a step after a conflict undoes earlier "keep" decisions

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDecisionsViewModelTest.kt`

Problem:
Keep decisions (and follow-up) removed the item only locally. A reload of the step from `failed()` (404/409/412), or a load that was still running when the keep was tapped, brought the kept items back.

Fix:
`decidedDuringLoad` is now `decided`, a set of decided ids for each step. It holds every decision taken since the step was last shown, including keeps (`decide` with `action == null`). The load reads the set when its result arrives, so items decided while the load ran are filtered out too. Public `loadStep` (the step is shown, or a retry) clears the set, like web's `decided` reset on load. `failed()` now calls a private `reloadStep`, which keeps both the set and the last decision. The test that asserted the old behavior (a kept item coming back after a 404/409/412 reload) now expects an empty list. New tests: a keep during a running load stays out of the result, and showing the step again offers the kept items again.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

### CR-3 — A late decision is announced in the next step

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDecisionsViewModelTest.kt`

Problem:
`removeItem` always wrote `decision`, even after the user had moved on to another step.

Fix:
`loadStep` records the step it shows in `shownStep`. `removeItem` and the `addNextStep` success path set `decision` only when the decision's step is `shownStep`. The item still leaves its own list. I used `shownStep` instead of `current.step` because the wizard calls `loadStep` every time a step is shown, and because the view-model decision tests run without an open review. New test: a completion that finishes after `loadStep(PROJECTS)` leaves `decision` null.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

### CR-4 — `addNextStep` partial failure says "try again"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewScreen.kt`
- `android/app/src/main/res/values/strings_review.xml`
- `android/app/src/main/res/values-de/strings_review.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDecisionsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionStepsTest.kt`

Problem:
When the task was created but setting it as the project's next step failed, the banner showed the generic "refused / offline, try again" message. Trying again would create a duplicate next action.

Fix:
New `ReviewMessage.NEXT_STEP_NOT_SET`, mapped in the banner to the new string `review_next_step_not_set`. English: "The next action was added to the project, but not set as its next step. Open the project to set it." German: "Die nächste Aktion wurde zum Projekt hinzugefügt, aber nicht als nächster Schritt gesetzt. Öffne das Projekt, um ihn zu setzen." When `outcome.taskCreated` is true and the result failed, this message replaces the one from `failed()`. A conflict still reloads the projects step. The two partial-failure tests (offline, 412) now expect the new message. The banner test checks the English text, and the translation key list includes the new key.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

### CR-5 — Status button hides the current status; project title touch target is small

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewActionSteps.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionStepsTest.kt`

Problem:
The status button's `contentDescription` replaced the visible status for TalkBack. The clickable project title was about 32 dp high and had no role.

Fix:
`StatusChoice` now also sets `stateDescription` to the current status name. The title's `clickable` gets `role = Role.Button` and `heightIn(min = 48.dp)`, with the text centered vertically (`wrapContentHeight`) in place of the 4 dp padding. The Compose tests now check the state description ("Active"), the Button role and a height of at least 48 dp.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

## Not fixed

### CR-1 — New decision behavior has no tests

Status:
Out of scope

Reason:
The parent worker assigned this to the test-writer. I added only focused regression tests for CR-2 to CR-5 and the minimal edits the fixes required in the untracked test files from the interrupted test-writer run.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL

Lint/type-check/build:
- Included above (ktlintCheck, lintDebug) — Pass

`.taskcheck`:
- `sh scripts/check.sh` — see the result reported to the parent worker (it was still running on shared Gradle daemons when this report was written)

Remaining unresolved findings:
1 (CR-1, assigned to the test-writer)

## Final status

All validated findings fixed and validation passed.
