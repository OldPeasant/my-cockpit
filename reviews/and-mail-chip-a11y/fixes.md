# Fix Report

Date: 2026-11-08

Task: and-mail-chip-a11y - Android compose: 48dp remove target and accessibility action for recipient chips

Scope: current task, finding CR-1 from `reviews/and-mail-chip-a11y/review.md`

## Fixed findings

### CR-1 — The remove icon of a disabled chip is dimmed twice

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`

Problem:
`RecipientChip` applied `Modifier.alpha(0.38)` to the whole `Surface`. The disabled `IconButton` inside it also applies Material's disabled content color (content color at 0.38 alpha), so the close icon ended up at about 0.14 effective alpha while the label stayed at 0.38.

Fix:
Removed the `Modifier.alpha` from the chip (and the now unused `androidx.compose.ui.draw.alpha` import). When the chip is disabled, the label `Text` color and the border color are each multiplied by `DISABLED_ALPHA` (0.38) one time. The close icon gets its dimming only from the `IconButton`'s own disabled content color. That color is derived from the Surface's `contentColor` at 0.38, so the label, border and icon are all dimmed once. The container background is not dimmed. Semantics, the custom action, the 48 dp target and the enabled appearance are unchanged.

Validation:
- `cd android && ./gradlew -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` (in `android/`) — Pass (no failures reported)

Full test suite:
- Not run by fixer (parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`)

Lint/type-check/build:
- ktlintCheck — Pass; lintDebug not run by fixer

`.taskcheck`:
- Not run (parent runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
