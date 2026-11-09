---
title: Linked items on projects and files; follow-up by mail (web + Android)
status: in_progress
owner: dell26/agent-2
deps: [and-links-panel,web-projects-plus,and-project-tasks,and-files-preview,and-scheduled-list,web-task-actions]
touches: [web/src/app/features/gtd/projects,web/src/app/features/files,web/src/app/features/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists,web/src/locale,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 3
lease_until: 2026-11-09T04:31:54Z
releases: 0
extended: 
hold: 
---
## Goal
SPEC.md §4.3 wants the linked-items panel on every detail view; projects and files have none on either client.
SPEC.md §5.3 also wants waiting "follow up" to be able to draft an email to the contact.

## Acceptance criteria
- [ ] Web and Android project detail show the linked-items panel
- [ ] Web file details/preview and Android file preview show the linked-items panel
- [ ] Waiting "Follow up" offers "Draft email" (prefilled To from the linked contact or waiting-for text, subject from the task) on web and Android
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 02:12+0100 by felix/agent-1): Web 'Draft email' opens /mail/compose?to=&subject=; added minimal ?to/?subject prefill to the compose page (outside touches, 5 lines) since no prefill existed. To = first email of waitingForContactId's contact, else an address found in waitingForText, else empty (a plain name is not a usable address).
- ASSUMPTION (2026-11-09 02:12+0100 by felix/agent-1): Web file preview: linked items shown in a toggleable side panel ('Linked items' button) of the full-window preview; Android project detail: collapsible 'Linked items' section under the next-step line, linked items open via SearchItemScreen.
- release by felix/agent-1: Done (commit b8eddca01, NOT yet compiled/tested): web project detail <app-linked-items PROJECT>; web file preview dialog 'Linked items' toggle + side panel (FILE), arrow keys ignored inside .links; web waiting 'Draft email' (followUpMail/emailIn in list-views.ts, getContact for waitingForContactId) -> /mail/compose?to=&subject= with small prefill in mail-compose-page.ts open(); xlf extracted + de targets; Android ProjectsScreen collapsible LinkedItemsSection(PROJECT) + strings en/de. LEFT: Android file preview panel (files/FilePreviewView.kt / FilesScreen.kt, strings_files.xml), Android waiting 'Draft email' (gtd/lists/WaitingScreen.kt; how does Android compose accept prefill?), unit tests (web: waiting-page/list-views/preview-dialog/project-detail/compose prefill; Android: projects/files/waiting), run npm run check + android checks, test-writer/code-reviewer quality gate, verify + done.
- release by felix/agent-1: Done: web project detail panel, web file preview links toggle, web waiting 'Draft email' (+compose ?to/?subject prefill), Android project detail panel, Android file preview links toggle (8fc3ea185; FilesContent/FilePreviewView take optional linksPanel slot, null in tests). Web npm run check: lint/build OK, 1 failing test projects-states.spec.ts 'shows an error when the tasks cannot be loaded, and retries' - likely the new <app-linked-items> renders its own .error when its request is unmocked; mock the links request or scope the selector. LEFT: Android waiting 'Draft email' (WaitingScreen.kt; in-app compose has no prefill and android mail/compose is touched by mail-identity-drafts - add ComposeMode.New prefill after it lands, or use a mailto: ACTION_SENDTO intent; To = contact email via waitingForContactId else address in waitingForText), unit tests (web waiting/list-views/preview/project; Android projects/files/waiting), android ktlintCheck testDebugUnitTest lintDebug, quality gate (test-writer, code-reviewer), verify, done.
- ASSUMPTION (2026-11-09 02:19+0100 by felix/agent-1): Android 'Draft email' opens a mailto: ACTION_SENDTO intent (To = address found in waitingForText, else empty; subject = task title) instead of in-app compose: Android GtdTask has no waitingForContactId and android mail/compose is being changed by mail-identity-drafts; switch to in-app compose prefill later.
- release by felix/agent-1: Done since last: Android waiting 'Draft email' (WaitingScreen.kt mailto ACTION_SENDTO, en/de strings, ListScreensTest cases), listLinks mocked in projects-states.spec.ts; code-reviewer report committed (reviews/gtd-links-everywhere/review.md: CR-1 missing tests, CR-2 FilesScreen.kt SearchItemScreen drawn over FilesContent instead of replacing it + pass modifier, CR-3 show 'no app' toast when ContactIntents.launch returns false). LEFT: (1) ktlint import-ordering in WaitingScreen.kt line 3 -> run ./gradlew ktlintFormat; (2) web projects-states.spec.ts 'an emptied description is cleared on the server' fails because a text() selector now matches the links empty state - scope the selector; run npm run check from web/ (running from repo root gives a spurious app-icons ENOENT); (3) fixer for CR-1..3, test-writer was started but may not have written tests.md - rerun test-writer + code-reviewer on final code; (4) verify, quality.json, done. Android check via 'sh scripts/check.sh android' takes ~9 min.
