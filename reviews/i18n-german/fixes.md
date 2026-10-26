# Fix Report

Date: 2026-10-26

Task: i18n-german - German UI

Scope: Current task. Findings CR-1..CR-8 from `reviews/i18n-german/review.md` and TW-1 from `reviews/i18n-german/tests.md`. Only `<target>` texts in `web/src/locale/messages.de.xlf` and the strings in `android/app/src/main/res/values-de/*.xml` were changed. No `<source>`, trans-unit id or `<x .../>` placeholder was touched: every changed XLIFF diff line is a `<target>` line. The parent agent made the terminology decisions, and they are applied the same way on web and Android.

## Fixed findings

### CR-1 / TW-1 — Quick-capture hint teaches German due keywords the parser rejects

Files changed:
- `web/src/locale/messages.de.xlf`

Problem:
`capture.syntaxHint` told users to type `!heute` / `!morgen`. The server parser (`CaptureParser`) accepts only `!today` / `!tomorrow`.

Fix:
The target now keeps `!today` and `!tomorrow` literally. The waiting-list name in the same hint is now „Warten auf“ (CR-4). No capture-syntax hint exists in the Android resources (checked with grep).

Validation:
- `cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts` — Pass (7/7, TW-1 test now green)

### CR-2 — "Letzter Wochenrückblick: vor 3 Tage"

Files changed:
- `web/src/locale/messages.de.xlf`

Problem:
`review.lastDays` put "vor" in front of the shared nominative ICU unit ("1 Tag / N Tage"). That rendered as "vor 3 Tage".

Fix:
The target is now "Letzter Wochenrückblick: [ICU] her." The shared ICU unit `3927918797092127748` is unchanged. The Waiting page (`gtd.waiting.days`) uses that ICU alone ("3 Tage"), and it is still correct. Android `review_last_days` was already grammatical ("vor %1$d Tagen") and was left unchanged.

Validation:
- `cd web && npm run build:de` — Pass

### CR-3 — Review nav entry named "Überprüfung"

Files changed:
- `web/src/locale/messages.de.xlf` (`nav.review`)
- `android/app/src/main/res/values-de/strings.xml` (`nav_review`)

Problem:
The nav entry did not match the weekly review terminology.

