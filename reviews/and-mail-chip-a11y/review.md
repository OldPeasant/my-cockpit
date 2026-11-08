# Code Review

Date: 2026-11-08

Task: and-mail-chip-a11y - Android compose: 48dp remove target and accessibility action for recipient chips

Scope: the final task code at be05d532bd487f75f63db4af6fc9caa795ab66d5 on top of base 3d7682042cd179cd8bc578ae269b340757ed8845. Commits 0d1aaa7ff (the implementation) and be05d532b (the CR-1 fix, one TalkBack label test, and the first reports). This is a re-review after the fix for CR-1 from the previous review at 2355d2957. Files reviewed in full at HEAD: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt` and `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeChipA11yTest.kt`. Committed changes only.

Requirements:
- tasks/and-mail-chip-a11y.md (acceptance criteria #1-#3, plus the custom-chip assumption)
- reviews/and-mail-chip-a11y/review.md (previous revision), reviews/and-mail-chip-a11y/fixes.md
- reviews/and-mail-compose-plus/review.md CR-1 (where this task comes from)
- AGENTS.md project notes (lint contract, English and German strings)

Git range:
- Branch: main
- HEAD: be05d532bd487f75f63db4af6fc9caa795ab66d5
- Base/start: 3d7682042cd179cd8bc578ae269b340757ed8845
- Commits reviewed: 2 (0d1aaa7ff, be05d532b)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes

- Previous CR-1 (the disabled remove icon was dimmed twice) is resolved. `RecipientChip` (`RecipientInput.kt:202-245`) no longer applies `Modifier.alpha` to the whole `Surface`, and the unused `alpha` import is gone. When the chip is disabled, the label color (`:231`) and the border color (`:212`) are each multiplied by `DISABLED_ALPHA` once. The close icon is dimmed only by the disabled `IconButton`. That button's disabled content color comes from `LocalContentColor`, which the `Surface` sets to `content`, at 0.38. So the label, border and icon all end up at the same effective alpha. When the chip is enabled, `dim` is 1f and nothing changes from the reviewed design.
- Side effect, not a finding: the container fill is no longer dimmed when the chip is disabled. For an invalid chip, which has `errorContainer` and no border, the disabled state now shows only through the dimmed label and icon. This lasts only while sending, and disabled controls are exempt from contrast rules, so it is acceptable.
- No regression found:
  - The semantics block is unchanged: it uses `mergeDescendants`, is `disabled()` when disabled, and otherwise has the "Remove" custom action.
  - The 48 dp `IconButton`, the `onRemove` capture of `value`/`index`, and the call site are unchanged.
- Criterion #1 (48 dp target) is met. `IconButton` uses `Modifier.size(48.dp)`, and a test checks the click action and that it is at least 48 x 48 dp.
- Criterion #2 is met:
  - The chip body has no `OnClick` and no `Role`. Tests check both, and the added TalkBack test also checks that the chip node reads "Ann Example".
  - The "Remove" custom action works on valid and invalid chips, removes only its own recipient (tested on the second chip), and has the German label "Entfernen" (Robolectric `@Config(qualifiers = "de")`).
  - When disabled, the chip is marked disabled and offers no action.
- Criterion #3: Compose tests cover the behavior above. No test was weakened. The visual alpha is not asserted by a test, which is reasonable for a purely cosmetic detail. This reviewer had no shell (Bash was denied), so it did not run Gradle, `sh scripts/check.sh` or `tasks.py verify`, and it could not read the raw diff. The parent reports that `ktlintCheck` and all `mail.compose` unit tests pass. Before recording evidence for #3, the parent worker must also run the full `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`.

## Review conclusion

No material issues found.
