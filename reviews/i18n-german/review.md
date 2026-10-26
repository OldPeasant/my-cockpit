# Code Review

Date: 2026-10-26

Task: i18n-german - German UI

Scope: Task commits of `i18n-german`: `git diff a4e1103b5..4b3d0156e`. After a rebase these are now `86115452b` and `3d93ca8fc`. `git diff 4b3d0156e 3d93ca8fc` changes only `tasks/i18n-german.md`, so the code is identical. Committed changes only. The untracked `web/src/locale/messages-de.spec.ts` (from the test-writer) is not part of the reviewed diff. It was read only as context.

Requirements:
- tasks/i18n-german.md acceptance criterion: "Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)"
- Recorded assumptions in tasks/i18n-german.md: standard German with ß, informal "du", glossary (Inbox=Eingang, Weekly review=Wochenrückblick, Event=Termin); `de` is an extra build config; values-de makes MissingTranslation mandatory
- SPEC.md §4.2 (UI language: English first, strings externalized, German later)
- docs/ARCHITECTURE.md (conventions), AGENTS.md, Dockerfile, scripts/check.sh
- Parent instructions: config correctness, translation correctness and quality, terminology consistency, docs accuracy

Git range:
- Branch: main
- HEAD: 3d93ca8fc84f708ea10a918085b3253b0bab6de3 (code-identical to the supplied 4b3d0156eec62216d101a687b07992a4cb460928)
- Base/start: a4e1103b549dcc281e9d5c3f7491c1399aa64fcb
- Commits reviewed: 2

Summary: 0 P0, 1 P1, 4 P2, 3 P3

## What was verified (no findings)

- **Config:** `i18n.locales.de` points at `src/locale/messages.de.xlf`. `i18nMissingTranslation: "error"` only takes effect when a build localizes. The new `de` configuration (`localize: ["de"]`) is the only one that does that.
  - The default `ng build` (production, not localized) still writes `dist/web/browser`. That is the path the Dockerfile uses (`npm run build`, then `COPY /web/dist/web/browser/`), so the Docker image is unaffected.
  - The `test` target (`@angular/build:unit-test`) and `extract-i18n` don't depend on the new options.
  - `build:de` = `ng build --configuration production,de` writes `dist/web/browser/de`. The ARCHITECTURE.md note matches this.
- **Web XLIFF structure:**
  - `messages.xlf` and `messages.de.xlf` both have 1708 trans-units with identical ids and identical `<source>` text.
  - Every unit has a non-empty `<target>`, and every target keeps the same `<x>` placeholders as its source.
  - All 21 ICU sub-messages keep `VAR_PLURAL`, `plural`, the same case keys (`=0`, `=1`, `other`) and balanced braces.
  - The 63 targets that equal their source are all legitimate: proper names (Google, CalDAV), loan words (Tags, Name, Status, Spam, Passkeys, Layout) or pure `[ICU]` wrapper units.
  - The `backup.restore.confirmWord` "RESTORE" → "WIEDERHERSTELLEN" translation is safe: the comparison is client-only (`backup-settings.ts:105`). The server never sees the word.
- **Android:**
  - `values/` has 564 translatable resources and `values-de/` has the same 564 names.
  - Every entry has the same kind (string or plurals), there are no extras, and every `%1$s`, `%d` and `%1$,d` format argument matches.
  - Plurals keep `one`/`other`, and there are no unescaped apostrophes.
  - No string resources exist outside `strings*.xml`.
  - Lint runs with `warningsAsErrors = true` (`android/app/build.gradle.kts:78`), and MissingTranslation is an error by default. The docs note is accurate.
- **Evidence:** `python tasks.py verify i18n-german` shows criterion #1 not yet verified. The parent still has to record `npm run build:de` and `./gradlew lintDebug` (or the full `scripts/check.sh`) results. Nothing is claimed without evidence yet.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Quick-capture hint teaches German due keywords that the parser does not understand**
    - File: `web/src/locale/messages.de.xlf:2047` (`capture.syntaxHint`)
    - Category: Correctness
    - Problem: The translation shows `!heute` and `!morgen` instead of `!today` and `!tomorrow`. The server parser accepts only the English keywords (`server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt:194-196`, `"today"` and `"tomorrow"`). Any other value returns `null`, so the due date is never set.
    - Why it matters: In the German build, a user who follows the on-screen hint gets a task with no due date. The keyword silently stays unparsed. This breaks a SPEC'd quick-capture feature for German users. The untracked test-writer spec (`messages-de.spec.ts`, "keeps the quick-capture due keywords") will also fail on this unit.
    - Suggested fix: Keep `!today` and `!tomorrow` literally in the German target, e.g. "… <code>!today</code>, <code>!tomorrow</code> oder <code>!2026-11-03</code> …". `#Projekt`, `@Kontext` and `^Name` are only example names and can stay translated.

