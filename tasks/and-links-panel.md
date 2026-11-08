---
title: Android: shared linked-items panel with add, remove, create linked
status: in_progress
owner: dell26/agent-1
deps: [and-task-convert]
touches: [android/app/src/main/java/ch/sonensei/cockpit/core/links,android/app/src/test/java/ch/sonensei/cockpit/core/links,android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,android/app/src/main/res/values/strings_contacts.xml,android/app/src/main/res/values-de/strings_contacts.xml,android/app/src/main/res/values/strings_links.xml,android/app/src/main/res/values-de/strings_links.xml]
prio: 2
lease_until: 2026-11-08T19:57:52Z
releases: 1
extended: 
---
## Goal
Android shows links read-only on task detail and contacts (contact links not even tappable). Web has a shared
panel with add (search-as-you-type), remove and "create linked…" (`web/src/app/shared/links`). SPEC.md §4.3.

## Acceptance criteria
- [ ] A reusable Compose `LinkedItemsPanel` (in `core/links`): lists links, opens each in-app where a screen exists (else the web URL), adds a link via the global search API, removes a link, "create linked task/note"
- [ ] Used in task detail (replacing the current list) and contact detail; contact related rows (waiting for, events, mails) become tappable
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Mail, note and event panels use this component in their own tasks.
- release by dell26/agent-1: Done (compiles, committed): core/links/{LinksRepository (binds generated LinksApi; list/link/remove/search/createTask/createNote), LinkedItemsViewModel (debounced search, add, remove manual links, create linked task/note + link, 409 -> reload), LinkedItemsPanel + LinkedItemsSection (hiltViewModel keyed per item; web targets open in browser)}, strings_links.xml EN+DE; TaskDetailScreen renders LinkedItemsSection via a linksPanel slot (old Links list, openLink, linkTarget, TaskDetailTags.LINKS/link removed). LEFT: (1) fix TaskDetailContentTest (~lines 66-112 use removed openLink/TaskDetailTags.link) and optionally drop the unused TaskLink loading from TaskDetailRepository (+ its test); (2) contacts: put LinkedItemsSection in ContactDetailContent (slot; used by ContactsScreen and search/SearchItemScreen SearchContact), drop the RelatedLink list, make waiting-for/mail/event RelatedRows clickable; ContactsRoute has no navigation, so open items inside ContactsRoute via a rememberSaveable TYPE:id state rendering SearchItemScreen (TASK/NOTE/EMAIL/CONTACT), events -> web server/calendar/events/id; (3) tests: LinkedItemsViewModelTest (fake LinksSource), LinksRepositoryTest (MockWebServer), LinkedItemsPanel Compose test; (4) record assumptions (create-linked asks for a title, task goes to Inbox), quality workflow, verify, done.
- ASSUMPTION (2026-11-08 20:41+0100 by dell26/agent-1): Create linked task/note asks for a title in a dialog; the new task goes to the Inbox (web panel parity; SPEC §4.3 leaves it open)
