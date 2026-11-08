# Web / Android feature parity

Inventory of 2026-11-08 (UI code of both clients compared feature by feature) and the owner's decisions on it.
Tasks reference this file; update it when a gap is closed or a decision changes.

## Decisions (owner, 2026-11-08)

1. **Android gets everything used day to day**, beyond the v1 scope of SPEC.md §9.2: GTD incl. project management and
   full task detail, mail actions and attachments, notes, files, calendar, contacts editing, journal, habits editing
   and the full weekly review.
2. **Stays web-only** (account and admin setup): mail accounts, identities CRUD, folder roles, calendar sources,
   integrations/OAuth, CardDAV, vCard import/export, backup/restore, first-run setup, devices, recovery codes,
   passkey delete, GTD contexts/areas management, labels, time zone and week start editing. Android *uses* these
   settings (task `and-user-settings`) but does not edit them.
3. **Desktop idioms get mobile equivalents** on Android: long-press multi-select instead of checkboxes and shortcuts,
   long-press drag to reorder. No keyboard shortcuts on Android.
4. **Web gaps and features neither client has** are planned too.
5. **Mail images:** remote images stay blocked by default ("Load images", optional per-sender allowlist), driven by
   the synced settings `mailImagePolicy` / `mailImageAllowedSenders` on both clients; inline (`cid:`) images always
   show.
6. **Navigation:** back returns to where the user came from; a mail search survives opening a match, and the read
   view steps to the previous/next match.

## Gaps and the tasks that close them

### Cross-cutting UX
| Gap | Client | Task |
|---|---|---|
| Inline and remote mail images never show | Android | and-mail-images |
| Image settings ignored, allowlist in localStorage | Web | mail-images-web-settings |
| Back buttons hardcoded to parent route (e.g. mail → `/mail`) | Web | web-back-navigation |
| Mail search lost after opening a match; no previous/next | Web | web-mail-search-nav |
| Same, plus back to origin from every entry point | Android | and-mail-search-nav |
| Synced settings not read at all (theme, week start, labels) | Android | and-user-settings |

### GTD
| Gap | Client | Task |
|---|---|---|
| No create/edit/complete/drop/delete project, no description editing | Android | and-projects-manage |
| No adding a task directly to a project, no reorder | Android | and-project-tasks |
| Inbox rows: no complete, no open; New view task opens read-only sheet (SPEC §5.3) | Android | and-inbox-rows |
| Task detail: scheduled, tickler, recurrence, waiting-since read-only; lists limited; checklist reorder | Android | and-task-detail-dates |
| No convert task → note / event (detail and inbox processing) | Android | and-task-convert |
| Links read-only, no add/remove/create linked | Android | and-links-panel |
| Next: no due/area/project filter, no saved presets | Android | and-next-filters |
| Today: no events, no habits | Android | and-today-events-habits |
| No Scheduled list | Android | and-scheduled-list |
| Weekly review read-only | Android | and-review-flow, and-review-steps |
| No delete/complete in task detail, no undo, checklist rename, notes not rendered | Web | web-task-actions |
| No project delete; project detail shows open Next tasks only | Web | web-projects-plus |
| Reminders on tasks (SPEC §4.6) | Both | task-reminders |
| Linked items on projects/files (SPEC §4.3), follow-up by email (SPEC §5.3) | Both | gtd-links-everywhere |
| Weekly review reminder weekday (SPEC §8.6) | Both | review-reminder |

### Other areas
| Gap | Client | Task |
|---|---|---|
| Dashboard: only task items tappable, no review button, no layout editing | Android | and-dashboard-actions |
| Capture: no syntax preview, no "save and add another", no attachment picker | Android | and-capture-improve |
| Habits: no create/edit/archive; check is all-or-nothing | Android | and-habits-manage |
| Journal: today only; no mood, tags, calendar, on this day | Android | and-journal-full |
| Mail: no unread/flag/move, spam only in read view, no multi-select | Android | and-mail-actions |
| Mail: attachments only listed by name | Android | and-mail-attachments |
| Compose: no recipient autocomplete, no attach from Files | Android | and-mail-compose-plus |
| Mail: no create note, no links panel | Android | and-mail-note-links |
| Calendar: no task overlay (SPEC §6.3), no month view | Android | and-cal-overlay-month |
| Events: no reminders, attendees, invitations, time zone, links | Android | and-cal-event-fields |
| Contacts view-only | Android | and-contacts-edit |
| Notes: no search, pin, archive, delete | Android | and-notes-list |
| Note editor: no tags, checklist toggle, `[[` links, revisions, links panel | Android | and-notes-editor |
| Files: no new folder/rename/move/delete | Android | and-files-manage |
| Files: no in-app preview (SPEC §8.2 PdfRenderer) | Android | and-files-preview |
| Bookmarks: add only via share, no read-later toggle, no reader; bookmark → inbox (SPEC §4.5) | Android | and-bookmarks-plus |
| Forced TOTP re-enrollment unsupported (user stuck after restore) | Android | and-totp-reenroll |
| Read view: no unread/flag/move; shortcuts `r a f c` missing (SPEC §7.3) | Web | web-mail-read-actions |
| Notes: no delete, tags only at creation | Web | web-notes-delete-tags |
| Bookmarks: no search (SPEC §8.4), no edit, no undo, bookmark → inbox | Web | web-bookmarks-search-edit |
| Search type filter, URL → read later in capture, Android download link | Web | web-small-gaps |
| Respond to invitations (SPEC §6.2, API only) | Both | cal-invitation-respond |
| Load mail older than the sync window (SPEC §7.2, API only) | Both | mail-load-older-ui |
| Choose sending identity (SPEC §7.3), resume drafts | Both | mail-identity-drafts |

### Not planned (yet)
- Conversation (thread) read view on either client; event status/transparency/conference URL fields; renaming or
  deleting calendar sources; "empty trash"; Android persistent-notification capture (optional in SPEC §4.5).
- Platform-specific features stay where they are: Android share target, widget, tile, offline queue, UnifiedPush;
  web keyboard shortcuts and drag-to-create in the calendar.
