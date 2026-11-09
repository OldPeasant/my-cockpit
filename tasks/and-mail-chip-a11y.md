---
title: Android compose: 48dp remove target and accessibility action for recipient chips
status: in_progress
owner: felix/agent-1
deps: [and-mail-compose-plus]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-11-09T03:25:51Z
releases: 0
extended: 
hold: 
---
## Goal
Follow-up of the and-mail-compose-plus review (P3, `reviews/and-mail-compose-plus/review.md` CR-1): the recipient
chip's close icon in `RecipientInput.kt` is the only way to remove a recipient, but it is an 18 dp touch target, and
the chip itself is announced to TalkBack as a clickable control that does nothing.

## Acceptance criteria
- [x] The chip's remove control has at least a 48 dp touch target (`IconButton` or `minimumInteractiveComponentSize()`)
  - verified (2026-11-08 21:24+0100 by felix/agent-1): MailComposeChipA11yTest 'the remove control has a 48 dp touch target' (IconButton size 48.dp; assertWidth/HeightIsAtLeast(48.dp)) via ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*' -> pass
- [x] TalkBack does not announce the chip body as an action that does nothing; the chip offers a "Remove" accessibility action (English and German string)
  - verified (2026-11-08 21:24+0100 by felix/agent-1): MailComposeChipA11yTest: chip has no OnClick semantics, not a button, reads its label; CustomActions == [Remove] (de: [Entfernen]) removes only its recipient; disabled while sending with no action -> 7/7 pass (robolectric unit tests)
- [x] Compose tests cover it; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 21:24+0100 by felix/agent-1): cd android && ./gradlew ktlintCheck lintDebug -> OK; ./gradlew ktlintCheck testDebugUnitTest -> all mail.compose tests pass, 1 failure auth.AppStartRoutingTest 'logging out from settings clears the cached files' which fails identically on clean origin/main (worktree, --rerun-tasks), unrelated; sh scripts/check.sh via tasks.py done

## Notes
- ASSUMPTION (2026-11-08 21:05+0100 by felix/agent-1): Replaced Material InputChip with a custom Surface+Row chip for recipients: InputChip always exposes an OnClick action, so a non-clickable body requires a custom chip (same colors: surface + outlineVariant border, errorContainer for invalid).
- release by felix/agent-1: Implementation, tests, review (0 findings after CR-1 fix), fixer, verify 1-3 and quality.json all committed. Only blocker: auth.AppStartRoutingTest 'logging out from settings clears the cached files' fails reproducibly on clean origin/main too, so the testDebugUnitTest/check.sh validation cannot be 'pass' (new task and-auth-logout-cache-test). Once fixed: rerun ./gradlew ktlintCheck testDebugUnitTest lintDebug and sh scripts/check.sh, set both validation results in reviews/and-mail-chip-a11y/quality.json to pass, commit, tasks.py done.
- hold by felix/agent-1: Blocked by and-auth-logout-cache-test: AppStartRoutingTest 'logging out from settings clears the cached files' still fails on current main (re-run 2026-11-08 ~21:15, 9 tests 1 failed), so .taskcheck cannot pass. All work, reviews, verify 1-3 and quality.json are committed on wip/and-mail-chip-a11y. Unhold once that task is done; then rerun check, set validation results in quality.json to pass, tasks.py done.
