# Code Review

Date: 2026-11-09

Task: web-de-invite-translations - German translations for the iTIP invitation dialog (calendar.invite.*)

Scope: Current state of `web/src/locale/messages.xlf` and `web/src/locale/messages.de.xlf` at HEAD, limited to the `calendar.invite.*` units. The task has no code commits of its own; the 16 units were added by commit ba52ebac3 (mail-load-older-ui fixer), which was reviewed for its locale part only (`git show ba52ebac3 -- web/src/locale`). The markers they must match are in `web/src/app/features/calendar/edit/invitation-dialog.ts`, `event-dialog.ts` and `event-dialog.html`.

Requirements:
- `tasks/web-de-invite-translations.md` (acceptance criteria 1 and 2)
- AGENTS.md project notes (i18n: add units by hand, no full extract)
- Parent instructions: ids, source text, placeholders match the markers; German correct, natural, du-form, consistent with the rest of `messages.de.xlf`

Git range:
- Branch: main
- HEAD: 27c8e091c19dbd3470bb8a410788f5dfa9e401c6
- Base/start: 27c8e091c19dbd3470bb8a410788f5dfa9e401c6 (no task commits; source commit of the units: ba52ebac3)
- Commits reviewed: 0 task commits (locale part of ba52ebac3 inspected as the origin of the current state)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands and results

- `grep -rn 'calendar.invite' web/src/app` -> 16 i18n markers: `open` (event-dialog.html), `saveFirst`, `cancelSent`, `requestSent` (event-dialog.ts), `title`, `to`, `method`, `request`, `cancel`, `loading`, `noAccounts`, `account`, `send`, `accountsFailed`, `failed`, `rejected` (invitation-dialog.ts).
- `grep -c 'id="calendar.invite' web/src/locale/messages.xlf web/src/locale/messages.de.xlf` -> 16 and 16; every id appears once per file, no duplicates.
- Source texts compared marker by marker: identical, including the surrounding whitespace that Angular keeps for multi-line template content (`noAccounts`, `send`), the `&apos;` encoding in `rejected`, and the ellipsis character in `loading`.
- Placeholders: `cancelSent` and `requestSent` use `<x id="count" equiv-text="sent.recipients.length"/>` in source and target, matching `${sent.recipients.length}:count:` in event-dialog.ts.
- `cd web && npm run build:de` -> "Application bundle generation complete", output in `web/dist/web`; `npm run build:de 2>&1 | grep -i -c 'no translation|missing|error'` -> 0. The only warning is the pre-existing initial bundle budget warning (692 kB vs 500 kB), unrelated to this task.
- Not run by the reviewer (per parent instruction): `npm run check`, `sh scripts/check.sh`, Gradle. Criterion 2 needs the parent's own evidence.

## German review

All 16 targets are `state="translated"`, grammatical and in du-form where the user is addressed ("Speichere deine Änderungen, bevor du Einladungen sendest.", "Füge in den Einstellungen ein E-Mail-Konto hinzu, ...", "Prüfe die Adressen der Gäste."). Terminology matches the rest of the file: "E-Mail-Konto/-Konten" (used throughout), "Senden" (same as `mailCompose.send`), "Mailserver" (5 other uses vs 2 of "E-Mail-Server"), "Absage" next to the existing "Absagen" action, and "Gast/Gäste" for "guest(s)" in line with the "Tag(e)" / "Stunde(n)" convention for optional plurals (a parenthesised suffix does not work for the umlaut plural). "Senden von" for the account picker label is understandable and close to the compose form's "Von".

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The `calendar.invite.*` units in both XLIFF files match the i18n markers (ids, source text, placeholders), the German translations are correct and consistent, and `npm run build:de` reports no missing translation (criterion 1). Criterion 2 (`npm run check`, `scripts/check.sh`) was not run by the reviewer and must be verified by the parent worker.
