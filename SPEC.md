# My Cockpit — Product & Technical Specification

Status: v1.0 (2026-10-20) · Source of truth for implementation by AI agents.
Origin: `BRAINSTORM.md` + Q&A session.

---

## 1. Vision

A personal, self-hosted "cockpit for almost everything": one place for tasks (GTD), calendar,
email, notes, files, contacts, bookmarks, journal and habits — accessible from a browser and a
native Android app, running on the owner's Linux server.

### Guiding principles

1. **Single user.** Exactly one owner account. No sharing, no roles, no multi-tenancy.
2. **Capture anywhere, fast.** Quick capture must be reachable from every screen (web hotkey,
   Android share target, widget, notification) and work offline on Android.
3. **Everything is linkable.** Any item (task, note, email, event, file, contact, bookmark,
   journal entry) can link to any other item. This is the cockpit's core value.
4. **Data ownership.** A full backup is a single zip that is human readable *and* restorable.
5. **Boring, robust technology.** Prefer well-known libraries and standards (IMAP, SMTP,
   iCalendar, vCard, Markdown) over custom formats.
6. **Incremental delivery.** Each phase (§13) produces a usable, deployable product.

### Key decisions (from Q&A)

| Topic | Decision |
|---|---|
| Users | Single user |
| Offline | Offline *capture only* on Android; everything else online |
| Email | Full client: read, mark, move, delete, compose, reply, forward |
| Email providers | Generic IMAP/SMTP, Gmail/Google Workspace (OAuth2), Microsoft 365/Outlook (OAuth2) |
| Calendar | Own calendars + **two-way sync** with external calendars (Google, Microsoft, CalDAV) |
| Android | Native Kotlin + Jetpack Compose |
| Files | Web drive (folders, upload/download, preview, linking). No WebDAV/sync client |
| Notes | Markdown with pasted/dropped images |
| Backup | On demand only (download zip / upload zip to restore). Mail content excluded |
| Hosting | Public HTTPS behind reverse proxy, strong auth (password + TOTP and/or passkey) |
| Extras in scope | Contacts, Weekly Review wizard, Bookmarks/read-later, Journal & habits |

---

## 2. Architecture

```
            ┌──────────────┐        ┌───────────────────┐
 Browser ──►│ Angular SPA  │──┐     │ Android (Compose) │
            └──────────────┘  │     └─────────┬─────────┘
                              │ HTTPS (REST+SSE)
                        ┌─────▼─────────────────▼─────┐
                        │ Caddy (TLS, reverse proxy)  │
                        └─────────────┬───────────────┘
                        ┌─────────────▼───────────────┐
                        │ cockpit-server (Spring Boot)│
                        │  modules: gtd, calendar,    │
                        │  mail, notes, files, ...    │
                        └──┬──────────┬────────────┬──┘
                           │          │            │
                     PostgreSQL   Data dir     External: IMAP/SMTP,
                                (files, blobs) Google APIs, MS Graph, CalDAV
```

- **Modular monolith.** One Spring Boot application, one database, internally split into
  modules with clear package boundaries. Modules communicate via application services and
  Spring application events, never via each other's repositories.
- **REST JSON API** (`/api/v1/...`) documented with OpenAPI (springdoc). The OpenAPI spec is
  the contract for both clients; client models are generated from it.
- **Server-Sent Events** (`/api/v1/events`) push change notifications (e.g. "new mail",
  "task changed", "reminder due") to connected web clients.
- **Android push:** Firebase Cloud Messaging is *not* used (self-hosted, no Google dependency
  required). Android uses WorkManager periodic sync (15 min) plus SSE while the app is in the
  foreground; reminders are scheduled locally via `AlarmManager` from synced reminder data.
  (Optional later: UnifiedPush/ntfy.)

### 2.1 Tech stack

| Layer | Choice |
|---|---|
| Server language | Kotlin 2.x on JVM 21 |
| Framework | Spring Boot 3.5+ (Web MVC, Security, Data JPA or jOOQ — **use Spring Data JPA + Hibernate**, Validation, Scheduling) |
| DB | PostgreSQL 16+, migrations with **Flyway** |
| Search | PostgreSQL full-text search (`tsvector` + GIN), `pg_trgm` for fuzzy titles |
| Mail | Eclipse Angus Mail (Jakarta Mail) for IMAP/SMTP incl. XOAUTH2, IMAP IDLE |
| Calendar | ical4j (iCalendar/RRULE), Google Calendar API v3 (REST), Microsoft Graph (REST), CalDAV via ical4j-connector or plain HTTP REPORT requests |
| Contacts | ez-vcard; Google People API; Microsoft Graph contacts |
| Markdown | flexmark-java (server-side rendering for export/search text extraction) |
| Auth | Spring Security, session cookie (web), bearer tokens (Android), TOTP (RFC 6238), WebAuthn/passkeys (webauthn4j / Spring Security WebAuthn) |
| Build | Gradle (Kotlin DSL), monorepo |
| Web client | Angular 20+ (standalone components, signals, new control flow), Angular Material or PrimeNG — **use Angular Material + CDK**, CodeMirror 6 for Markdown editing |
| Android | Kotlin, Jetpack Compose, Material 3, Hilt, Retrofit/OkHttp + kotlinx.serialization, Room (capture queue + read cache), WorkManager, DataStore |
| Tests | JUnit 5, Kotest assertions, Testcontainers (Postgres, GreenMail for IMAP/SMTP, Radicale for CalDAV), MockK; Angular: Vitest/Jest + Playwright e2e; Android: JUnit + Compose UI tests |
| Deployment | Docker Compose: `caddy`, `cockpit-server`, `postgres` |

