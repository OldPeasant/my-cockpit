# Code Review

Date: 2026-11-08

Task: and-review-flow - Android weekly review: start, steps, resume, reflection

Scope: `git diff 08204ca5b..HEAD`. That covers commits 17fe32542, d9c4d2786 and 495b5deca: production code under `android/app/src/main/**` (review package, `ui/CockpitShell.kt`, `strings_review.xml` en/de) and the tests committed with them. Untracked test files added later by the parallel test-writer (`ReviewFlowEdgeCasesTest.kt`, `ReviewRepositoryCallsTest.kt`) were not reviewed.

Requirements:
- tasks/and-review-flow.md acceptance criteria 1-7 and its recorded assumptions #1-#5
- SPEC.md §8.6 (weekly review) and §9.2 (Android)
- AGENTS.md "Project notes" (conventions, logging, i18n), docs/ARCHITECTURE.md
- Web reference implementation: web/src/app/features/review/review-session.ts, steps/calendar-step.ts, steps/reflection-step.ts

Git range:
- Branch: main
- HEAD: 495b5deca32bfa28d0038955e52deedb0046e83f
- Base/start: 08204ca5b1f38365c28c9331852303f1929569c3
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A slower overview load can overwrite a newer move or completion (out-of-order results)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:106` (reload), `:290` (save); trigger at `ReviewScreen.kt:79`
    - Category: Correctness
    - Problem: `ReviewRoute` calls `refresh()` every time the screen comes back into composition. One example is returning from the Waiting/Projects/Next/Someday list that steps 4-7 open on top of Review. `reload()` then replaces `overview` (including `current` with its step and `version`) with whatever the server answered. The overview load and `save()` (move, complete) run at the same time and share no request identity. `save()` does not cancel `loadJob`, and `reload()` does not check whether a save finished after the load started. Example: the user returns to the wizard and taps "Next step" right away. The move answers `version = n+1`. Then the older overview answer arrives with `version = n` and the previous step (`overview()` waits for both the status and the projects call, so it is easily the slower one). The wizard jumps back a step. The next move sends the stale `If-Match`, gets 412, and shows the misleading "changed on another device" CONFLICT banner. The same can happen after `complete()`: a stale load puts the completed review back as `current`, the overview offers "Continue review", and the wizard reopens on a review the server has already completed.
    - Why it matters: The UI shows a wrong step after a successful save and blames another device for a conflict that does not exist. This is the out-of-order async result defect the review checklist names explicitly. It is not covered by any test.
    - Suggested fix: Use one shared request identity. For example, cancel `loadJob` when a save starts, or keep a generation counter that `save()` bumps on success and `reload()` checks before it applies `current` (it can still apply `projects`/`daysSinceLastReview`). Add a view-model test where `overview()` suspends until after a successful `move`/`complete` and assert that the newer state is kept.

- [ ] **CR-2 | The reflection draft (and the open wizard) is lost on any bottom-tab switch, contrary to assumption #3**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:70` (draft kept only in `ReviewUiState.reflection`); navigation in `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:278-283`
    - Category: Correctness
    - Problem: Review is a `NavSection.More` destination. Switching to a bottom-bar tab (Inbox, Calendar, Mail, also through step 1's "Open Mail" link at `ReviewWizard.kt:258`) calls `navigateToTopLevel`. That pops the More stack, Review included (`saveState = true`). Going back through the More tab uses `restoreState = false` (`CockpitShell.kt:282`), so the user always gets a new More list and then a new Review back-stack entry with a new `ReviewViewModel`. The text typed in step 8 is gone, and `wizard` is reset, so the user lands on the overview instead of the step. Recorded assumption #3 says the draft "survives tab switches", which is not what the code does. The web keeps the draft per review id in localStorage, so a resumed review keeps it.
    - Why it matters: Text the user typed is lost without warning, for example after briefly checking the Calendar tab during the reflection. Humans reviewing the assumption are told the opposite.
    - Suggested fix: Keep the draft outside the back-stack entry, keyed by the review id. A `@Singleton` holder would survive tab switches, and DataStore/SharedPreferences would also survive app death like the web. Clear it on successful completion. If that is out of scope, at least correct assumption #3 with `tasks.py assume` so a human can decide.

## P3 Low

- [ ] **CR-3 | Quick-add can capture the same text twice**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:225`
    - Category: Correctness
    - Problem: `capture()` clears `captureText` only after the suspending `captures.capture(text)` (Room insert) returns, and nothing guards against a second call while one is running. Two calls in that window queue two identical inbox items: a double tap on "Add to inbox", or IME Done followed by the button (`ReviewWizard.kt:352` and `:356`). If the user keeps typing during the insert, the newer text is also cleared afterwards. The web disables the button while saving (`calendar-step.ts:50`, `:135`).
    - Why it matters: Duplicate inbox captures the user has to clean up. The window is short, so this is low impact.
    - Suggested fix: Add a `capturing` flag (return early while it is set, disable the button), or clear the field before the call and restore the text on failure.

- [ ] **CR-4 | A journal save committed on the server but reported as offline is appended twice on retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReflectionSaver.kt:44-52`; `ReviewViewModel.kt:180-186`
    - Category: Correctness
    - Problem: A read timeout or dropped connection after the server has committed the PUT comes back as `JournalSaveResult.Offline`, so `ReflectionOutcome.OFFLINE`. The view model keeps the reflection. When the user taps "Complete review" again, `save()` loads the entry, which now already ends with the section, and appends `## Weekly review` + text a second time. The guard against a second append (clearing `reflection` after SAVED) does not cover this case. The web has the same weakness, so this is not a regression against the reference.
    - Why it matters: The journal entry gets a duplicate reflection section. This needs a specific network failure and does not lose data.
    - Suggested fix: In `ReflectionSaver.save`, return `SAVED` without writing when `base?.bodyMd?.trimEnd()` already ends with the exact section that would be appended. Cover it with a `ReflectionSaverTest` case.

- [ ] **CR-5 | Step-track segments are below the minimum touch target**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewWizard.kt:203-209`
    - Category: Quality
    - Problem: Each of the 8 track segments is a raw `clickable` `Box` with `height(24.dp)` and a width of about (screen - 24dp) / 8 (about 42dp on a 360dp phone). That is below the 48x48dp minimum interactive size. Plain `clickable` does not apply `minimumInteractiveComponentSize()`. The step menu (`StepHeader`) offers full-size alternatives, so jumping stays accessible, but the track segments are hard to hit.
    - Why it matters: Accessibility and usability: taps miss or hit a neighbouring step, which then saves an unintended move on the server.
    - Suggested fix: Make the segment row at least 48dp tall (keep the visual bar thin inside it), or add `Modifier.minimumInteractiveComponentSize()`.

## Notes (verified, no finding)

- Criteria 1-6 are implemented. Start/continue uses the shared server session (`start()` continues locally when a review is open, otherwise POSTs `startWeeklyReview`). Next/previous/jump go through `move` with an ETag `If-Match`. Progress shows as "Step n of 8" plus the track. Step 1 embeds `InboxRoute` and links to Mail. Steps 2/3 list events grouped by local day, with quick-add through `CaptureQueue` (assumption #5). Step 8 shows the habit rate and streak and appends the reflection to today's journal entry before completing. Steps 4-7 open their lists, as recorded in assumption #2 and left to and-review-steps.
- Save guard: `goTo`, `complete` and `save` all return early while `saving`, and the buttons are disabled while saving. A conflict (404/409/412) reloads the review and closes the wizard when no review is open any more. This matches the web's `saveWith`.
- Reflection double append after a failed completion is prevented (the text is cleared after SAVED, which a test covers). `ReflectionSaver` keeps mood and tags through `JournalRepository.save` and retries once on a conflict against the current entry, like the web's `saveReflection`.
- The event day mapping (`toReviewEvent`) matches the web's `eventDay`: all-day events use `startDate`, timed events use their local start in the device zone. The zone handling is tested.
- i18n: all 49 new or changed keys and plurals exist in both `values/` and `values-de/`. The journal heading matches the web's `review.journalHeading` translations.
- No logging was added. HTTP failures carry only the status code, and no reflection or journal text reaches exceptions or logs.
- The one-line change to `ui/CockpitShell.kt`, outside the task's touches, is recorded as assumption #4.
- Not run by the reviewer: Gradle `ktlintCheck testDebugUnitTest lintDebug` and `scripts/check.sh`. `tasks.py verify and-review-flow` shows no criterion verified yet. The parent must record that evidence before `done`.

## Review conclusion

Material issues found; fix before completing the task.