Fix:
Both platforms now use "Wochenrückblick". `gtd.project.review` / `gtd.project.form.review` (a project's review date) stay "Überprüfung", because that is a different concept.

Validation:
- `cd android && ./gradlew lintDebug ktlintCheck` — Pass

### CR-4 — Waiting For list had four names

Files changed:
- `web/src/locale/messages.de.xlf`
- `android/app/src/main/res/values-de/strings.xml`, `strings_inbox.xml`, `strings_mail.xml`, `strings_contacts.xml`, `strings_review.xml`

Problem:
The list was called "Warten", "Wartet auf" and "Warten auf", and some Android messages left the name unquoted.

Fix:
The list is now "Warten auf" everywhere:
- Web: `nav.waiting`, `gtd.waiting.title`, `contacts.related.waiting`, `review.step.waitingFor`, `mailRead.toWaiting` (→ „Warten auf“), plus „Warten auf“ in `contacts.related.loadFailed`, `dashboard.waitingOverdue` and `capture.syntaxHint`.
- Android: `nav_waiting`, `contacts_waiting_for`, `review_step_waiting_for`, `inbox_confirm_waiting` ("Nach „Warten auf“ verschieben"), `mail_read_to_waiting` ("Zu „Warten auf“"), `mail_read_waiting_created`, `mail_read_waiting_failed`, and the „Warten auf“-Einträge strings in contacts.

Labels that name a person keep "Wartet auf …": `capture.waitingFor`, `taskDetail.waitingFor` and `inbox.prompt.waitingFor`. The processing action button "Waiting" stays "Warten" on both platforms (`inbox.action.waiting`, `inbox_action_waiting`), and `gtd.waiting.empty` quotes that button label as „Warten“.

Validation:
- `cd android && ./gradlew lintDebug ktlintCheck` — Pass
- `cd web && npm run build:de` — Pass

### CR-5 — Mail inbox and GTD inbox both "Eingang" on the web

Files changed:
- `web/src/locale/messages.de.xlf`

Problem:
On the web, the mail folder role and the "GTD Inbox" label both used "Eingang". Android does not.

Fix:
- `mail.role.inbox` and `mailSettings.role.inbox` are now "Posteingang".
- `mail.unified` is now "Alle Posteingänge".
- `new.source.inbox` and `new.filter.inbox` are now "GTD-Eingang".
- `new.keysHint` now says "2 GTD-Eingang".

Android already used "Posteingang" / "GTD-Eingang", so it is unchanged.

Validation:
- `cd web && npm run build:de` — Pass

### CR-6 — Term drift across modules and platforms

Files changed:
- `web/src/locale/messages.de.xlf`
- `android/app/src/main/res/values-de/strings_review.xml`, `strings_next_today.xml`, `strings_new.xml`, `strings_inbox.xml`, `strings_contacts.xml`

Problem:
The same concepts had different translations.

Fix:
- Reference = "Ablage": `inbox.action.reference` is now "In die Ablage", and `inbox.referenced` now says "in die Ablage".
- Schedule = "Einplanen", scheduled = "Geplant": `inbox.action.schedule` is now "Einplanen". `gtd.scheduled.title` and `nav.scheduled` are now "Geplant". `gtd.scheduled.empty` is now "Nichts geplant. Plane einen Eingangseintrag ein, …". Android already used these terms.
- Stalled = "Stockend": `gtd.projects.stalled` is now "Stockend", and Android `review_stalled` is now "Stockende Projekte".
- Get clear = "Klarheit schaffen": Android `review_step_get_clear`.
- Process = "Verarbeiten": web `dashboard.inboxTasks` ("zu verarbeiten") and `mailRead.gtdAria` ("Verarbeiten"), Android `next_empty` and `new_task_hint` ("Verarbeite …").
- Item = "Eintrag":
  - Web: `notes.editor.placeholder`, `trash.hint`, `trash.empty`, `links.heading`, `links.searchLabel`, `links.resultsLabel`, `links.inlineTitle`, `links.empty`, `links.createLabel`, `links.create`, `links.unavailable`.
  - Android: `inbox_count`, `inbox_left`, `inbox_message_offline` / `_conflict` / `_failed` (articles and pronouns adjusted), `contacts_links`.
- Next step: `gtd.projects.stalledCount` now says "einen nächsten Schritt".

"Next action" (the GTD Next Actions list) intentionally stays "nächste Aktion". It is a separate English term.

Validation:
- `cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts` — Pass
- `cd android && ./gradlew lintDebug ktlintCheck` — Pass

### CR-7 — Android empty-projects hint quotes a nonexistent label

Files changed:
- `android/app/src/main/res/values-de/strings_projects_lists.xml`

Problem:
`projects_empty` referred to „Zu Projekt“. The button label is "Zum Projekt".

Fix:
It now says „Zum Projekt“.

Validation:
- `cd android && ./gradlew lintDebug ktlintCheck` — Pass

### CR-8 — "Ctrl" in German shortcuts

Files changed:
- `web/src/locale/messages.de.xlf`

Problem:
`taskDetail.saveShortcut` kept "Ctrl".

Fix:
`taskDetail.saveShortcut` is now "Strg/⌘ ↵" (⌘ kept). `mail.keysHint` now says "Umschalt+I" instead of "Shift+I", as the review suggested optionally.

Validation:
- `cd web && npm run build:de` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts` — Pass (1 file, 7 tests passed)

Full test suite:
- `sh scripts/check.sh` — Pass ("all checks passed": tfp, server, web lint/tests/build, android ktlint + unit tests + lint)

Lint/type-check/build:
- `cd web && npm run build:de` — Pass. Output is in `dist/web/browser/de`. A grep for translation/i18n/localize diagnostics in the build output found 0 matches. The only warning is the initial bundle budget warning, which also appears in the regular production build.
- `cd android && ./gradlew lintDebug ktlintCheck` — Pass (BUILD SUCCESSFUL, lint `warningsAsErrors`)

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
