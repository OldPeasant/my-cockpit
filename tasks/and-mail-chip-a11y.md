---
title: Android compose: 48dp remove target and accessibility action for recipient chips
status: in_progress
owner: felix/agent-1
deps: [and-mail-compose-plus]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-11-08T20:42:22Z
---
## Goal
Follow-up of the and-mail-compose-plus review (P3, `reviews/and-mail-compose-plus/review.md` CR-1): the recipient
chip's close icon in `RecipientInput.kt` is the only way to remove a recipient, but it is an 18 dp touch target, and
the chip itself is announced to TalkBack as a clickable control that does nothing.

## Acceptance criteria
- [ ] The chip's remove control has at least a 48 dp touch target (`IconButton` or `minimumInteractiveComponentSize()`)
- [ ] TalkBack does not announce the chip body as an action that does nothing; the chip offers a "Remove" accessibility action (English and German string)
- [ ] Compose tests cover it; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- ASSUMPTION (2026-11-08 21:05+0100 by felix/agent-1): Replaced Material InputChip with a custom Surface+Row chip for recipients: InputChip always exposes an OnClick action, so a non-clickable body requires a custom chip (same colors: surface + outlineVariant border, errorContainer for invalid).
