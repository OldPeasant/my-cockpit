# Code Review

Date: 2026-10-26

Task: i18n-german - German UI

Scope: Second review pass on the final code of task `i18n-german`. Whole task diff `git diff 9d66f287777b38892249cf2de47ac8e2538a9737..3e44ed5249c723fc920fd4d1e1beb1a85ce17085` (4 commits), with a focused check of the fix commit `git diff 3e44ed524~1 3e44ed524`. Committed changes only. The working tree was clean.

Requirements:
- tasks/i18n-german.md acceptance criterion: "Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)"
- Recorded assumptions in tasks/i18n-german.md: standard German with ß, informal "du", a glossary (Inbox=Eingang, Weekly review=Wochenrückblick, Event=Termin); `de` is an extra build configuration; values-de makes MissingTranslation mandatory
- SPEC.md §4.2 (UI language), docs/ARCHITECTURE.md (conventions), AGENTS.md
- First-pass findings CR-1..CR-8 (previous revision of this file), reviews/i18n-german/fixes.md, reviews/i18n-german/tests.md
- Parent instructions: verify each fix, check the fix commit for regressions, re-check the whole task diff

Git range:
- Branch: main
- HEAD: 3e44ed5249c723fc920fd4d1e1beb1a85ce17085
- Base/start: 9d66f287777b38892249cf2de47ac8e2538a9737
- Commits reviewed: 4 (b7fc9124d, e1097e043, d423f7a44, 3e44ed524)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was verified

- **Fix commit scope:** 3e44ed524 changes only `<target>` lines in `web/src/locale/messages.de.xlf` and string values in `android/app/src/main/res/values-de/*.xml`, plus `reviews/i18n-german/fixes.md`. No `<source>`, trans-unit id, `<x>` placeholder, format argument or plural quantity changed. No new apostrophes or other XML escaping risks were introduced.
- **No drift from the rebase:** Since the first-pass base (a4e1103b5), main has no commits that touch `web/src` or `android/app/src/main/res/values`. The extracted `messages.xlf` and the English Android strings are still the ones the translations were written for.
- **Config and docs:** `web/angular.json`, `web/package.json` (`build:de`) and the `docs/ARCHITECTURE.md` note are unchanged since the first pass, and they are still correct. The `de` configuration localizes with `i18nMissingTranslation: "error"`. The default build and the Dockerfile path (`dist/web/browser`) stay English-only.
- **Translation spec:** I re-ran `npx ng test --watch=false --include src/locale/messages-de.spec.ts` in `web/`: 1 file, 7 tests passed. This includes the unit-set parity, current-source, non-empty-target, placeholder, ICU-case and capture-keyword checks.
- **Terminology sweep on the final files:** I grepped for the old terms (Terminier*, "Wartet auf", Element, Referenz, Überprüfung, Bearbeite*, "Ins Stocken", Blockiert, "Kopf frei", Ctrl, Shift, "Zu Projekt", !heute/!morgen, "nächste Aktion"). Every hit that remains is intentional:
  - "Bearbeiten" means Edit.
  - "Wartet auf …" labels a person (`capture.waitingFor`, `inbox.prompt.waitingFor`, `taskDetail.waitingFor`).
  - "Überprüfung" is a project's review date (`gtd.project.review`, `gtd.project.form.review`).
  - "nächste Aktion(en)" is the GTD Next Actions list.
- **Inbox terms:** Every web source containing "Inbox" now maps to "Eingang" (GTD), "GTD-Eingang" (the "GTD Inbox" label in New) or "Posteingang" (mail folder roles). This matches Android.

## Disposition of first-pass findings

- [x] **CR-1 | Quick-capture hint taught German due keywords (P1):** Fixed. `capture.syntaxHint` (`web/src/locale/messages.de.xlf:2047`) now shows `!today` and `!tomorrow` literally. The other placeholders and code tags are unchanged. The spec test "keeps the quick-capture due keywords" passes. Android has no capture-syntax hint.
- [x] **CR-2 | "vor 3 Tage" (P2):** Fixed. `review.lastDays` (`:9765`) now reads "Letzter Wochenrückblick: [ICU] her.", which gives "1 Tag her" or "3 Tage her". It matches `dashboard.reviewDaysAgo` ("Tage her"). The shared ICU unit is unchanged, so `gtd.waiting.days` still works. Android `review_last_days` was already grammatical ("vor %1$d Tag/Tagen").
- [x] **CR-3 | Review nav naming (P2):** Fixed. Web `nav.review` and Android `nav_review` are now "Wochenrückblick". The project review date keeps "Überprüfung" because it is a different concept.
- [x] **CR-4 | Four names for Waiting For (P2):** Fixed. The list is now "Warten auf" in navigation, page titles, review steps, contacts, task detail and mail actions on both platforms. When it is used as a name in running text, it is quoted („Warten auf“). The action button stays "Warten", and `gtd.waiting.empty` quotes it correctly as „Warten“.
- [x] **CR-5 | Mail vs GTD inbox (P2):** Fixed. On the web, `mail.role.inbox` and `mailSettings.role.inbox` are "Posteingang", `mail.unified` is "Alle Posteingänge", and `new.source.inbox`, `new.filter.inbox` and `new.keysHint` use "GTD-Eingang". This now matches Android.
- [x] **CR-6 | Term drift (P3):** Fixed.
  - Ablage, Einplanen/Geplant, Stockend, Klarheit schaffen, Verarbeiten, Eintrag and "nächster Schritt" are applied on both platforms.
  - The articles and pronouns that changed with the Element→Eintrag switch are grammatical ("Der Eintrag ist …", "Er zeigt …", "Verknüpften Eintrag erstellen", "Nicht verfügbarer Eintrag").
  - `mailRead.gtdAria` is now "Verarbeiten", so the Edit ambiguity for screen-reader users is gone.
- [x] **CR-7 | „Zu Projekt“ (P3):** Fixed. `projects_empty` now quotes „Zum Projekt“, which matches `inbox_action_project`.
- [x] **CR-8 | Ctrl/Shift (P3):** Fixed. `taskDetail.saveShortcut` now reads "Strg/⌘ ↵" and `mail.keysHint` uses "Umschalt+I".

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Evidence note (not a finding)

`python tasks.py verify i18n-german` still shows criterion #1 as unverified. Before `done`, the parent must record the actual results. For example, `cd web && npm run build:de` passes with `i18nMissingTranslation=error` and shows no translation warnings, and `cd android && ./gradlew lintDebug` passes (MissingTranslation is an error under `warningsAsErrors`). `fixes.md` reports both as passing, but they are not yet recorded with `verify`.

## Review conclusion

No material issues found.
