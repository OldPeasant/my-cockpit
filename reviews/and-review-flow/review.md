# Code Review

Date: 2026-11-08

Task: and-review-flow - Android weekly review: start, steps, resume, reflection

Scope: `git diff fdd5513d678901dc58963738a9d1e5e05ab2166f..HEAD`, the whole task: commits 76b39ca6f, 26134ab8d, 7f3729b5d, 66ab0159d and 72df82ee6, plus the two `assume` commits. This is a second review of the final code. It checks the fixes for the earlier findings CR-1 to CR-5 and the Mail back-navigation fix (fixes.md "TEST-1"), and looks for new issues in the `ReviewDrafts` singleton, the `saves` generation counter in `ReviewViewModel` and the `CockpitShell` change. No uncommitted changes were present.

Requirements:
- tasks/and-review-flow.md acceptance criteria 1-7 and its recorded assumptions #1-#7 (`python tasks.py assumptions`)
- SPEC.md §8.6 (weekly review) and §9.2 (Android), docs/PARITY.md decision 6 (back navigation)
- AGENTS.md "Project notes" (conventions, logging, i18n, touches), docs/ARCHITECTURE.md, docs/QUALITY.md
- Previous review `reviews/and-review-flow/review.md` (revision 495b5deca) and `reviews/and-review-flow/fixes.md`
- Web reference: web/src/app/features/review/review-session.ts, steps/calendar-step.ts, steps/reflection-step.ts

Git range:
- Branch: main
- HEAD: 72df82ee649f1f9f8cab75b852e9acb89f152bf7
- Base/start: fdd5513d678901dc58963738a9d1e5e05ab2166f
- Commits reviewed: 7 (5 code/test commits, 2 assumption commits)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Reflection text typed while "Complete review" saves the journal entry is erased**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:217-224`; field at `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewWizard.kt:447-453`
    - Category: Correctness
    - Problem: `complete()` reads `state.value.reflection` once, saves it through `ReflectionSaver` (a journal GET and PUT over the network), and on `SAVED` sets both `drafts.setReflection(review.id, "")` and `reflection = ""` without checking the current value. The reflection `OutlinedTextField` stays enabled while `saving` is set, so anything the user types or changes during that round trip is cleared without being saved. If `reviews.complete` then fails (offline, 5xx), the wizard stays open on step 8 with an empty field, and the extra text is gone. The fixer already applied the matching guard to the quick-add field (`ReviewViewModel.kt:266` clears only if the field still holds the captured text), but not here.
    - Why it matters: Lost user text is possible, but only when the user edits during a short save window. That keeps the impact low.
    - Suggested fix: Clear only if the field still holds the saved text (`if (it.reflection.trim() == text) "" else it.reflection`, and the same for the draft), or disable the reflection field while `saving`. Add a view-model test where `reflections.save` suspends while `setReflection` changes the text.

## Verification of the earlier findings

- CR-1 (stale overview load overwrites a newer save): fixed. `saves` is incremented after each successful start, move and complete (`ReviewViewModel.kt:158`, `:188`, `:234`). `reload()` records it before the request (`:117`). When a save finished in between, the load keeps the view model's `current` and `daysSinceLastReview` and applies only the rest (`:125-126`). A conflict reload (404/409/412) does not increment the counter, so it still applies the server's state. Everything runs on `viewModelScope` (Main), so the plain `Int` needs no synchronization. Tests: `ReviewViewModelRaceTest` covers move, completion and a load started after the move.
- CR-2 (draft and open wizard lost on a tab switch): fixed. `ReviewDrafts` is a Hilt `@Singleton` keyed by review id (`ReviewDrafts.kt`). Every `setReflection` writes it while a review is open, so restoring it in `reload()` (`ReviewViewModel.kt:134-135`) cannot overwrite newer typing. The wizard comes back only for the same review id, so a review completed on another device and a new one started there do not reopen it. A completed review is cleared (`drafts.clear`). The Hilt `@Inject` constructor has no `@JvmOverloads`, so Dagger injects the singleton and the default value only serves direct construction in tests. Assumption #7 corrects assumption #3 (not kept across app death). Drafts of reviews completed on another device stay in memory until the process ends. That is harmless.
- CR-3 (double quick-add): fixed. There is a `capturing` guard with a `finally` reset, and the button is disabled while capturing (`ReviewWizard.kt:359`). Text typed during the insert is kept. The IME Done action calls `capture()`, which returns early while capturing.
- CR-4 (duplicate journal section after an offline-reported commit): fixed. `ReflectionSaver.save` returns `SAVED` before each write when the trimmed body already ends with the exact section (`ReflectionSaver.kt:440`). It is covered by `ReflectionSaverTest`, including a conflict whose current entry already holds the section.
- CR-5 (track segment touch target): fixed. The clickable segment is 48dp tall and the visible bar stays 4/8dp (`ReviewWizard.kt:205-224`). The scroll added to `ReviewWizardTest` changes only the viewport. Its assertions are unchanged.
- TEST-1 / CockpitShell change: the Review destination's `onOpen` now pushes the target (`navigate(route) { launchSingleTop = true }`) instead of `open()` (`CockpitShell.kt:182-189`). Every other destination keeps `open()`. Steps 4-7 open More destinations, which `open()` pushed anyway, so only Mail changes behaviour. System back from Mail returns to the wizard, as PARITY decision 6 asks (`ReviewWizardRouteInAppTest` "the Mail link of get clear opens Mail, and back returns to the wizard"). Known trade-offs: the bottom bar highlights Mail, there is no app-bar back arrow on that Mail entry, and the file is outside the task's `touches`. All of them are recorded as assumptions #4 and #6.

## Notes (verified, no finding)

- Criteria 1-6 are implemented as described in the first review, and the fixes did not change that. No new strings were added by the fixes. The en/de `strings_review.xml` files still have the same key set.
- No logging was added. Reflection and journal text do not reach exceptions or logs. All test data is fictitious.
- Evidence still open for the parent worker: `python tasks.py verify and-review-flow` shows all 7 criteria unverified. `reviews/and-review-flow/tests.md` (test-writer report) is not committed yet. `docs/QUALITY.md` requires it, as well as `quality.json`, before `done`. No criterion is claimed without evidence, so this is not a finding against the code.
- Not run by the reviewer: Gradle (`ktlintCheck testDebugUnitTest lintDebug`) and `sh scripts/check.sh`. fixes.md reports both passing at 72df82ee6. The parent must record its own runs with `tasks.py verify`.

## Review conclusion

No material issues found.
