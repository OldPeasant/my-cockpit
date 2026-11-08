# Code Review

Date: 2026-11-08

Task: and-mail-chip-a11y - Android compose: 48dp remove target and accessibility action for recipient chips

Scope: task commit 2355d2957 on top of base 3d7682042cd179cd8bc578ae269b340757ed8845 (`git diff 3d76820..2355d2957`). This covers `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt` (`RecipientChip`, the `RecipientInput` call site) and the Compose tests in `android/app/src/test/java/ch/sonensei/cockpit/mail/compose` (`MailComposeChipA11yTest.kt`, plus the updated `MailComposeRecipientsFilesReviewFixesScreenTest.kt`). Committed changes only. The working tree was clean at the start.

Requirements:
- tasks/and-mail-chip-a11y.md (acceptance criteria #1-#3)
- reviews/and-mail-compose-plus/review.md CR-1 (where this task comes from)
- AGENTS.md project notes (lint contract, English and German strings)
- `res/values{,-de}/strings_mail_compose.xml`

Git range:
- Branch: main
- HEAD: 2355d2957
- Base/start: 3d7682042cd179cd8bc578ae269b340757ed8845
- Commits reviewed: 1 (2355d2957)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The remove icon of a disabled chip is dimmed twice**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt:213` (and `:236`)
    - Category: Quality
    - Problem: When the chip is disabled, the whole `Surface` is drawn with `alpha(DISABLED_ALPHA)` (0.38). Inside it, `IconButton(enabled = false)` also applies Material's disabled content color, which is the content color at 0.38 alpha. So the close icon ends up at about 0.14 effective alpha, while the label is at 0.38.
    - Why it matters: The disabled chip looks uneven, and the icon is close to invisible. This only happens while sending, so the impact is cosmetic. Disabled controls are exempt from contrast requirements.
    - Suggested fix: Dim only once. Either leave the alpha off the `IconButton` (for example, pass `IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current)`), or apply the alpha only to the container and label.

## Notes

- Criterion #1 (48 dp target) is met. The remove control is an `IconButton` with `Modifier.size(48.dp)`. `MailComposeChipA11yTest` checks that it has a click action and is at least 48 dp wide and tall.
- Criterion #2 (no dead action; a "Remove" action in English and German) is met:
  - The chip is now a `Surface` without a click handler, so the chip node has no `OnClick` and no `Role`. Tests check both.
  - The chip node uses `semantics(mergeDescendants = true)`. It merges the label `Text`. It does not swallow the `IconButton`, because `clickable` is its own merge boundary. TalkBack therefore focuses the chip as "<label>, actions available" and then the button as "Remove <address>, button". The disabled state follows the same split.
  - The custom action uses the existing `mail_compose_remove_recipient` key ("Remove" / "Entfernen"), which is present in both `values/` and `values-de/`. A Robolectric test with `@Config(qualifiers = "de")` checks the German label.
  - When the chip is disabled, it is marked `disabled()`, offers no custom action, and the `IconButton` is disabled. Tests cover this.
  - The action lambda captures the current `value`/`index` on each recomposition, so it removes the right chip. A test checks this on the second chip.
- Criterion #3 (tests): Compose tests cover the target size, no click action on the body, the label and role, the custom action on valid and invalid chips, the German locale, and the disabled state. The earlier body-tap test now also asserts that no `OnClick` exists. No test was weakened. This reviewer could not use a shell (Bash was denied), so it did not run Gradle, `sh scripts/check.sh` or `python tasks.py verify and-mail-chip-a11y`. It also could not read the raw diff, so it reviewed the files at HEAD instead. The parent worker must record the evidence for #3 from real runs of `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`.
- Visual side effect, not a finding: the 48 dp button sits inside the bordered `Surface`, so the chip is now 48 dp tall instead of the Material input chip's 32 dp. The task explicitly allows an `IconButton`, so this is accepted.

## Review conclusion

No material issues found.