## P2 Medium

- [ ] **CR-2 | "Last weekly review: N days ago" renders ungrammatical German ("vor 3 Tage")**
    - File: `web/src/locale/messages.de.xlf:9763` (`review.lastDays`), together with the shared ICU unit `3927918797092127748`
    - Category: Correctness
    - Problem: `review.lastDays` is translated as "Letzter Wochenrückblick: vor [ICU]." Its ICU sub-message is the same extracted unit `3927918797092127748`, shared with `gtd.waiting.days` (both `{1 day / N days}`; the unit's context lists both `waiting-page.html:26` and `review-page.html:19`). That unit is translated as nominative "1 Tag / N Tage". After "vor", German needs the dative "Tagen", so the review page shows "vor 3 Tage".
    - Why it matters: Visible grammar error on the weekly review page. Because the unit is shared, it can't be fixed in the ICU unit without breaking the Waiting page.
    - Suggested fix: Rephrase the parent message so the nominative fits, e.g. "Letzter Wochenrückblick: [ICU] her." ("3 Tage her", which matches `dashboard.reviewDaysAgo`). Alternatively, give the review ICU its own text or custom id in the template (a code change outside this task's `touches`).

- [ ] **CR-3 | The "Review" navigation entry is named differently from the review it opens**
    - File: `web/src/locale/messages.de.xlf:9995` (`nav.review`), `android/app/src/main/res/values-de/strings.xml:21` (`nav_review`)
    - Category: Quality
    - Problem: The nav entry is "Überprüfung", but the page and every review string use "Wochenrückblick"/"Rückblick" ("Rückblick starten", "Rückblick beenden", `review_in_progress` "Rückblick läuft"). The recorded glossary assumption says Weekly review = Wochenrückblick. `gtd.project.review` "Überprüfung" (a project's review date) is a different concept, and now shares the wording with the nav item.
    - Why it matters: Inconsistent terminology for a main navigation target, on both platforms.
    - Suggested fix: Use "Rückblick" (or "Wochenrückblick") for `nav.review` and `nav_review`.

- [ ] **CR-4 | The "Waiting For" list has four different German names**
    - File: `web/src/locale/messages.de.xlf:4807` (`nav.waiting` "Warten"), `:4723` (`gtd.waiting.title` "Wartet auf"), `:6239` (`taskDetail.list.waiting` "Warten auf"), `:8747` (`mailRead.toWaiting` "→ Warten auf"); `android/app/src/main/res/values-de/strings_mail.xml:26,39` ("Zu Wartet auf", without quotes); `android/.../values-de/strings.xml:13` (`nav_waiting` "Warten")
    - Category: Quality
    - Problem: The same list is called "Warten", "Wartet auf" and "Warten auf". Messages refer to it as „Wartet auf“ (contacts, dashboard) but also as „Warten auf“ (`mailRead.waitingCreated`). On Android "Zu Wartet auf hinzugefügt" also lacks the quotes that make the list name readable.
    - Why it matters: Users see one list under several names, and the confirmation messages don't match the nav label. The parent explicitly asked about this inconsistency.
    - Suggested fix: Pick one list name (e.g. "Warten auf" for the list and nav, keeping the action button "Warten") and quote it consistently („Warten auf“) in web and Android messages.

- [ ] **CR-5 | Mail "Inbox" and GTD "Inbox" both become "Eingang" on the web, unlike Android**
    - File: `web/src/locale/messages.de.xlf:8267` (`mail.role.inbox`), `:12475` (`mailSettings.role.inbox`), `:9019`/`:9067` (`new.source.inbox`/`new.filter.inbox`, "GTD Inbox" → "Eingang"); compare `android/app/src/main/res/values-de/strings_mail.xml:5` ("Posteingang") and `strings_new.xml:4` ("GTD-Eingang")
    - Category: Quality
    - Problem: On the web, the mail folder role "Inbox" is translated with the GTD term "Eingang". The explicit "GTD Inbox" label in the "New" view (which mixes GTD inbox items and mail) also drops "GTD". Android uses "Posteingang" for the mail folder and "GTD-Eingang" for the GTD source.
    - Why it matters: In the German web UI the mail inbox and the GTD inbox share one name, which is exactly the ambiguity the English "GTD Inbox" label avoids. The two platforms also disagree.
    - Suggested fix: Use "Posteingang" for the mail folder roles (web `mail.role.inbox`, `mailSettings.role.inbox`, and "Alle Posteingänge" for `mail.unified`). Translate "GTD Inbox" as "GTD-Eingang" on the web, as Android does.

## P3 Low

- [ ] **CR-6 | Further cross-module and cross-platform term drift**
    - File: `web/src/locale/messages.de.xlf` and `android/app/src/main/res/values-de/*.xml` (entries listed below)
    - Category: Quality
    - Problem: These are the same English concepts translated differently:
        - **Reference:** "Zur Referenz"/"in die Referenz" (`inbox.action.reference`:4227, `inbox.referenced`:4347) vs "In die Ablage" (`taskDetail.reference`:5947)
        - **Schedule:** "Terminieren"/"Terminiert" (`inbox.action.schedule`:4195, `nav.scheduled`) vs "Geplant" (`taskDetail.list.scheduled`) vs Android "Einplanen"/"Geplant" (`strings_inbox.xml:44`)
        - **Stalled:** "Stockende" (dashboard), "Ins Stocken geraten" (`gtd.projects.stalled`:5135) vs Android "Blockierte Projekte" (`strings_review.xml:25`)
        - **Get clear:** "Klarheit schaffen" (web `review.step.getClear`:9867) vs Android "Den Kopf freibekommen" (`strings_review.xml:17`)
        - **Process:** "verarbeiten" (Eingang verarbeiten) vs "bearbeiten" (`dashboard.inboxTasks`, Android `next_empty`:45, `new_task_hint`). In particular, `mailRead.gtdAria` ("Process" → "Bearbeiten", :8731) reads as "Edit" to screen-reader users.
        - **Item:** "Eintrag" (web inbox) vs "Element" (Android `inbox_count`/`inbox_left`)
        - **Next step:** `gtd.projects.stalledCount` (:5575) says "nächste Aktion", while the English says "next step" and every other project string uses "nächster Schritt".
    - Why it matters: Each case is small, but together they make the German UI look stitched together from separately translated chunks.
    - Suggested fix: Settle one term per concept (e.g. Ablage, Terminieren/Terminiert, Stockend, verarbeiten, Eintrag, nächster Schritt) and align both files. Consider adding the glossary to the assumption or to ARCHITECTURE.md so future strings follow it.

- [ ] **CR-7 | Android empty-projects hint quotes a button label that doesn't exist**
    - File: `android/app/src/main/res/values-de/strings_projects_lists.xml:38` (`projects_empty`)
    - Category: Correctness
    - Problem: The hint says to use „Zu Projekt“, but the inbox action it refers to is labelled "Zum Projekt" (`strings_inbox.xml:42`, `inbox_action_project`; web `inbox.action.project` is also "Zum Projekt").
    - Why it matters: The hint points to a label the user can't find verbatim.
    - Suggested fix: Change it to „Zum Projekt“.

- [ ] **CR-8 | Keyboard shortcut label keeps "Ctrl" in the German UI**
    - File: `web/src/locale/messages.de.xlf:6215` (`taskDetail.saveShortcut`)
    - Category: Quality
    - Problem: The target is unchanged ("Ctrl/⌘ ↵"). German keyboards label the key "Strg".
    - Why it matters: Minor localisation gap in a visible hint.
    - Suggested fix: Use "Strg/⌘ ↵". Optionally use "Umschalt+I" instead of "Shift+I" in `mail.keysHint`.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 (German capture keywords that the parser rejects) must be fixed. The untracked translation spec will fail on it. CR-2 to CR-5 are translation-quality and consistency fixes in the translation files only. Criterion #1 still needs recorded evidence: `npm run build:de` passing with `i18nMissingTranslation=error`, and Android `lintDebug` clean.