---

## 3. Repository layout

```
my-cockpit/
├── SPEC.md                 # this document
├── AGENTS.md / CLAUDE.md   # agent working rules (see §14)
├── server/                 # Spring Boot app (Gradle)
│   └── src/main/kotlin/ch/sonensei/cockpit/
│       ├── core/           # auth, users, items, links, search, events, settings, crypto
│       ├── gtd/
│       ├── calendar/
│       ├── mail/
│       ├── notes/
│       ├── files/
│       ├── contacts/
│       ├── bookmarks/
│       ├── journal/        # journal + habits
│       ├── review/         # weekly review
│       └── backup/
├── web/                    # Angular app
├── android/                # Android app (Gradle)
├── api/                    # openapi.yaml (generated from server, committed)
├── deploy/                 # docker-compose.yml, Caddyfile, .env.example, scripts
└── docs/                   # ADRs, backup format reference
```

Base package: `ch.sonensei.cockpit` (adjust if desired). Each module package has `api/` (controllers,
DTOs), `domain/` (entities, services), `infra/` (external integrations, repositories).

---

## 4. Cross-cutting concerns

### 4.1 Authentication & security

- One user, created on first start via a **setup flow** (only available while no user exists):
  username, password (Argon2id hashed), then mandatory TOTP enrollment (show QR + 10
  recovery codes). Passkeys can be added afterwards and then used as an alternative to
  password+TOTP.
- **Web:** session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`), CSRF protection via
  double-submit cookie (Angular's `XSRF-TOKEN` convention). Session lifetime 30 days sliding,
  "remember device" option.
- **Android:** login (password+TOTP or passkey via Credential Manager) returns a long-lived
  refresh token (stored in EncryptedSharedPreferences / Keystore) and short-lived access
  token (15 min). Devices are listed in settings and can be revoked.
- Rate limiting and lockout on login (e.g. 5 failures → exponential backoff).
- **Secrets at rest:** external credentials (IMAP passwords, OAuth refresh tokens) are
  encrypted with AES-256-GCM using a master key from the environment
  (`COCKPIT_MASTER_KEY`), never stored in plaintext, never returned by the API.
- Security headers via Caddy + Spring (CSP, HSTS, X-Content-Type-Options, frame-ancestors).
- **HTML email is untrusted:** sanitized server-side (jsoup Safelist + custom rules), rendered
  in a sandboxed `<iframe sandbox>` with CSP; remote images blocked by default with a
  "load images" toggle (optionally per-sender allowlist), images proxied through the server.

### 4.2 Identifiers, timestamps, conventions

- All entities use **UUIDv7** primary keys (time-ordered), generated server-side; Android
  generates UUIDv7 for offline captures (idempotent create).
- Every entity has `createdAt`, `updatedAt` (UTC `timestamptz`), and `version` (optimistic
  locking; API returns `ETag`, updates require `If-Match` → `409/412` on conflict).
- Soft delete via `deletedAt` for user content (tasks, notes, files, contacts, bookmarks,
  journal entries); a **Trash** view lists deleted items, purged after 30 days.
- Time zone: user setting (default `Europe/Zurich`); store instants in UTC, store all-day
  dates as `LocalDate`, store event time zone ids for recurrence correctness.
- API: JSON camelCase, ISO-8601 dates, cursor pagination (`?cursor=&limit=`), errors as
  RFC 9457 Problem Details.
- UI language: English first; all UI strings externalized (i18n-ready, German later).

### 4.3 Universal items & links

A central `item_ref` concept lets any module reference any other:

- `ItemType` enum: `TASK, PROJECT, NOTE, EVENT, EMAIL, FILE, FOLDER, CONTACT, BOOKMARK,
  JOURNAL_ENTRY, HABIT`.
- Table `link(id, source_type, source_id, target_type, target_id, label?, created_at)`;
  links are bidirectional in presentation (query both directions).
- For emails, the link target id is the cockpit's `mail_message.id`, which stores
  account + folder + Message-ID header so the link survives moves (re-resolve by
  Message-ID if UID changes).
- Each module implements an `ItemResolver` (type → title, subtitle, icon, deep-link URL,
  exists?) so a generic "Linked items" panel can render links from any item.
- Every detail view in web and Android shows a **Linked items** panel with "add link"
  (search-as-you-type over all types) and "create linked…" (e.g. *new task from this email*).
- Notes support inline links `[[type:id]]` or picked via `[[` autocomplete; they are rendered
  as chips and also persisted into the `link` table.

### 4.4 Global search

- Single endpoint `GET /api/v1/search?q=&types=` across tasks, projects, notes, events,
  contacts, bookmarks, journal, file names (+ text of PDFs/text files, optional later), and
  **cached mail headers + bodies** (see §6.3).
- Each module maintains a `search_document(item_type, item_id, title, body, tsv, updated_at)`
  row via application events; ranking by `ts_rank` + recency; results grouped by type.
- Web: command palette (`Ctrl+K`) combining search + commands ("new task", "go to inbox").

### 4.5 Quick capture

- Web: global hotkey (`Q` when not in an input, plus `Ctrl+Shift+Space`) and a persistent
  "+" button open a capture dialog: one text field, optional notes, optional attachment
  (paste/drop), Enter = save to Inbox. Light syntax parsing (optional, visible preview):
  `#project`, `@context`, `!today`/`!tomorrow`/`!2026-11-03` (due), `^waiting`.
- Android: share target (text, URLs, images, files), home-screen widget, quick-settings
  tile, persistent-notification action (optional), app shortcut. Works offline (§9.3).
- Captures from emails ("→ Inbox" button), from the calendar, and from bookmarks also
  create inbox items with a link to the origin.

### 4.6 Reminders & notifications

- `reminder(id, item_type, item_id, remind_at, channel, fired_at?)` — generic for tasks,
  events, ticklers, habits.
- Server scheduler fires due reminders: SSE event to web (browser Notification API, with
  permission), and Android schedules local alarms from synced reminders.
- Optional: Web Push (VAPID) for notifications when no tab is open — phase 7.

### 4.7 Settings

Key-value user settings (time zone, week start, default views, energy/creativity labels,
contexts list, theme light/dark/system, mail image policy, etc.), exposed via
`/api/v1/settings`.

---

## 5. Module: GTD (Tasks & Projects)

### 5.1 Concepts

| Concept | Implementation |
|---|---|
| Inbox | Tasks with `list = INBOX` |
| Next Actions | `list = NEXT` |
| Waiting For | `list = WAITING`, with `waitingFor` (contact id or free text), `waitingSince` |
| Someday/Maybe | `list = SOMEDAY` |
| Scheduled | `list = SCHEDULED` with a `scheduledDate` (appears in Next on that date) |
| Reference | Not a task list — "move to reference" converts the item into a **Note** |
| Tickler | `ticklerDate`: task is hidden; on that date it moves back to **INBOX** (daily job at 00:05 local time + on-read check) |
| Projects | Separate entity; tasks may belong to a project; a project has an ordered task list and a designated **next step** |
| Areas of focus | Optional grouping of projects (e.g. Work, Family, Health) |
| Contexts | Tags with `@` semantics (`@home`, `@computer`, `@phone`, `@errands`), user-defined |
| Energy | `LOW / MEDIUM / HIGH` |
| Creativity | `ROUTINE / MIXED / CREATIVE` (labels configurable) |
| Time estimate | Minutes (5, 15, 30, 60, 120+ quick picks) |
| Done | `completedAt`; done tasks hidden by default, "Logbook" view |

### 5.2 Data model

```
task(
  id, title, notes_md, list [INBOX|NEXT|WAITING|SOMEDAY|SCHEDULED],
  project_id?, position (for ordering within project/list),
  energy?, creativity?, time_estimate_min?, contexts (m:n via task_context),
  due_date?, due_time?, scheduled_date?, tickler_date?,
  waiting_for_contact_id?, waiting_for_text?, waiting_since?,
  recurrence_rule? (RRULE, e.g. FREQ=WEEKLY;BYDAY=MO), recurrence_mode [FIXED|AFTER_COMPLETION]?,
  completed_at?, deleted_at?, created_at, updated_at, version
)
project(
  id, title, description_md, area_id?, status [ACTIVE|ON_HOLD|SOMEDAY|COMPLETED|DROPPED],
  next_task_id?, due_date?, review_date?, completed_at?, ...
)
area(id, title, position)
context(id, name, color?, position)
checklist_item(id, task_id, text, done, position)   -- simple sub-steps inside a task
```

Rules:
- An **active project without an open next task** is flagged ("stalled") in project list,
  dashboard and weekly review.
- Completing a project's next task prompts: "Choose next step" (pick an open task of the
  project or create one).
- Completing a recurring task creates the next occurrence (FIXED: next RRULE date;
  AFTER_COMPLETION: relative to completion date) — no infinite pre-generation.
- Moving a task out of INBOX is "processing"; tasks may stay in INBOX with partial data.
- Attachments: via links to files (upload in task creates file in `/Attachments/Tasks/`).

### 5.3 UI

- **Inbox processing mode:** one item at a time, keyboard driven. Actions:
  `Do it (<2 min → done)`, `Next`, `Waiting`, `Someday`, `Schedule`, `Tickler`, `To project`
  (existing/new), `To reference (note)`, `To calendar (event)`, `Trash`. Inline setting of
  energy, creativity, contexts, estimate. Shows "x items left".
- **Next Actions view** with filters: context, energy, creativity, max time estimate,
  area/project, due. Saved filter presets ("Low energy @home ≤ 15 min").
- **Today view:** due/overdue tasks, scheduled for today, today's calendar events, habits due.
- Project list (grouped by area, stalled highlighted), project detail (ordered tasks,
  drag reorder, next-step marker, notes, linked items).
- Waiting For list sorted by `waitingSince`, with "follow up" action (creates task or
  drafts email to the contact).
- Logbook (completed), Trash.

### 5.4 API (sketch)

```
GET/POST        /api/v1/tasks            ?list=&projectId=&context=&energy=&creativity=&maxMinutes=&due=
GET/PATCH/DELETE /api/v1/tasks/{id}
POST            /api/v1/tasks/{id}/complete | /uncomplete | /move {list, projectId?, ...}
POST            /api/v1/tasks/{id}/convert-to-note | /convert-to-event
GET/POST        /api/v1/projects, /api/v1/projects/{id}, POST /{id}/next-step
GET/POST        /api/v1/areas, /api/v1/contexts
POST            /api/v1/capture          {id?, text, notes?, fileIds?}  (idempotent by id)
```

---

## 6. Module: Calendar

### 6.1 Concepts

- **Calendar sources:** `LOCAL` (stored in cockpit), `GOOGLE` (OAuth2, Calendar API v3),
  `MICROSOFT` (OAuth2, Graph), `CALDAV` (URL + credentials, e.g. Nextcloud, iCloud with app
  password). Each source contains one or more **calendars** (name, color, visible, read-only
  flag, default for new events).
- **Events:** title, description, location, start/end (time zone aware) or all-day, RRULE +
  EXDATE + overrides (recurrence exceptions), attendees (email + name + status),
  reminders (minutes before), status, transparency (busy/free), conference URL, linked items.
- Read-only ICS subscription URLs supported as a simple source type `ICS_FEED`.

### 6.2 Two-way sync

- Server stores a local copy of external events: `calendar_event` with `source_calendar_id`,
  `external_id`, `external_etag`, `sync_state`.
- **Google:** incremental sync with `syncToken`; push notifications via watch channels
  are optional (requires public HTTPS — available), fallback poll every 5 min. Writes go
  directly to Google API, then local copy is updated from the response.
- **Microsoft:** Graph delta queries (`/calendarView/delta`), poll every 5 min.
- **CalDAV:** `sync-collection` REPORT (RFC 6578) when supported, else ctag/etag comparison,
  poll every 10 min.
- **Conflict policy:** remote is source of truth; local edits are written through
  immediately (no offline edits), and a `412 Precondition Failed` from the remote triggers
  re-fetch and a user-visible "event changed remotely, please retry".
- Recurring events: edit "this occurrence" / "this and following" / "all" — map to the
  provider's semantics (Google: instance edits, split series for "following").
- Invitations: show attendee status; responding to invitations (accept/decline) supported for
  Google/Microsoft via API; sending invites for LOCAL events via email (iTIP REPLY/REQUEST)
  is phase 7.

### 6.3 UI

- Views: day, week (default), month, agenda list. Drag to create/move/resize.
- Overlay toggle for calendars; tasks with due dates and ticklers shown as an optional
  overlay in day/week views.
- Event detail with linked items (e.g. meeting notes note, follow-up tasks).
- "Create task from event" and "Create note for event" (pre-filled title + date).

### 6.4 API (sketch)

```
GET/POST   /api/v1/calendar/sources   (+ OAuth connect flows: /api/v1/oauth/{provider}/start, /callback)
GET/PATCH  /api/v1/calendar/calendars
GET        /api/v1/calendar/events?from=&to=&calendarIds=   (expanded occurrences)
POST/PATCH/DELETE /api/v1/calendar/events/{id}?scope=THIS|FOLLOWING|ALL&occurrence=
POST       /api/v1/calendar/sources/{id}/sync
```

---

## 7. Module: Mail

### 7.1 Accounts

- Account types: `IMAP_SMTP` (host, port, TLS mode, username, password/app password),
  `GOOGLE` (OAuth2, IMAP/SMTP with XOAUTH2 — Gmail API not required), `MICROSOFT`
  (OAuth2, IMAP/SMTP with XOAUTH2 for Outlook/M365).
- OAuth apps: the owner registers their own Google Cloud and Azure app; client id/secret
  configured via environment. Setup guide in `docs/oauth-setup.md`. The same Google/
  Microsoft connection is reused for mail, calendar and contacts (combined scopes, one
  `oauth_connection` per provider account).
- Per account: display name, email address(es)/identities, signature (Markdown → HTML),
  folder role mapping (Inbox, Sent, Drafts, Trash, Archive, Spam — auto-detected via
  SPECIAL-USE, overridable).

### 7.2 Sync model

- Server-side IMAP client; clients never talk to IMAP directly.
- **Cache:** folder list, and per folder message *headers* (UID, Message-ID, from, to, cc,
  subject, date, flags, size, has-attachments, snippet, thread references) for a
  configurable window (default: last 12 months; older loaded on demand when scrolling).
  Bodies are fetched on demand and cached (LRU, size-bounded, default 2 GB) for search and
  fast reopening.
- Uses UIDVALIDITY/UIDNEXT, CONDSTORE/QRESYNC when available; IMAP IDLE on INBOX of each
  account (connection pool, reconnect with backoff); other folders polled every 5 min.
- New mail → SSE event + optional notification (configurable per account).
- Threading: conversations by `References`/`In-Reply-To`, fallback normalized subject;
  view can toggle threaded/flat.

### 7.3 Features

- Unified inbox across accounts + per-account folder tree.
- List: sender, subject, snippet, date, flags, attachment icon; multi-select.
- Actions: mark read/unread, flag/star, move to folder (same account), archive (to Archive
  role folder), delete (to Trash; permanent delete in Trash), mark as spam.
- Keyboard shortcuts (Gmail-like: `j/k`, `e` archive, `#` delete, `r` reply, `a` reply all,
  `f` forward, `c` compose, `u` unread, `v` move).
- Read view: sanitized HTML (§4.1) or plain text, attachments list (download, preview,
  **save to Files**), "load remote images".
- **Compose / reply / reply-all / forward:** rich text editor (TipTap-based or Angular
  Material-compatible editor) producing HTML + plain-text alternative; choose sending
  identity; attachments (upload or pick from Files); drafts saved to the account's Drafts
  folder (autosave every 10 s); sent mail appended to Sent folder (unless provider does it,
  e.g. Gmail). Recipient autocomplete from Contacts + recent correspondents.
- GTD integration: "→ Inbox task", "→ Waiting For" (from sent mails: waiting for reply from
  recipient), "Create note", link email to project. Linked tasks show a link back to the mail.
- Search: server-side over cached headers/bodies; option "search on server" (IMAP SEARCH)
  for older mail.

### 7.4 API (sketch)

```
GET/POST/PATCH/DELETE /api/v1/mail/accounts
GET   /api/v1/mail/accounts/{id}/folders
GET   /api/v1/mail/messages?accountId=&folderId=&unified=true&cursor=&q=
GET   /api/v1/mail/messages/{id}        (headers + sanitized body + attachments meta)
GET   /api/v1/mail/messages/{id}/attachments/{partId}
POST  /api/v1/mail/messages/actions     {ids, action: READ|UNREAD|FLAG|UNFLAG|MOVE|ARCHIVE|DELETE|SPAM, targetFolderId?}
POST  /api/v1/mail/drafts, PUT /api/v1/mail/drafts/{id}, POST /api/v1/mail/drafts/{id}/send
```

---

## 8. Other modules

### 8.1 Notes

- Small Markdown snippets: `note(id, title?, body_md, pinned, archived, tags, created_at, ...)`.
  Title optional (derived from first line when empty).
- Editor: CodeMirror 6 with Markdown highlighting + live preview toggle/split; paste/drop
  image → upload to Files (`/Attachments/Notes/`) → inserts `![](cockpit-file:{fileId})`,
  rendered via authenticated file URL. Checklists `- [ ]` are toggleable in preview.
- Views: grid/list of cards (masonry), pinned first, filter by tag, search.
- Note history: keep last 20 versions (`note_revision`) for undo of accidental edits.

### 8.2 Files (web drive)

- Folder tree + file metadata in DB (`folder`, `file(id, folder_id, name, mime, size,
  sha256, storage_key, ...)`); blobs on disk under `${COCKPIT_DATA_DIR}/blobs/ab/cd/<uuid>`.
- Upload (multi-file, drag & drop, resumable via chunked upload for files > 50 MB, max size
  configurable, default 5 GB), download, rename, move, delete (trash), new folder,
  download folder as zip.
- Preview: images (thumbnails generated server-side, e.g. via Thumbnailator), PDF (pdf.js in
  web, Android PdfRenderer), text/Markdown, audio/video via HTML5.
- Special folders: `/Attachments/{Tasks,Notes,Mail,Journal}` auto-managed.
- Storage quota display (disk usage).

### 8.3 Contacts

- `contact(id, display_name, given/family name, emails[], phones[], addresses[],
  organization, title, birthday, notes_md, photo_file_id?, source, external_id, etag)`.
- Sources: LOCAL, GOOGLE (People API, two-way), MICROSOFT (Graph, two-way), CARDDAV (later).
- Contact detail shows: linked items, Waiting-For items for this contact, recent emails
  from/to them, upcoming events with them.
- Birthdays shown as a virtual read-only calendar.
- vCard import/export.

### 8.4 Bookmarks / read-later

- `bookmark(id, url, title, description, tags, read_later, read_at?, favicon, snapshot_file_id?)`.
- On save, server fetches title/description/favicon (OpenGraph); optional "save readable
  snapshot" (Readability-style extraction → Markdown/HTML stored as file).
- Android share of a URL offers: "Inbox" or "Read later".
- Views: read-later queue, all bookmarks by tag, search.

### 8.5 Journal & habits

- **Journal:** one entry per day (`journal_entry(date, body_md, mood?, tags)`), Markdown,
  images like notes. "Today" opens/creates today's entry; calendar heatmap of entries;
  "on this day" in previous years.
- **Habits:** `habit(id, name, schedule (daily / N times per week / specific weekdays),
  target_count, reminder_time?, archived)`, `habit_check(habit_id, date, count)`.
  Today view shows habits due with one-tap check; streaks and simple stats (completion %
  last 30 days).

### 8.6 Weekly Review wizard

Guided, resumable steps (progress persisted in `weekly_review(id, started_at,
completed_at, step)`):

1. **Get clear:** process Inbox to zero (embedded inbox processing), loose captures from
   mail ("flagged mails").
2. **Review past calendar** (last 7 days): anything to capture? (quick-add tasks).
3. **Review upcoming calendar** (next 14 days).
4. **Waiting For:** each item — still waiting / follow up / done.
5. **Projects:** each active project — has next step? (stalled ones first), status change.
6. **Next Actions:** stale (> 30 days) items — keep / someday / delete.
7. **Someday/Maybe:** activate / keep / delete.
8. **Habits & journal reflection** (optional free text saved as journal entry).

Dashboard shows "Last weekly review: N days ago" with a reminder (configurable weekday).

### 8.7 Dashboard (home)

Configurable widgets: Today (tasks + events + habits), Inbox count (tasks + unread mail),
Stalled projects, Waiting For overdue, Upcoming events, Read-later queue, Pinned notes,
Last weekly review.

---

## 9. Clients

### 9.1 Web (Angular)

- Layout: left navigation (Dashboard, Inbox, Next, Projects, Waiting, Someday, Calendar,
  Mail, Notes, Files, Contacts, Bookmarks, Journal, Habits, Review, Settings), top bar with
  search (`Ctrl+K`) and capture button. Responsive down to tablet; mobile use is the
  Android app's job but web must remain usable on phones.
- Light/dark theme, keyboard-first (shortcut overview with `?`).
- State: Angular signals + services per feature; HTTP via generated OpenAPI client;
  SSE service dispatches change events to invalidate/refresh feature stores.
- Feature folders mirror server modules; lazy-loaded routes.

### 9.2 Android (Kotlin + Compose)

- Minimum SDK 29 (Android 10), target latest.
- Architecture: MVVM + unidirectional data flow, Hilt DI, Retrofit client generated or
  hand-written from OpenAPI, Room for local cache + capture queue.
- Scope for v1 Android (prioritized):
  1. Login (password+TOTP, passkey), server URL setting.
  2. Quick capture: share target (text/URL/images/files), widget, QS tile, app shortcut.
  3. Inbox + processing, Next Actions with filters, Today, Projects, Waiting, Someday.
  4. Calendar (agenda + day/week), event create/edit.
  5. Mail (unified inbox, read, actions, compose/reply).
  6. Notes (view/edit Markdown, add photo from camera/gallery).
  7. Files (browse, download/open, upload), Contacts (view, call/mail intents).
  8. Bookmarks, journal, habits (check-off), weekly review (read-only first).
- Notifications: reminders via AlarmManager (exact alarms permission), new-mail summary from
  periodic sync.

### 9.3 Offline capture (Android)

- Capture always writes to a local Room `pending_capture` table first (client UUIDv7,
  text, notes, local attachment URIs copied into app storage, created_at).
- WorkManager job (constraint: network connected) uploads attachments, then calls
  `POST /api/v1/capture` with the client id (idempotent: duplicate id → 200 with existing).
- UI shows pending count and sync state; failures retried with backoff.
- Read cache: last-loaded lists (Inbox, Next, Today, agenda) are cached in Room and shown
  read-only with an "offline" banner when the server is unreachable.

---

## 10. Backup & restore

### 10.1 Trigger

- On demand only: Settings → Backup → "Create backup" (server builds zip asynchronously,
  progress via SSE, then download). Optional passphrase for secret encryption (see below).
- Restore: Settings → Restore → upload zip → validation report (counts per type, format
  version, warnings) → confirm → **full replace** of all data (current state is
  automatically backed up to `${COCKPIT_DATA_DIR}/pre-restore/` first).
- Also available via CLI for disaster recovery on a fresh install:
  `java -jar cockpit-server.jar --restore=/path/backup.zip` (and setup flow is skipped
  if the backup contains the user).

### 10.2 Zip format (format version 1)

Dual representation: **canonical JSON** (used for restore, lossless) + **human-readable
mirrors** (Markdown/ICS/vCard, for browsing and use without the app). Restore uses only
`data/`; the mirrors are regenerated, never read.

```
cockpit-backup-2026-10-20T1530Z.zip
├── README.md                  # explains structure, format version, how to restore
├── manifest.json              # formatVersion, appVersion, createdAt, counts, sha256 per file
├── data/                      # canonical, one JSON (or JSONL for large sets) per entity type
│   ├── user.json              # username, password hash, TOTP secret (encrypted, see below), settings
│   ├── tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl
│   ├── notes.jsonl, note_revisions.jsonl
│   ├── calendar_sources.json, calendars.json, events.jsonl   # LOCAL events fully; external ones as cache (optional)
│   ├── mail_accounts.json     # config only, secrets encrypted or omitted; no messages
│   ├── mail_message_refs.jsonl# Message-ID refs needed to keep links to emails
│   ├── contacts.jsonl, bookmarks.jsonl, journal.jsonl, habits.json, habit_checks.jsonl
│   ├── links.jsonl, reminders.jsonl, weekly_reviews.json
│   └── files.jsonl, folders.json
├── files/                     # actual file blobs, in the original folder tree with original names
│   └── Documents/tax/2025.pdf ...   (name clashes resolved with " (2)"; mapping in files.jsonl)
└── readable/
    ├── tasks/inbox.md, next.md, waiting.md, someday.md, logbook.md
    ├── projects/<area>/<project-title>.md     # description + task list with checkboxes
    ├── notes/<yyyy-mm-dd>-<slug>.md           # front-matter (id, tags, links) + body; images point to ../../files/...
    ├── journal/<yyyy>/<yyyy-mm-dd>.md
    ├── calendar/<calendar-name>.ics           # LOCAL calendars
    ├── contacts/contacts.vcf
    ├── bookmarks.html                         # Netscape bookmark format (importable in browsers)
    └── habits.csv
```

- **Secrets:** if a backup passphrase is given, secrets (IMAP passwords, OAuth refresh
  tokens, TOTP secret) are encrypted with a key derived via Argon2id from the passphrase
  and stored in `data/secrets.enc.json`. Without passphrase, secrets are omitted; after
  restore, accounts show "reconnect required" and the user re-enrolls TOTP on first login.
  The password hash is always included.
- External calendar/contact data is *not* required in the backup (re-synced), but the link
  targets (external ids) are kept so links survive.
- Restore must be tested in CI: create data → backup → wipe → restore → compare (§12).
- Each module implements `BackupContributor { export(writer); import(reader); readable(writer) }`;
  the backup service orchestrates in dependency order and validates format versions
  (migration hooks for future format versions).

---

## 11. Deployment & operations

- `deploy/docker-compose.yml`: `caddy` (automatic Let's Encrypt TLS for `COCKPIT_DOMAIN`),
  `server` (image built by Gradle `bootBuildImage` or Dockerfile, serves Angular build as
  static resources under `/`), `postgres` (volume). Data dir volume for blobs.
- Configuration via env: `COCKPIT_DOMAIN`, `COCKPIT_DATA_DIR`, `COCKPIT_MASTER_KEY`,
  `SPRING_DATASOURCE_*`, `GOOGLE_CLIENT_ID/SECRET`, `MS_CLIENT_ID/SECRET/TENANT`,
  `SMTP_*` (not needed — per-account).
- Health: Spring Actuator `/actuator/health` (internal only), structured JSON logging,
  log of sync job status visible in Settings → "Integrations status" (last sync, errors).
- Since backups are on-demand only, the README must recommend an additional host-level
  backup (e.g. restic of the data dir + `pg_dump`) — provide `deploy/host-backup.sh` as an
  optional example.
- Android APK: built by CI, distributed via direct download from the server
  (`/download/android`) or sideload; no Play Store required.
- CI: GitHub Actions (or similar): build + test server, web, android; publish Docker image.

---

## 12. Testing strategy

- **Server:** unit tests for domain logic (tickler, recurrence, next-step rules, inbox
  processing, RRULE expansion, sanitization); integration tests with Testcontainers:
  Postgres, **GreenMail** (IMAP/SMTP), **Radicale** (CalDAV/CardDAV); Google/Microsoft APIs
  mocked with WireMock using recorded responses.
- **Backup round-trip test:** seed every entity type → export → wipe DB + data dir → import
  → assert equality (deep compare of canonical JSON) + files' sha256.
- **Web:** component tests for key features; Playwright e2e for: setup/login with TOTP,
  quick capture, inbox processing, project next-step, note with pasted image, file upload,
  backup/restore.
- **Android:** ViewModel unit tests, Room tests for capture queue, Compose UI tests for
  capture and inbox processing.
- Definition of done per feature: API documented in OpenAPI, tests green, works in both
  light/dark theme, keyboard accessible (web), included in backup/restore.

---

## 13. Delivery phases

Each phase ends with a deployable version. Acceptance criteria are minimal must-haves.

**Phase 0 — Foundation**
Monorepo, Gradle builds, Docker Compose deploy, Postgres + Flyway, setup flow, login with
password+TOTP, sessions, settings, SSE channel, item/link/search core, Angular shell with
navigation, theme, command palette skeleton, CI.
✔ Can deploy to the server via HTTPS, set up the account, log in with TOTP.

**Phase 1 — GTD core**
Tasks, projects, areas, contexts, all lists, inbox processing, tickler job, recurrence,
quick capture (web), Today and Next views with filters, Logbook, Trash, links between
tasks/projects, search for tasks.
✔ Full GTD workflow usable in the browser.

**Phase 2 — Notes, Files, Backup**
Notes with Markdown + pasted images, files web drive with previews, linking notes/files to
tasks, backup export + restore (UI + CLI) incl. round-trip test.
✔ Backup of everything so far restores into a fresh install identically.

**Phase 3 — Android v1 (capture + GTD)**
Login, offline quick capture (share target, widget, tile), inbox processing, Next/Today/
Projects/Waiting/Someday, notes view/edit, reminders.
✔ Capture works in airplane mode and syncs when back online.

**Phase 4 — Calendar**
Local calendars, Google/Microsoft OAuth connections, two-way sync (Google, MS, CalDAV),
ICS feeds, recurrence editing, calendar UI (web + Android agenda/day/week), task overlay.
✔ Event created in cockpit appears in Google Calendar within seconds and vice versa ≤ 5 min.

**Phase 5 — Mail**
Accounts (IMAP/SMTP, Google, Microsoft), header sync + IDLE, unified inbox, read view with
sanitization, actions, compose/reply/forward with drafts and attachments, mail→task/
waiting/note, mail search; Android mail.
✔ Daily email triage possible without another mail client.

**Phase 6 — Contacts, Bookmarks, Journal & Habits, Weekly Review, Dashboard**
All §8.3–8.7 features on web; Android: bookmarks share, habits check-off, journal.
✔ Weekly review can be completed end-to-end in the wizard.

**Phase 7 — Polish & later**
Web Push, iTIP invitations for local events, CardDAV, PDF full-text search, German UI,
UnifiedPush for Android, performance tuning, accessibility audit.

---

## 14. Instructions for implementing agents

- Read this spec fully before starting a phase; implement phase by phase, module by module.
  Do not start a later phase's features unless asked.
- Keep an `docs/adr/` (architecture decision records) for any deviation from this spec or any
  decision the spec leaves open; update this spec when a decision changes it.
- Respect module boundaries (§2); new cross-module needs go through services/events.
- Every schema change is a new Flyway migration; never edit applied migrations.
- Every new entity must: be in the `ItemType`/resolver system if user-facing, be searchable
  if it has text, be covered by a `BackupContributor`, and be included in the round-trip test.
- API changes: update server, regenerate `api/openapi.yaml`, regenerate clients.
- Never log secrets, email bodies or note contents at INFO level or above.
- Prefer small, reviewable commits with tests; keep the build green.

---

## 15. Open questions / to decide later

1. Domain name and server specs (RAM/disk) — affects mail body cache size and file quota.
2. Exact Angular component library look (Material 3 theme colors) — default Material 3.
3. Whether Google push notifications (watch channels) are worth it vs. 5-minute polling.
4. Optional end-to-end encryption of notes/journal (would complicate search — not planned).
5. Import from existing tools (e.g. Todoist, Google Keep, Evernote) — not planned for v1.
