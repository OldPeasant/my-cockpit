# Task board

```mermaid
graph LR
  a11y_audit["Accessibility audit<br/>@dell26/agent-1"]:::done
  and_api_client["Android API client and auth plumbing<br/>@dell26/agent-5"]:::done
  and_apk_distribution["APK build in CI and download from the server<br/>@dell26/agent-1"]:::done
  and_bookmarks_plus["Android bookmarks: add, read-later toggle, reader, to inbox"]:::todo
  and_bookmarks_share["Android: share URL to read later<br/>@dell26/agent-2"]:::done
  and_bookmarks["Android: Bookmarks screen (list, read later, open, edit)<br/>@dell26/agent-1"]:::done
  and_cal_agenda["Android calendar agenda and day/week<br/>@dell26/agent-1"]:::done
  and_cal_cache["Android agenda offline read cache (SPEC 9.3)<br/>@dell26/agent-1"]:::done
  and_cal_delete["Android calendar: delete events (incl. repeating series scope)<br/>@dell26/agent-1"]:::done
  and_cal_edit["Android event create/edit<br/>@dell26/agent-1"]:::done
  and_cal_event_fields["Android events: reminders, attendees, invitations, time zone, links"]:::todo
  and_cal_overlay_month["Android calendar: task overlay and month view"]:::todo
  and_capture_button["Android: in-app capture button on every screen<br/>@dell26/agent-2"]:::done
  and_capture_entrypoints["Android widget, quick-settings tile, app shortcut<br/>@dell26/agent-1"]:::done
  and_capture_improve["Android capture: syntax preview, save and add another, attachments"]:::todo
  and_capture_queue["Android offline capture queue<br/>@dell26/agent-4"]:::done
  and_contacts_edit["Android contacts: create, edit, delete"]:::todo
  and_contacts["Android contacts<br/>@dell26/agent-1"]:::done
  and_dashboard_actions["Android dashboard: tappable items, review button, layout editing"]:::todo
  and_dashboard_inbox_to_mail["Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty<br/>@Peter Gürtler"]:::done
  and_dashboard["Android: Dashboard as start screen<br/>@dell26/agent-1"]:::done
  and_deps["Android dependencies and test setup<br/>@dell26/agent-3"]:::done
  and_files_manage["Android files: new folder, rename, move, delete"]:::todo
  and_files_preview["Android files: in-app preview"]:::todo
  and_files["Android files<br/>@dell26/agent-1"]:::done
  and_gtd_data["Android GTD repositories and read cache<br/>@dell26/agent-5"]:::done
  and_gtd_load_error["Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)<br/>@dell26/agent-1"]:::done
  and_habits_manage["Android habits: create, edit, archive, incremental check"]:::todo
  and_inbox_rows["Android: inbox and New view rows complete and open tasks"]:::todo
  and_inbox["Android inbox processing<br/>@dell26/agent-3"]:::done
  and_journal_full["Android journal: other days, mood, tags, calendar, on this day"]:::todo
  and_journal_habits["Android journal and habits<br/>@dell26/agent-1"]:::done
  and_links_panel["Android: shared linked-items panel with add, remove, create linked"]:::todo
  and_logbook["Android: Logbook (completed tasks, reopen)<br/>@dell26/agent-2"]:::done
  and_login["Android login and secure token storage<br/>@dell26/agent-5"]:::done
  and_mail_actions["Android mail: unread, flag, move, spam, multi-select"]:::todo
  and_mail_attachments["Android mail attachments: open, download, preview, save to Files"]:::todo
  and_mail_compose_plus["Android compose: recipient autocomplete, attach from Files"]:::todo
  and_mail_compose["Android mail compose<br/>@dell26/agent-2"]:::done
  and_mail_folder_manage["Android mail: create, rename and delete folders<br/>@dell26/agent-1"]:::done
  and_mail_images["Android shows mail images (inline and on request)"]:::todo
  and_mail_list_read["Android mail list and reading<br/>@dell26/agent-1"]:::done
  and_mail_note_links["Android mail: create note and linked items"]:::todo
  and_mail_read_spam["Android mail read view: Spam button<br/>@dell26/agent-1"]:::done
  and_mail_search_nav["Android mail search kept on return, previous/next, back to origin"]:::todo
  and_mail_search["Android mail: search box with 'search on server' option<br/>@dell26/agent-1"]:::done
  and_new_view["Android combined New view<br/>@dell26/agent-2"]:::done
  and_next_filters["Android Next actions: due, area/project filters and saved presets"]:::todo
  and_next_today["Android Next and Today<br/>@dell26/agent-1"]:::done
  and_notes_editor["Android note editor: tags, checklists, links, revisions"]:::todo
  and_notes_list["Android notes list: search, pin, archive, delete"]:::todo
  and_notes["Android notes<br/>@dell26/agent-3"]:::done
  and_passkeys["Android passkeys<br/>@dell26/agent-2"]:::done
  and_project_tasks["Android: add tasks directly to a project and reorder them"]:::todo
  and_projects_lists["Android projects, waiting, someday<br/>@dell26/agent-1"]:::done
  and_projects_manage["Android: create, edit, complete and drop projects"]:::todo
  and_reminders["Android reminders<br/>@dell26/agent-4"]:::done
  and_review_flow["Android weekly review: start, steps, resume, reflection"]:::todo
  and_review_steps["Android weekly review: waiting, projects, next, someday steps"]:::todo
  and_review["Android weekly review (read-only)<br/>@dell26/agent-1"]:::done
  and_scheduled_list["Android: Scheduled list screen"]:::todo
  and_search["Android: global search<br/>@dell26/agent-2"]:::done
  and_server_url_tolerant["Android login: server address tolerates keyboard-inserted spaces<br/>@dell26/agent-1"]:::done
  and_settings["Android: Settings screen (server, notifications, capture queue, app version)<br/>@dell26/agent-1"]:::done
  and_share_target["Android share target<br/>@dell26/agent-2"]:::done
  and_shell["Android app shell: theme, navigation, placeholders<br/>@dell26/agent-1"]:::done
  and_task_convert["Android: convert tasks to notes and events"]:::todo
  and_task_detail_dates["Android task detail: scheduled, tickler, recurrence, all lists"]:::todo
  and_task_detail["Android: task detail screen with editing<br/>@dell26/agent-1"]:::done
  and_test_tmpdir["Android: unit tests write Robolectric temp files into build/, not the shared /tmp<br/>@dell26/agent-1"]:::done
  and_today_events_habits["Android Today shows events and habits"]:::todo
  and_today_released["Android Today: show tasks released to Next today under 'Scheduled for today' (releasedOn / scheduled=today)<br/>@dell26/agent-1"]:::done
  and_totp_reenroll["Android handles forced TOTP re-enrollment"]:::todo
  and_trash["Android: Trash (list, restore, delete forever)<br/>@dell26/agent-1"]:::done
  and_user_settings["Android reads and writes the synced user settings"]:::todo
  app_icons["App icon and favicon from the brand mark<br/>@dell26/agent-3"]:::done
  app_logo["Show the brand mark in the web top bar, web login/setup and Android login<br/>@dell26/agent-2"]:::done
  backup_contrib_bookmarks["Backup contributor: bookmarks<br/>@dell26/agent-1"]:::done
  backup_contrib_calendar["Backup contributor: calendar<br/>@dell26/agent-3"]:::done
  backup_contrib_contacts["Backup contributor: contacts<br/>@dell26/agent-2"]:::done
  backup_contrib_core["Backup contributors: core entities<br/>@dell26/agent-5"]:::done
  backup_contrib_files["Backup contributor: files<br/>@dell26/agent-1"]:::done
  backup_contrib_gtd["Backup contributor: GTD<br/>@dell26/agent-1"]:::done
  backup_contrib_journal["Backup contributor: journal and habits<br/>@dell26/agent-2"]:::done
  backup_contrib_mail["Backup contributor: mail accounts<br/>@dell26/agent-2"]:::done
  backup_contrib_notes["Backup contributor: notes<br/>@dell26/agent-2"]:::done
  backup_contrib_review["Backup contributor: weekly reviews<br/>@dell26/agent-2"]:::done
  backup_core["Backup export: zip format and orchestration<br/>@dell26/agent-5"]:::done
  backup_restore["Backup restore: validation, full replace, CLI<br/>@dell26/agent-4"]:::done
  backup_round_trip_tmp_leak["BackupRoundTripTest leaks a 61 MB cockpit-round-trip-data temp dir per run, filling /tmp (Disk quota exceeded)<br/>@dell26/agent-1"]:::done
  backup_roundtrip_tmp_leak["BackupRoundTripTest leaks a temp data dir per run<br/>@dell26/agent-1"]:::done
  backup_secrets["Backup secrets with passphrase<br/>@dell26/agent-1"]:::done
  backup_workdir_tmp_leak["BackupJobService/RestoreService leak their lazy temp work dirs<br/>@dell26/agent-2"]:::done
  bookmarks_api["Bookmarks API<br/>@dell26/agent-3"]:::done
  bookmarks_snapshot["Readable snapshots<br/>@dell26/agent-1"]:::done
  cal_caldav_retry["CalDAV: survive dropped connections (flaky Radicale integration tests)<br/>@dell26/agent-1"]:::done
  cal_caldav["CalDAV adapter<br/>@dell26/agent-1"]:::done
  cal_google["Google Calendar adapter<br/>@dell26/agent-2"]:::done
  cal_ics["ICS feed adapter<br/>@dell26/agent-1"]:::done
  cal_invitation_respond["Accept, decline or tentatively accept invitations (web + Android)"]:::todo
  cal_invitations["Respond to invitations<br/>@dell26/agent-3"]:::done
  cal_local_api["Calendar API for local calendars<br/>@R1963MACOS/agent-2"]:::done
  cal_microsoft["Microsoft Graph calendar adapter<br/>@dell26/agent-2"]:::done
  cal_model["Calendar data model<br/>@dell26/agent-3"]:::done
  cal_recurrence["Recurrence expansion<br/>@dell26/agent-3"]:::done
  cal_sync_engine["Calendar sync engine<br/>@dell26/agent-6"]:::done
  caldav_radicale_auth["CalDAV Radicale tests run without authentication<br/>@dell26/agent-1"]:::done
  carddav_api["CardDAV accounts: REST API, web settings UI and backup<br/>@dell26/agent-1"]:::done
  carddav_sync["CardDAV contacts sync<br/>@dell26/agent-1"]:::done
  ci_oom_and_test_reports["CI: give the Docker server build its heap settings; make failing tests visible<br/>@dell26/agent-1"]:::done
  ci_split_check["CI: split the fast check into parallel jobs per part<br/>@dell26/agent-1"]:::done
  contacts_aggregate["Contact detail aggregation and autocomplete<br/>@dell26/agent-1"]:::done
  contacts_api["Contacts API<br/>@dell26/agent-1"]:::done
  contacts_birthdays["Birthdays calendar<br/>@dell26/agent-1"]:::done
  contacts_google["Google contacts sync<br/>@dell26/agent-2"]:::done
  contacts_microsoft["Microsoft contacts sync"]:::dropped
  deploy_assetlinks["Serve /.well-known/assetlinks.json for the Android app (passkeys)<br/>@dell26/agent-1"]:::done
  deploy_oauth_env["Pass COCKPIT_OAUTH_PUBLICURL to the server in deploy compose<br/>@dell26/agent-2"]:::done
  deploy_passkey_env["Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy<br/>@dell26/agent-2"]:::done
  download_spa_404["Unknown /download/* paths return 404, not the SPA index.html<br/>@dell26/agent-1"]:::done
  files_api["Files API: upload, download, operations<br/>@dell26/agent-3"]:::done
  files_preview["Thumbnails and previews<br/>@dell26/agent-4"]:::done
  files_storage["Files: model and blob storage<br/>@dell26/agent-5"]:::done
  fix_flaky_backup_progress_and_settings_tests["Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak<br/>@dell26/agent-1"]:::done
  fix_flaky_backup_sse_test["BackupEventsStreamTests flakes under full-suite load (step was 'bookmarks: data', expected 'controllable: data')<br/>@dell26/agent-1"]:::done
  fix_flaky_bookmark_patch_test["Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)<br/>@Peter Gürtler"]:::done
  fix_flaky_caldav_dropped_chunk_test["Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries<br/>@Peter Gürtler"]:::done
  fix_flaky_clock_test["Fix flaky ClockConfigurationTests (micro- vs nanosecond Instant compare)"]:::dropped
  gtd_capture_api["Quick capture endpoint and syntax parser<br/>@dell26/agent-6"]:::done
  gtd_convert_event["Convert task to calendar event<br/>@dell26/agent-1"]:::done
  gtd_convert_note["Convert task to reference note<br/>@dell26/agent-1"]:::done
  gtd_delete_reassign["Server-side reassignment when deleting a context or area<br/>@Peter Gürtler"]:::done
  gtd_links_everywhere["Linked items on projects and files; follow-up by mail (web + Android)"]:::todo
  gtd_model["GTD data model<br/>@dell26/agent-3"]:::done
  gtd_projects_api["Projects, areas, contexts API and next-step rules<br/>@R1963MACOS/agent-1"]:::done
  gtd_recurrence["Recurring tasks<br/>@R1963MACOS/agent-1"]:::done
  gtd_scheduled_marker["Today view: keep 'scheduled for today' visible after the scheduled release<br/>@dell26/agent-2"]:::done
  gtd_tasks_api["Tasks API<br/>@dell26/agent-5"]:::done
  gtd_tickler["Tickler and scheduled tasks job<br/>@dell26/agent-4"]:::done
  h_android_device["HUMAN: real phone test<br/>@Peter Gürtler"]:::done
  h_cal_real["HUMAN: connect real calendars<br/>@Peter Gürtler"]:::done
  h_deploy_p0["HUMAN: first deploy on the owner's server<br/>@Peter Gürtler"]:::done
  h_mail_real["HUMAN: connect real mail accounts<br/>@Peter Gürtler"]:::done
  h_ms_real["HUMAN: connect real Microsoft calendar and mail"]:::dropped
  h_oauth_apps["HUMAN: register Google OAuth app<br/>@Peter Gürtler"]:::done
  h_oauth_azure["HUMAN: register Azure OAuth app (Microsoft)"]:::dropped
  h_prod_p1["HUMAN: phase 1 on production<br/>@Peter Gürtler"]:::done
  h_prod_p2["HUMAN: phase 2 on production + real restore drill<br/>@Peter Gürtler"]:::done
  h_prod_p6["HUMAN: phase 6 on production<br/>@Peter Gürtler"]:::done
  habits_api["Habits API<br/>@dell26/agent-2"]:::done
  i18n_german["German UI<br/>@dell26/agent-2"]:::done
  ics_max_size_flaky_test["IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'<br/>@dell26/agent-1"]:::done
  itip_api["API + web UI to send iTIP invitations for local events<br/>@dell26/agent-1"]:::done
  itip_invitations["Send invitations for local events (iTIP)<br/>@dell26/agent-1"]:::done
  itip_reply_ordering["Ignore stale iTIP REPLYs across messages (per-attendee answered-at)<br/>@dell26/agent-1"]:::done
  journal_api["Journal API<br/>@dell26/agent-1"]:::done
  mail_account_label_email["Mail screens label accounts by email address, not the sender name<br/>@dell26/agent-1"]:::done
  mail_actions_api["Mail actions<br/>@dell26/agent-5"]:::done
  mail_attachment_to_files["Save mail attachment to Files (/Attachments/Mail)<br/>@dell26/agent-1"]:::done
  mail_compose_identity_fallback["New mail starts From the account address unless an identity is default<br/>@dell26/agent-2"]:::done
  mail_compose["Drafts and sending<br/>@Peter Gürtler"]:::done
  mail_connection_errors["Shared MailConnectionException handler for mail controllers<br/>@felix/agent-1"]:::done
  mail_delete_stale_trash["Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up<br/>@dell26/agent-1"]:::done
  mail_folder_manage_api["Mail folders: create, rename and delete (API)<br/>@dell26/agent-2"]:::done
  mail_gtd["Mail to GTD integration<br/>@dell26/agent-3"]:::done
  mail_header_sync["Header sync and threading<br/>@dell26/agent-1"]:::done
  mail_identities["Mail sending identities API and settings<br/>@dell26/agent-1"]:::done
  mail_identity_drafts["Choose sending identity and resume drafts (web + Android)"]:::todo
  mail_idle_flaky_test["MailIdleGreenMailTests: wait for the change event (flaky in CI)<br/>@dell26/agent-1"]:::done
  mail_idle["IMAP IDLE push and polling<br/>@dell26/agent-1"]:::done
  mail_images_web_settings["Web mail images follow the synced image settings"]:::todo
  mail_imap["IMAP/SMTP connectivity and folder sync<br/>@dell26/agent-1"]:::done
  mail_load_older_ui["Load mail older than the sync window (web + Android)"]:::todo
  mail_load_older["Load older mail on demand (API for MailHeaderSyncService.loadOlder)<br/>@dell26/agent-2"]:::done
  mail_model["Mail data model<br/>@dell26/agent-2"]:::done
  mail_read_api["Mail read API<br/>@dell26/agent-5"]:::done
  mail_restore_retake_linked["Mail sync: retake linked/reminded refs on the first sync after a restore<br/>@dell26/agent-2"]:::done
  mail_sanitizer["HTML mail sanitizer<br/>@dell26/agent-5"]:::done
  mail_search["Mail search<br/>@dell26/agent-1"]:::done
  mail_sync_perf["Cheaper link checks and loadOlder candidate narrowing in mail sync<br/>@dell26/agent-1"]:::done
  microsoft_hide["Hide Microsoft integration behind a setting (default off)<br/>@dell26/agent-1"]:::done
  notes_api["Notes API<br/>@dell26/agent-5"]:::done
  notes_markdown_escapes["Robust plain text of note Markdown with backslash escapes<br/>@dell26/agent-2"]:::done
  oauth_connections["OAuth connections for Google and Microsoft<br/>@dell26/agent-1"]:::done
  p0_api_conventions["API conventions: errors, ETags, pagination, OpenAPI contract file<br/>@dell26/agent-2"]:::done
  p0_auth_login["Login with password + TOTP, sessions, CSRF, lockout<br/>@dell26/agent-4"]:::done
  p0_auth_setup["First-run setup: owner account, password hashing, TOTP enrollment<br/>@dell26/agent-4"]:::done
  p0_auth_tokens["Device tokens for the Android app<br/>@dell26/agent-5"]:::done
  p0_ci["GitHub Actions CI<br/>@dell26/agent-1"]:::done
  p0_crypto["Secret encryption at rest<br/>@dell26/agent-1"]:::done
  p0_db_conventions["Persistence conventions: base entity, UUIDv7, clock, migrations layout<br/>@dell26/agent-4"]:::done
  p0_deploy["Docker image, compose stack, Caddy<br/>@dell26/agent-3"]:::done
  p0_e2e_harness["Playwright e2e harness against the compose stack<br/>@dell26/agent-2"]:::done
  p0_events_sse["Change events and SSE stream<br/>@dell26/agent-4"]:::done
  p0_items_links["Universal item references and links<br/>@dell26/agent-5"]:::done
  p0_search_core["Global search infrastructure<br/>@dell26/agent-5"]:::done
  p0_server_deps["Server dependencies, profiles and test infrastructure<br/>@dell26/agent-1"]:::done
  p0_settings["User settings API<br/>@dell26/agent-5"]:::done
  p0_smoke["Phase 0 demo script + README<br/>@dell26/agent-1"]:::done
  p0_trash_core["Trash: listing, restore, purge<br/>@R1963MACOS/agent-2"]:::done
  p0_web_api_client["Generated API client and HTTP infrastructure<br/>@dell26/agent-2"]:::done
  p0_web_auth["Web setup and login pages<br/>@dell26/agent-2"]:::done
  p0_web_deps["Web dependencies, theme and i18n baseline<br/>@dell26/agent-3"]:::done
  p0_web_devices["Settings: security and devices<br/>@dell26/agent-4"]:::done
  p0_web_settings["Settings page: structure and general section<br/>@dell26/agent-3"]:::done
  p0_web_shell["Application shell: navigation, top bar, palette skeleton, placeholders<br/>@dell26/agent-2"]:::done
  p0_web_sse["Web SSE client<br/>@dell26/agent-4"]:::done
  p1_smoke["Phase 1 demo script<br/>@dell26/agent-4"]:::done
  p2_smoke["Phase 2 demo script incl. restore on a fresh install<br/>@dell26/agent-2"]:::done
  p3_smoke["Phase 3 demo script (emulator)<br/>@dell26/agent-3"]:::done
  p4_smoke["Phase 4 demo script<br/>@dell26/agent-1"]:::done
  p5_smoke["Phase 5 demo script<br/>@dell26/agent-2"]:::done
  p6_smoke["Phase 6 demo script<br/>@dell26/agent-1"]:::done
  passkeys_server["Passkeys (WebAuthn) backend<br/>@dell26/agent-2"]:::done
  pdf_fulltext["Full-text search in PDFs and text files<br/>@dell26/agent-2"]:::done
  perf_tuning["Performance baseline and tuning<br/>@dell26/agent-1"]:::done
  push_session_binding["Bind Web Push subscriptions to the login session<br/>@dell26/agent-1"]:::done
  reminders_core["Reminders and notifications backend<br/>@dell26/agent-3"]:::done
  review_api["Weekly review API<br/>@dell26/agent-1"]:::done
  review_reminder["Weekly review reminder on a configurable weekday"]:::todo
  runner_hung_session["Runner: end a session that hangs after its final result<br/>@dell26/agent-2"]:::done
  runner_push_failure_loop["Runner: stop the loop when release/hold cannot push<br/>@dell26/agent-4"]:::done
  runner_version_skew["Runner: same tasks.py as agents.py; lost push races are not refusals<br/>@dell26/agent-5"]:::done
  task_reminders["Reminders on tasks (web + Android)"]:::todo
  test_tmp_dir_leaks["Other server tests leak their companion temp data dirs into /tmp<br/>@dell26/agent-2"]:::done
  test_tmp_in_build["Server and Android tests keep temp files in build/, not /tmp<br/>@dell26/agent-1"]:::done
  unifiedpush_android_client["Android UnifiedPush client<br/>@dell26/agent-2"]:::done
  unifiedpush_android["UnifiedPush for Android<br/>@dell26/agent-1"]:::done
  unifiedpush_server["Server side of UnifiedPush (registrations + push sending)<br/>@dell26/agent-2"]:::done
  web_and_birthday_occurrences["Clients: open birthday (virtual) occurrences read-only<br/>@dell26/agent-1"]:::done
  web_back_navigation["Web back buttons return to where the user came from"]:::todo
  web_bookmarks_search_edit["Web bookmarks: search, edit, undo, to inbox"]:::todo
  web_bookmarks["Bookmarks UI<br/>@dell26/agent-3"]:::done
  web_cal_edit["Calendar event editing<br/>@dell26/agent-3"]:::done
  web_cal_overlay["Calendar task overlay and cross-links<br/>@dell26/agent-3"]:::done
  web_cal_sources["Settings: calendars and integrations<br/>@dell26/agent-2"]:::done
  web_cal_views["Calendar views<br/>@dell26/agent-4"]:::done
  web_capture["Quick capture dialog<br/>@dell26/agent-3"]:::done
  web_contacts["Contacts UI<br/>@dell26/agent-1"]:::done
  web_dashboard["Dashboard<br/>@dell26/agent-1"]:::done
  web_files_browser["Files: browser and operations<br/>@R1963MACOS/agent-1"]:::done
  web_files_upload_preview["Files: upload and preview<br/>@dell26/agent-4"]:::done
  web_gtd_convert_event["Inbox/task detail: to calendar<br/>@dell26/agent-1"]:::done
  web_gtd_convert_note["Inbox/task detail: to reference<br/>@dell26/agent-1"]:::done
  web_gtd_data["Web GTD data layer and shared task components<br/>@dell26/agent-2"]:::done
  web_gtd_lists["Waiting For, Someday, Scheduled, Logbook views<br/>@dell26/agent-6"]:::done
  web_habits_shared_labels["Share habit check label/error helpers between Habits view and Today<br/>@Peter Gürtler"]:::done
  web_habits["Habits UI<br/>@dell26/agent-3"]:::done
  web_inbox["Inbox processing mode<br/>@dell26/agent-6"]:::done
  web_journal_rollover["Journal: Today rolls over at midnight, prompt before dropping conflicting edits<br/>@dell26/agent-2"]:::done
  web_journal["Journal UI<br/>@dell26/agent-2"]:::done
  web_links_panel["Linked items panel (shared)<br/>@dell26/agent-1"]:::done
  web_mail_accounts["Settings: mail accounts<br/>@dell26/agent-3"]:::done
  web_mail_compose["Mail compose, reply, forward<br/>@dell26/agent-1"]:::done
  web_mail_contacts_autocomplete["Mail compose: contacts autocomplete<br/>@dell26/agent-1"]:::done
  web_mail_folder_manage["Mail folder tree: create, rename and delete folders<br/>@dell26/agent-1"]:::done
  web_mail_list["Mail list and triage<br/>@dell26/agent-4"]:::done
  web_mail_read_actions["Web mail read view: unread, flag, move, shortcuts r/a/f/c"]:::todo
  web_mail_read_archive_spam["Mail read view: Archive and Spam buttons<br/>@dell26/agent-2"]:::done
  web_mail_read_delete["Mail read view: Delete button<br/>@dell26/agent-1"]:::done
  web_mail_read["Mail reading view<br/>@dell26/agent-2"]:::done
  web_mail_search_nav["Web mail search survives opening a match and has previous/next"]:::todo
  web_mail_search["Mail view: search box with 'search on server' option<br/>@dell26/agent-1"]:::done
  web_mail_single_click_open["Mail list: a single click opens the message<br/>@dell26/agent-1"]:::done
  web_new_view["Combined New view (GTD inbox + mail inbox)<br/>@dell26/agent-2"]:::done
  web_next_today["Next Actions and Today views<br/>@dell26/agent-4"]:::done
  web_notes_delete_tags["Web notes: delete and edit tags"]:::todo
  web_notes_editor["Notes: Markdown editor with images<br/>@dell26/agent-4"]:::done
  web_notes_list["Notes: list and organization<br/>@R1963MACOS/agent-1"]:::done
  web_passkeys["Web passkeys<br/>@dell26/agent-1"]:::done
  web_projects_plus["Web projects: delete project, all tasks in project detail"]:::todo
  web_projects["Projects views<br/>@dell26/agent-6"]:::done
  web_push["Web Push notifications<br/>@dell26/agent-1"]:::done
  web_reminders["Browser notifications for reminders<br/>@dell26/agent-2"]:::done
  web_search["Command palette search and commands<br/>@dell26/agent-4"]:::done
  web_settings_backup["Settings: backup and restore<br/>@dell26/agent-3"]:::done
  web_settings_gtd["Settings: contexts and areas<br/>@dell26/agent-3"]:::done
  web_small_gaps["Web: search type filter, read-later capture, Android download link"]:::todo
  web_task_actions["Web task detail: complete, delete, undo, Markdown notes"]:::todo
  web_task_detail["Task detail editor<br/>@dell26/agent-1"]:::done
  web_tests_load_robust["Web unit tests: no cascade failures and fewer timeouts on a busy machine<br/>@dell26/agent-1"]:::done
  web_today_events["Today view: calendar events<br/>@dell26/agent-1"]:::done
  web_today_habits["Today view: habits due<br/>@dell26/agent-1"]:::done
  web_totp_reenroll["Web forced TOTP re-enrollment after restore<br/>@dell26/agent-3"]:::done
  web_trash["Trash view<br/>@dell26/agent-6"]:::done
  web_weekly_review["Weekly review wizard<br/>@dell26/agent-2"]:::done
  p6_smoke --> a11y_audit
  and_shell --> and_api_client
  p0_auth_tokens --> and_api_client
  and_login --> and_apk_distribution
  p0_ci --> and_apk_distribution
  p0_deploy --> and_apk_distribution
  ci_split_check --> and_apk_distribution
  and_capture_entrypoints --> and_bookmarks_share
  bookmarks_api --> and_bookmarks_share
  and_bookmarks_share --> and_bookmarks
  and_shell --> and_bookmarks
  and_login --> and_cal_agenda
  cal_local_api --> and_cal_agenda
  and_cal_agenda --> and_cal_cache
  and_cal_edit --> and_cal_delete
  and_cal_agenda --> and_cal_edit
  and_capture_entrypoints --> and_capture_button
  and_shell --> and_capture_button
  and_share_target --> and_capture_entrypoints
  and_login --> and_capture_queue
  gtd_capture_api --> and_capture_queue
  files_api --> and_capture_queue
  and_links_panel --> and_contacts_edit
  and_login --> and_contacts
  contacts_aggregate --> and_contacts
  and_dashboard --> and_dashboard_inbox_to_mail
  and_shell --> and_dashboard
  and_next_today --> and_dashboard
  web_dashboard --> and_dashboard
  and_logbook --> and_dashboard
  and_files_manage --> and_files_preview
  and_login --> and_files
  files_preview --> and_files
  and_login --> and_gtd_data
  gtd_projects_api --> and_gtd_data
  and_next_today --> and_gtd_load_error
  and_gtd_data --> and_inbox
  and_shell --> and_journal_habits
  journal_api --> and_journal_habits
  habits_api --> and_journal_habits
  and_login --> and_journal_habits
  and_task_convert --> and_links_panel
  and_projects_lists --> and_logbook
  and_gtd_data --> and_logbook
  and_trash --> and_logbook
  and_api_client --> and_login
  and_mail_search_nav --> and_mail_actions
  and_mail_actions --> and_mail_attachments
  and_mail_list_read --> and_mail_compose
  mail_compose --> and_mail_compose
  mail_folder_manage_api --> and_mail_folder_manage
  and_mail_list_read --> and_mail_folder_manage
  mail_account_label_email --> and_mail_folder_manage
  and_mail_read_spam --> and_mail_folder_manage
  and_user_settings --> and_mail_images
  and_login --> and_mail_list_read
  mail_actions_api --> and_mail_list_read
  mail_gtd --> and_mail_list_read
  and_links_panel --> and_mail_note_links
  and_mail_attachments --> and_mail_note_links
  and_mail_list_read --> and_mail_read_spam
  mail_account_label_email --> and_mail_read_spam
  and_mail_images --> and_mail_search_nav
  and_mail_list_read --> and_mail_search
  mail_search --> and_mail_search
  and_inbox --> and_new_view
  and_mail_list_read --> and_new_view
  web_new_view --> and_new_view
  and_user_settings --> and_next_filters
  and_gtd_data --> and_next_today
  and_notes_list --> and_notes_editor
  and_links_panel --> and_notes_editor
  and_login --> and_notes
  notes_api --> and_notes
  passkeys_server --> and_passkeys
  and_login --> and_passkeys
  app_logo --> and_passkeys
  and_projects_manage --> and_project_tasks
  and_gtd_data --> and_projects_lists
  and_gtd_data --> and_reminders
  reminders_core --> and_reminders
  and_capture_entrypoints --> and_reminders
  and_scheduled_list --> and_review_flow
  and_review_flow --> and_review_steps
  and_login --> and_review
  review_api --> and_review
  and_project_tasks --> and_scheduled_list
  and_mail_search_nav --> and_scheduled_list
  and_shell --> and_search
  p0_search_core --> and_search
  and_capture_button --> and_search
  and_login --> and_server_url_tolerant
  and_shell --> and_settings
  and_capture_queue --> and_settings
  and_reminders --> and_settings
  unifiedpush_android_client --> and_settings
  and_capture_queue --> and_share_target
  and_deps --> and_shell
  and_task_detail_dates --> and_task_convert
  and_inbox_rows --> and_task_convert
  and_dashboard --> and_task_detail
  and_logbook --> and_task_detail
  and_search --> and_task_detail
  and_gtd_data --> and_task_detail
  and_next_filters --> and_today_events_habits
  gtd_scheduled_marker --> and_today_released
  and_shell --> and_trash
  p0_trash_core --> and_trash
  and_search --> and_trash
  p0_web_shell --> app_icons
  and_shell --> app_icons
  app_icons --> app_logo
  backup_contrib_core --> backup_contrib_bookmarks
  bookmarks_api --> backup_contrib_bookmarks
  backup_contrib_core --> backup_contrib_calendar
  cal_local_api --> backup_contrib_calendar
  backup_contrib_core --> backup_contrib_contacts
  contacts_api --> backup_contrib_contacts
  backup_core --> backup_contrib_core
  reminders_core --> backup_contrib_core
  backup_contrib_core --> backup_contrib_files
  files_preview --> backup_contrib_files
  backup_contrib_core --> backup_contrib_gtd
  gtd_convert_note --> backup_contrib_gtd
  backup_contrib_core --> backup_contrib_journal
  journal_api --> backup_contrib_journal
  habits_api --> backup_contrib_journal
  backup_contrib_core --> backup_contrib_mail
  mail_model --> backup_contrib_mail
  backup_secrets --> backup_contrib_mail
  backup_contrib_core --> backup_contrib_notes
  notes_api --> backup_contrib_notes
  review_api --> backup_contrib_review
  backup_contrib_core --> backup_contrib_review
  p0_settings --> backup_core
  p0_items_links --> backup_core
  p0_crypto --> backup_core
  backup_core --> backup_restore
  backup_restore --> backup_secrets
  backup_contrib_core --> backup_secrets
  p0_items_links --> bookmarks_api
  p0_search_core --> bookmarks_api
  p0_trash_core --> bookmarks_api
  bookmarks_api --> bookmarks_snapshot
  files_api --> bookmarks_snapshot
  cal_sync_engine --> cal_caldav
  cal_sync_engine --> cal_google
  oauth_connections --> cal_google
  cal_sync_engine --> cal_ics
  and_cal_event_fields --> cal_invitation_respond
  cal_google --> cal_invitations
  cal_microsoft --> cal_invitations
  cal_recurrence --> cal_local_api
  p0_events_sse --> cal_local_api
  cal_sync_engine --> cal_microsoft
  oauth_connections --> cal_microsoft
  p0_items_links --> cal_model
  p0_search_core --> cal_model
  p0_trash_core --> cal_model
  cal_model --> cal_recurrence
  cal_local_api --> cal_sync_engine
  carddav_sync --> carddav_api
  contacts_api --> carddav_sync
  contacts_api --> contacts_aggregate
  mail_read_api --> contacts_aggregate
  cal_local_api --> contacts_aggregate
  gtd_tasks_api --> contacts_aggregate
  p0_items_links --> contacts_api
  p0_search_core --> contacts_api
  p0_trash_core --> contacts_api
  files_api --> contacts_api
  contacts_api --> contacts_birthdays
  cal_local_api --> contacts_birthdays
  contacts_api --> contacts_google
  oauth_connections --> contacts_google
  contacts_api --> contacts_microsoft
  oauth_connections --> contacts_microsoft
  and_passkeys --> deploy_assetlinks
  deploy_passkey_env --> deploy_assetlinks
  oauth_connections --> deploy_oauth_env
  passkeys_server --> deploy_passkey_env
  and_apk_distribution --> download_spa_404
  files_storage --> files_api
  runner_push_failure_loop --> files_api
  files_api --> files_preview
  p0_trash_core --> files_storage
  p0_search_core --> files_storage
  p0_events_sse --> files_storage
  backup_core --> fix_flaky_backup_progress_and_settings_tests
  and_settings --> fix_flaky_backup_progress_and_settings_tests
  bookmarks_api --> fix_flaky_bookmark_patch_test
  cal_caldav_retry --> fix_flaky_caldav_dropped_chunk_test
  gtd_tickler --> gtd_capture_api
  gtd_convert_note --> gtd_convert_event
  cal_local_api --> gtd_convert_event
  notes_api --> gtd_convert_note
  gtd_capture_api --> gtd_convert_note
  web_settings_gtd --> gtd_delete_reassign
  and_links_panel --> gtd_links_everywhere
  web_projects_plus --> gtd_links_everywhere
  and_project_tasks --> gtd_links_everywhere
  and_files_preview --> gtd_links_everywhere
  and_scheduled_list --> gtd_links_everywhere
  web_task_actions --> gtd_links_everywhere
  p0_db_conventions --> gtd_model
  p0_items_links --> gtd_model
  gtd_tasks_api --> gtd_projects_api
  gtd_projects_api --> gtd_recurrence
  web_next_today --> gtd_scheduled_marker
  gtd_model --> gtd_tasks_api
  p0_search_core --> gtd_tasks_api
  p0_trash_core --> gtd_tasks_api
  p0_events_sse --> gtd_tasks_api
  gtd_recurrence --> gtd_tickler
  p3_smoke --> h_android_device
  and_apk_distribution --> h_android_device
  p4_smoke --> h_cal_real
  h_oauth_apps --> h_cal_real
  h_prod_p2 --> h_cal_real
  p0_smoke --> h_deploy_p0
  p5_smoke --> h_mail_real
  h_oauth_apps --> h_mail_real
  h_cal_real --> h_mail_real
  h_oauth_azure --> h_ms_real
  h_mail_real --> h_ms_real
  oauth_connections --> h_oauth_apps
  oauth_connections --> h_oauth_azure
  p1_smoke --> h_prod_p1
  h_deploy_p0 --> h_prod_p1
  p2_smoke --> h_prod_p2
  h_prod_p1 --> h_prod_p2
  p6_smoke --> h_prod_p6
  h_mail_real --> h_prod_p6
  p0_items_links --> habits_api
  reminders_core --> habits_api
  p6_smoke --> i18n_german
  itip_invitations --> itip_api
  cal_local_api --> itip_invitations
  mail_compose --> itip_invitations
  itip_invitations --> itip_reply_ordering
  p0_items_links --> journal_api
  p0_search_core --> journal_api
  p0_trash_core --> journal_api
  files_api --> journal_api
  mail_read_api --> mail_actions_api
  web_mail_read --> mail_attachment_to_files
  mail_load_older --> mail_attachment_to_files
  mail_identities --> mail_compose_identity_fallback
  mail_actions_api --> mail_compose
  files_api --> mail_compose
  mail_gtd --> mail_connection_errors
  mail_actions_api --> mail_delete_stale_trash
  mail_imap --> mail_folder_manage_api
  mail_actions_api --> mail_gtd
  gtd_capture_api --> mail_gtd
  notes_api --> mail_gtd
  mail_imap --> mail_header_sync
  web_mail_accounts --> mail_identities
  and_mail_compose_plus --> mail_identity_drafts
  mail_load_older_ui --> mail_identity_drafts
  mail_header_sync --> mail_idle
  p0_events_sse --> mail_idle
  web_back_navigation --> mail_images_web_settings
  mail_model --> mail_imap
  oauth_connections --> mail_imap
  web_mail_read_actions --> mail_load_older_ui
  and_mail_actions --> mail_load_older_ui
  and_mail_note_links --> mail_load_older_ui
  mail_read_api --> mail_load_older
  mail_actions_api --> mail_load_older
  p0_items_links --> mail_model
  p0_search_core --> mail_model
  p0_crypto --> mail_model
  mail_header_sync --> mail_read_api
  mail_sanitizer --> mail_read_api
  backup_contrib_mail --> mail_restore_retake_linked
  mail_sync_perf --> mail_restore_retake_linked
  p0_server_deps --> mail_sanitizer
  mail_read_api --> mail_search
  p0_search_core --> mail_search
  mail_header_sync --> mail_sync_perf
  mail_idle --> mail_sync_perf
  mail_idle_flaky_test --> mail_sync_perf
  mail_identities --> microsoft_hide
  files_api --> notes_api
  p0_search_core --> notes_api
  p0_trash_core --> notes_api
  p0_events_sse --> notes_api
  runner_version_skew --> notes_api
  mail_gtd --> notes_markdown_escapes
  p0_crypto --> oauth_connections
  p0_auth_login --> oauth_connections
  p0_settings --> oauth_connections
  p0_server_deps --> p0_api_conventions
  p0_auth_setup --> p0_auth_login
  p0_db_conventions --> p0_auth_setup
  p0_api_conventions --> p0_auth_setup
  p0_crypto --> p0_auth_setup
  p0_auth_login --> p0_auth_tokens
  p0_deploy --> p0_ci
  p0_server_deps --> p0_crypto
  p0_server_deps --> p0_db_conventions
  p0_web_deps --> p0_deploy
  p0_api_conventions --> p0_deploy
  p0_deploy --> p0_e2e_harness
  p0_web_auth --> p0_e2e_harness
  p0_auth_login --> p0_events_sse
  p0_auth_login --> p0_items_links
  p0_items_links --> p0_search_core
  p0_auth_login --> p0_settings
  p0_e2e_harness --> p0_smoke
  p0_web_settings --> p0_smoke
  p0_web_sse --> p0_smoke
  p0_ci --> p0_smoke
  p0_web_devices --> p0_smoke
  p0_trash_core --> p0_smoke
  p0_search_core --> p0_smoke
  p0_items_links --> p0_trash_core
  p0_web_deps --> p0_web_api_client
  p0_api_conventions --> p0_web_api_client
  p0_web_shell --> p0_web_auth
  p0_auth_login --> p0_web_auth
  p0_web_settings --> p0_web_devices
  p0_auth_tokens --> p0_web_devices
  p0_web_shell --> p0_web_settings
  p0_settings --> p0_web_settings
  p0_web_api_client --> p0_web_shell
  p0_web_api_client --> p0_web_sse
  p0_events_sse --> p0_web_sse
  web_capture --> p1_smoke
  web_inbox --> p1_smoke
  web_next_today --> p1_smoke
  web_projects --> p1_smoke
  web_task_detail --> p1_smoke
  web_gtd_lists --> p1_smoke
  web_trash --> p1_smoke
  web_search --> p1_smoke
  web_reminders --> p1_smoke
  web_settings_gtd --> p1_smoke
  p0_smoke --> p1_smoke
  web_files_upload_preview --> p2_smoke
  web_notes_editor --> p2_smoke
  web_settings_backup --> p2_smoke
  web_gtd_convert_note --> p2_smoke
  backup_contrib_gtd --> p2_smoke
  backup_contrib_notes --> p2_smoke
  backup_contrib_files --> p2_smoke
  p1_smoke --> p2_smoke
  and_share_target --> p3_smoke
  and_capture_entrypoints --> p3_smoke
  and_inbox --> p3_smoke
  and_next_today --> p3_smoke
  and_projects_lists --> p3_smoke
  and_reminders --> p3_smoke
  and_notes --> p3_smoke
  p2_smoke --> p3_smoke
  web_cal_edit --> p4_smoke
  web_cal_sources --> p4_smoke
  web_cal_overlay --> p4_smoke
  web_today_events --> p4_smoke
  web_gtd_convert_event --> p4_smoke
  and_cal_edit --> p4_smoke
  cal_invitations --> p4_smoke
  backup_contrib_calendar --> p4_smoke
  p3_smoke --> p4_smoke
  web_mail_accounts --> p5_smoke
  web_mail_compose --> p5_smoke
  mail_idle --> p5_smoke
  mail_search --> p5_smoke
  backup_contrib_mail --> p5_smoke
  and_mail_compose --> p5_smoke
  p4_smoke --> p5_smoke
  web_contacts --> p6_smoke
  contacts_google --> p6_smoke
  contacts_birthdays --> p6_smoke
  backup_contrib_contacts --> p6_smoke
  web_mail_contacts_autocomplete --> p6_smoke
  bookmarks_snapshot --> p6_smoke
  backup_contrib_bookmarks --> p6_smoke
  and_bookmarks_share --> p6_smoke
  web_journal --> p6_smoke
  backup_contrib_journal --> p6_smoke
  and_journal_habits --> p6_smoke
  web_dashboard --> p6_smoke
  and_files --> p6_smoke
  and_contacts --> p6_smoke
  and_review --> p6_smoke
  web_passkeys --> p6_smoke
  and_passkeys --> p6_smoke
  p5_smoke --> p6_smoke
  p0_auth_tokens --> passkeys_server
  files_preview --> pdf_fulltext
  p0_search_core --> pdf_fulltext
  p6_smoke --> perf_tuning
  web_push --> push_session_binding
  p0_events_sse --> reminders_core
  p0_items_links --> reminders_core
  gtd_projects_api --> review_api
  cal_local_api --> review_api
  and_review_flow --> review_reminder
  and_review_steps --> review_reminder
  web_small_gaps --> review_reminder
  and_task_detail_dates --> task_reminders
  web_task_actions --> task_reminders
  and_links_panel --> task_reminders
  test_tmp_dir_leaks --> test_tmp_in_build
  backup_round_trip_tmp_leak --> test_tmp_in_build
  and_reminders --> unifiedpush_android_client
  unifiedpush_server --> unifiedpush_android_client
  and_reminders --> unifiedpush_android
  unifiedpush_server --> unifiedpush_android
  unifiedpush_android_client --> unifiedpush_android
  and_reminders --> unifiedpush_server
  contacts_birthdays --> web_and_birthday_occurrences
  p0_web_shell --> web_bookmarks
  bookmarks_api --> web_bookmarks
  p0_e2e_harness --> web_bookmarks
  web_cal_views --> web_cal_edit
  web_cal_edit --> web_cal_overlay
  web_gtd_data --> web_cal_overlay
  web_links_panel --> web_cal_overlay
  notes_api --> web_cal_overlay
  p0_web_settings --> web_cal_sources
  cal_caldav --> web_cal_sources
  cal_ics --> web_cal_sources
  cal_google --> web_cal_sources
  cal_microsoft --> web_cal_sources
  p0_web_shell --> web_cal_views
  cal_local_api --> web_cal_views
  p0_web_sse --> web_cal_views
  p0_e2e_harness --> web_cal_views
  web_gtd_data --> web_capture
  gtd_capture_api --> web_capture
  p0_web_shell --> web_contacts
  contacts_aggregate --> web_contacts
  web_links_panel --> web_contacts
  p0_e2e_harness --> web_contacts
  contacts_birthdays --> web_contacts
  web_today_habits --> web_dashboard
  web_weekly_review --> web_dashboard
  web_bookmarks --> web_dashboard
  web_notes_list --> web_dashboard
  web_mail_list --> web_dashboard
  p0_web_shell --> web_files_browser
  files_api --> web_files_browser
  p0_e2e_harness --> web_files_browser
  web_files_browser --> web_files_upload_preview
  files_preview --> web_files_upload_preview
  web_gtd_convert_note --> web_gtd_convert_event
  gtd_convert_event --> web_gtd_convert_event
  web_cal_views --> web_gtd_convert_event
  web_inbox --> web_gtd_convert_note
  web_task_detail --> web_gtd_convert_note
  gtd_convert_note --> web_gtd_convert_note
  p0_web_shell --> web_gtd_data
  p0_web_sse --> web_gtd_data
  gtd_projects_api --> web_gtd_data
  p0_e2e_harness --> web_gtd_data
  web_gtd_data --> web_gtd_lists
  web_today_habits --> web_habits_shared_labels
  p0_web_shell --> web_habits
  habits_api --> web_habits
  p0_e2e_harness --> web_habits
  web_gtd_data --> web_inbox
  web_journal --> web_journal_rollover
  p0_web_shell --> web_journal
  journal_api --> web_journal
  p0_e2e_harness --> web_journal
  p0_web_shell --> web_links_panel
  p0_items_links --> web_links_panel
  p0_search_core --> web_links_panel
  p0_web_settings --> web_mail_accounts
  mail_imap --> web_mail_accounts
  web_mail_read --> web_mail_compose
  mail_compose --> web_mail_compose
  web_mail_compose --> web_mail_contacts_autocomplete
  contacts_aggregate --> web_mail_contacts_autocomplete
  mail_folder_manage_api --> web_mail_folder_manage
  web_mail_list --> web_mail_folder_manage
  mail_account_label_email --> web_mail_folder_manage
  web_mail_single_click_open --> web_mail_folder_manage
  p0_web_shell --> web_mail_list
  mail_actions_api --> web_mail_list
  p0_web_sse --> web_mail_list
  p0_e2e_harness --> web_mail_list
  web_mail_search_nav --> web_mail_read_actions
  web_mail_read_delete --> web_mail_read_archive_spam
  web_mail_read --> web_mail_read_delete
  web_mail_list --> web_mail_read
  web_links_panel --> web_mail_read
  mail_gtd --> web_mail_read
  web_back_navigation --> web_mail_search_nav
  mail_images_web_settings --> web_mail_search_nav
  mail_search --> web_mail_search
  web_mail_list --> web_mail_search
  web_mail_list --> web_mail_single_click_open
  mail_account_label_email --> web_mail_single_click_open
  web_inbox --> web_new_view
  web_mail_list --> web_new_view
  mail_gtd --> web_new_view
  web_gtd_data --> web_next_today
  web_back_navigation --> web_notes_delete_tags
  web_notes_list --> web_notes_editor
  web_links_panel --> web_notes_editor
  p0_web_shell --> web_notes_list
  notes_api --> web_notes_list
  p0_e2e_harness --> web_notes_list
  passkeys_server --> web_passkeys
  p0_web_devices --> web_passkeys
  p0_web_auth --> web_passkeys
  web_totp_reenroll --> web_passkeys
  web_back_navigation --> web_projects_plus
  web_gtd_data --> web_projects
  reminders_core --> web_push
  web_reminders --> web_push
  app_icons --> web_push
  reminders_core --> web_reminders
  p0_web_sse --> web_reminders
  p0_web_shell --> web_search
  p0_search_core --> web_search
  p0_e2e_harness --> web_search
  p0_web_settings --> web_settings_backup
  backup_secrets --> web_settings_backup
  p0_web_settings --> web_settings_gtd
  gtd_projects_api --> web_settings_gtd
  mail_images_web_settings --> web_small_gaps
  web_back_navigation --> web_task_actions
  web_gtd_data --> web_task_detail
  web_links_panel --> web_task_detail
  gtd_recurrence --> web_task_detail
  web_files_upload_preview --> web_tests_load_robust
  web_next_today --> web_today_events
  web_cal_views --> web_today_events
  web_today_events --> web_today_habits
  web_habits --> web_today_habits
  backup_secrets --> web_totp_reenroll
  p0_web_auth --> web_totp_reenroll
  app_logo --> web_totp_reenroll
  p0_web_shell --> web_trash
  p0_trash_core --> web_trash
  p0_e2e_harness --> web_trash
  review_api --> web_weekly_review
  web_inbox --> web_weekly_review
  web_cal_views --> web_weekly_review
  web_gtd_lists --> web_weekly_review
  classDef todo fill:#e5e7eb
  classDef in_progress fill:#fde68a
  classDef done fill:#86efac
  classDef dropped fill:#f3f4f6
```

| Task | Status | Owner | Unblocks | Ready |
|---|---|---|---|---|
| and-user-settings | todo |  | 14 | yes |
| web-back-navigation | todo |  | 12 | yes |
| and-mail-images | todo |  | 11 |  |
| and-mail-search-nav | todo |  | 10 |  |
| and-inbox-rows | todo |  | 9 | yes |
| and-task-detail-dates | todo |  | 9 | yes |
| and-task-convert | todo |  | 8 |  |
| and-links-panel | todo |  | 7 |  |
| and-projects-manage | todo |  | 6 | yes |
| mail-images-web-settings | todo |  | 6 |  |
| and-project-tasks | todo |  | 5 |  |
| and-mail-actions | todo |  | 4 |  |
| and-scheduled-list | todo |  | 4 |  |
| and-mail-attachments | todo |  | 3 |  |
| web-mail-search-nav | todo |  | 3 |  |
| and-files-manage | todo |  | 2 | yes |
| and-mail-note-links | todo |  | 2 |  |
| and-review-flow | todo |  | 2 |  |
| web-mail-read-actions | todo |  | 2 |  |
| web-task-actions | todo |  | 2 |  |
| and-cal-event-fields | todo |  | 1 | yes |
| and-files-preview | todo |  | 1 |  |
| and-mail-compose-plus | todo |  | 1 | yes |
| and-next-filters | todo |  | 1 |  |
| and-notes-list | todo |  | 1 | yes |
| and-review-steps | todo |  | 1 |  |
| mail-load-older-ui | todo |  | 1 |  |
| web-projects-plus | todo |  | 1 |  |
| web-small-gaps | todo |  | 1 |  |
| a11y-audit | done | dell26/agent-1 | 0 |  |
| and-api-client | done | dell26/agent-5 | 0 |  |
| and-apk-distribution | done | dell26/agent-1 | 0 |  |
| and-bookmarks | done | dell26/agent-1 | 0 |  |
| and-bookmarks-plus | todo |  | 0 | yes |
| and-bookmarks-share | done | dell26/agent-2 | 0 |  |
| and-cal-agenda | done | dell26/agent-1 | 0 |  |
| and-cal-cache | done | dell26/agent-1 | 0 |  |
| and-cal-delete | done | dell26/agent-1 | 0 |  |
| and-cal-edit | done | dell26/agent-1 | 0 |  |
| and-cal-overlay-month | todo |  | 0 | yes |
| and-capture-button | done | dell26/agent-2 | 0 |  |
| and-capture-entrypoints | done | dell26/agent-1 | 0 |  |
| and-capture-improve | todo |  | 0 | yes |
| and-capture-queue | done | dell26/agent-4 | 0 |  |
| and-contacts | done | dell26/agent-1 | 0 |  |
| and-contacts-edit | todo |  | 0 |  |
| and-dashboard | done | dell26/agent-1 | 0 |  |
| and-dashboard-actions | todo |  | 0 | yes |
| and-dashboard-inbox-to-mail | done | Peter Gürtler | 0 |  |
| and-deps | done | dell26/agent-3 | 0 |  |
| and-files | done | dell26/agent-1 | 0 |  |
| and-gtd-data | done | dell26/agent-5 | 0 |  |
| and-gtd-load-error | done | dell26/agent-1 | 0 |  |
| and-habits-manage | todo |  | 0 | yes |
| and-inbox | done | dell26/agent-3 | 0 |  |
| and-journal-full | todo |  | 0 | yes |
| and-journal-habits | done | dell26/agent-1 | 0 |  |
| and-logbook | done | dell26/agent-2 | 0 |  |
| and-login | done | dell26/agent-5 | 0 |  |
| and-mail-compose | done | dell26/agent-2 | 0 |  |
| and-mail-folder-manage | done | dell26/agent-1 | 0 |  |
| and-mail-list-read | done | dell26/agent-1 | 0 |  |
| and-mail-read-spam | done | dell26/agent-1 | 0 |  |
| and-mail-search | done | dell26/agent-1 | 0 |  |
| and-new-view | done | dell26/agent-2 | 0 |  |
| and-next-today | done | dell26/agent-1 | 0 |  |
| and-notes | done | dell26/agent-3 | 0 |  |
| and-notes-editor | todo |  | 0 |  |
| and-passkeys | done | dell26/agent-2 | 0 |  |
| and-projects-lists | done | dell26/agent-1 | 0 |  |
| and-reminders | done | dell26/agent-4 | 0 |  |
| and-review | done | dell26/agent-1 | 0 |  |
| and-search | done | dell26/agent-2 | 0 |  |
| and-server-url-tolerant | done | dell26/agent-1 | 0 |  |
| and-settings | done | dell26/agent-1 | 0 |  |
| and-share-target | done | dell26/agent-2 | 0 |  |
| and-shell | done | dell26/agent-1 | 0 |  |
| and-task-detail | done | dell26/agent-1 | 0 |  |
| and-test-tmpdir | done | dell26/agent-1 | 0 |  |
| and-today-events-habits | todo |  | 0 |  |
| and-today-released | done | dell26/agent-1 | 0 |  |
| and-totp-reenroll | todo |  | 0 | yes |
| and-trash | done | dell26/agent-1 | 0 |  |
| app-icons | done | dell26/agent-3 | 0 |  |
| app-logo | done | dell26/agent-2 | 0 |  |
| backup-contrib-bookmarks | done | dell26/agent-1 | 0 |  |
| backup-contrib-calendar | done | dell26/agent-3 | 0 |  |
| backup-contrib-contacts | done | dell26/agent-2 | 0 |  |
| backup-contrib-core | done | dell26/agent-5 | 0 |  |
| backup-contrib-files | done | dell26/agent-1 | 0 |  |
| backup-contrib-gtd | done | dell26/agent-1 | 0 |  |
| backup-contrib-journal | done | dell26/agent-2 | 0 |  |
| backup-contrib-mail | done | dell26/agent-2 | 0 |  |
| backup-contrib-notes | done | dell26/agent-2 | 0 |  |
| backup-contrib-review | done | dell26/agent-2 | 0 |  |
| backup-core | done | dell26/agent-5 | 0 |  |
| backup-restore | done | dell26/agent-4 | 0 |  |
| backup-round-trip-tmp-leak | done | dell26/agent-1 | 0 |  |
| backup-roundtrip-tmp-leak | done | dell26/agent-1 | 0 |  |
| backup-secrets | done | dell26/agent-1 | 0 |  |
| backup-workdir-tmp-leak | done | dell26/agent-2 | 0 |  |
| bookmarks-api | done | dell26/agent-3 | 0 |  |
| bookmarks-snapshot | done | dell26/agent-1 | 0 |  |
| cal-caldav | done | dell26/agent-1 | 0 |  |
| cal-caldav-retry | done | dell26/agent-1 | 0 |  |
| cal-google | done | dell26/agent-2 | 0 |  |
| cal-ics | done | dell26/agent-1 | 0 |  |
| cal-invitation-respond | todo |  | 0 |  |
| cal-invitations | done | dell26/agent-3 | 0 |  |
| cal-local-api | done | R1963MACOS/agent-2 | 0 |  |
| cal-microsoft | done | dell26/agent-2 | 0 |  |
| cal-model | done | dell26/agent-3 | 0 |  |
| cal-recurrence | done | dell26/agent-3 | 0 |  |
| cal-sync-engine | done | dell26/agent-6 | 0 |  |
| caldav-radicale-auth | done | dell26/agent-1 | 0 |  |
| carddav-api | done | dell26/agent-1 | 0 |  |
| carddav-sync | done | dell26/agent-1 | 0 |  |
| ci-oom-and-test-reports | done | dell26/agent-1 | 0 |  |
| ci-split-check | done | dell26/agent-1 | 0 |  |
| contacts-aggregate | done | dell26/agent-1 | 0 |  |
| contacts-api | done | dell26/agent-1 | 0 |  |
| contacts-birthdays | done | dell26/agent-1 | 0 |  |
| contacts-google | done | dell26/agent-2 | 0 |  |
| contacts-microsoft | dropped |  | 0 |  |
| deploy-assetlinks | done | dell26/agent-1 | 0 |  |
| deploy-oauth-env | done | dell26/agent-2 | 0 |  |
| deploy-passkey-env | done | dell26/agent-2 | 0 |  |
| download-spa-404 | done | dell26/agent-1 | 0 |  |
| files-api | done | dell26/agent-3 | 0 |  |
| files-preview | done | dell26/agent-4 | 0 |  |
| files-storage | done | dell26/agent-5 | 0 |  |
| fix-flaky-backup-progress-and-settings-tests | done | dell26/agent-1 | 0 |  |
| fix-flaky-backup-sse-test | done | dell26/agent-1 | 0 |  |
| fix-flaky-bookmark-patch-test | done | Peter Gürtler | 0 |  |
| fix-flaky-caldav-dropped-chunk-test | done | Peter Gürtler | 0 |  |
| fix-flaky-clock-test | dropped |  | 0 |  |
| gtd-capture-api | done | dell26/agent-6 | 0 |  |
| gtd-convert-event | done | dell26/agent-1 | 0 |  |
| gtd-convert-note | done | dell26/agent-1 | 0 |  |
| gtd-delete-reassign | done | Peter Gürtler | 0 |  |
| gtd-links-everywhere | todo |  | 0 |  |
| gtd-model | done | dell26/agent-3 | 0 |  |
| gtd-projects-api | done | R1963MACOS/agent-1 | 0 |  |
| gtd-recurrence | done | R1963MACOS/agent-1 | 0 |  |
| gtd-scheduled-marker | done | dell26/agent-2 | 0 |  |
| gtd-tasks-api | done | dell26/agent-5 | 0 |  |
| gtd-tickler | done | dell26/agent-4 | 0 |  |
| h-android-device | done | Peter Gürtler | 0 |  |
| h-cal-real | done | Peter Gürtler | 0 |  |
| h-deploy-p0 | done | Peter Gürtler | 0 |  |
| h-mail-real | done | Peter Gürtler | 0 |  |
| h-ms-real | dropped |  | 0 |  |
| h-oauth-apps | done | Peter Gürtler | 0 |  |
| h-oauth-azure | dropped |  | 0 |  |
| h-prod-p1 | done | Peter Gürtler | 0 |  |
| h-prod-p2 | done | Peter Gürtler | 0 |  |
| h-prod-p6 | done | Peter Gürtler | 0 |  |
| habits-api | done | dell26/agent-2 | 0 |  |
| i18n-german | done | dell26/agent-2 | 0 |  |
| ics-max-size-flaky-test | done | dell26/agent-1 | 0 |  |
| itip-api | done | dell26/agent-1 | 0 |  |
| itip-invitations | done | dell26/agent-1 | 0 |  |
| itip-reply-ordering | done | dell26/agent-1 | 0 |  |
| journal-api | done | dell26/agent-1 | 0 |  |
| mail-account-label-email | done | dell26/agent-1 | 0 |  |
| mail-actions-api | done | dell26/agent-5 | 0 |  |
| mail-attachment-to-files | done | dell26/agent-1 | 0 |  |
| mail-compose | done | Peter Gürtler | 0 |  |
| mail-compose-identity-fallback | done | dell26/agent-2 | 0 |  |
| mail-connection-errors | done | felix/agent-1 | 0 |  |
| mail-delete-stale-trash | done | dell26/agent-1 | 0 |  |
| mail-folder-manage-api | done | dell26/agent-2 | 0 |  |
| mail-gtd | done | dell26/agent-3 | 0 |  |
| mail-header-sync | done | dell26/agent-1 | 0 |  |
| mail-identities | done | dell26/agent-1 | 0 |  |
| mail-identity-drafts | todo |  | 0 |  |
| mail-idle | done | dell26/agent-1 | 0 |  |
| mail-idle-flaky-test | done | dell26/agent-1 | 0 |  |
| mail-imap | done | dell26/agent-1 | 0 |  |
| mail-load-older | done | dell26/agent-2 | 0 |  |
| mail-model | done | dell26/agent-2 | 0 |  |
| mail-read-api | done | dell26/agent-5 | 0 |  |
| mail-restore-retake-linked | done | dell26/agent-2 | 0 |  |
| mail-sanitizer | done | dell26/agent-5 | 0 |  |
| mail-search | done | dell26/agent-1 | 0 |  |
| mail-sync-perf | done | dell26/agent-1 | 0 |  |
| microsoft-hide | done | dell26/agent-1 | 0 |  |
| notes-api | done | dell26/agent-5 | 0 |  |
| notes-markdown-escapes | done | dell26/agent-2 | 0 |  |
| oauth-connections | done | dell26/agent-1 | 0 |  |
| p0-api-conventions | done | dell26/agent-2 | 0 |  |
| p0-auth-login | done | dell26/agent-4 | 0 |  |
| p0-auth-setup | done | dell26/agent-4 | 0 |  |
| p0-auth-tokens | done | dell26/agent-5 | 0 |  |
| p0-ci | done | dell26/agent-1 | 0 |  |
| p0-crypto | done | dell26/agent-1 | 0 |  |
| p0-db-conventions | done | dell26/agent-4 | 0 |  |
| p0-deploy | done | dell26/agent-3 | 0 |  |
| p0-e2e-harness | done | dell26/agent-2 | 0 |  |
| p0-events-sse | done | dell26/agent-4 | 0 |  |
| p0-items-links | done | dell26/agent-5 | 0 |  |
| p0-search-core | done | dell26/agent-5 | 0 |  |
| p0-server-deps | done | dell26/agent-1 | 0 |  |
| p0-settings | done | dell26/agent-5 | 0 |  |
| p0-smoke | done | dell26/agent-1 | 0 |  |
| p0-trash-core | done | R1963MACOS/agent-2 | 0 |  |
| p0-web-api-client | done | dell26/agent-2 | 0 |  |
| p0-web-auth | done | dell26/agent-2 | 0 |  |
| p0-web-deps | done | dell26/agent-3 | 0 |  |
| p0-web-devices | done | dell26/agent-4 | 0 |  |
| p0-web-settings | done | dell26/agent-3 | 0 |  |
| p0-web-shell | done | dell26/agent-2 | 0 |  |
| p0-web-sse | done | dell26/agent-4 | 0 |  |
| p1-smoke | done | dell26/agent-4 | 0 |  |
| p2-smoke | done | dell26/agent-2 | 0 |  |
| p3-smoke | done | dell26/agent-3 | 0 |  |
| p4-smoke | done | dell26/agent-1 | 0 |  |
| p5-smoke | done | dell26/agent-2 | 0 |  |
| p6-smoke | done | dell26/agent-1 | 0 |  |
| passkeys-server | done | dell26/agent-2 | 0 |  |
| pdf-fulltext | done | dell26/agent-2 | 0 |  |
| perf-tuning | done | dell26/agent-1 | 0 |  |
| push-session-binding | done | dell26/agent-1 | 0 |  |
| reminders-core | done | dell26/agent-3 | 0 |  |
| review-api | done | dell26/agent-1 | 0 |  |
| review-reminder | todo |  | 0 |  |
| runner-hung-session | done | dell26/agent-2 | 0 |  |
| runner-push-failure-loop | done | dell26/agent-4 | 0 |  |
| runner-version-skew | done | dell26/agent-5 | 0 |  |
| task-reminders | todo |  | 0 |  |
| test-tmp-dir-leaks | done | dell26/agent-2 | 0 |  |
| test-tmp-in-build | done | dell26/agent-1 | 0 |  |
| unifiedpush-android | done | dell26/agent-1 | 0 |  |
| unifiedpush-android-client | done | dell26/agent-2 | 0 |  |
| unifiedpush-server | done | dell26/agent-2 | 0 |  |
| web-and-birthday-occurrences | done | dell26/agent-1 | 0 |  |
| web-bookmarks | done | dell26/agent-3 | 0 |  |
| web-bookmarks-search-edit | todo |  | 0 | yes |
| web-cal-edit | done | dell26/agent-3 | 0 |  |
| web-cal-overlay | done | dell26/agent-3 | 0 |  |
| web-cal-sources | done | dell26/agent-2 | 0 |  |
| web-cal-views | done | dell26/agent-4 | 0 |  |
| web-capture | done | dell26/agent-3 | 0 |  |
| web-contacts | done | dell26/agent-1 | 0 |  |
| web-dashboard | done | dell26/agent-1 | 0 |  |
| web-files-browser | done | R1963MACOS/agent-1 | 0 |  |
| web-files-upload-preview | done | dell26/agent-4 | 0 |  |
| web-gtd-convert-event | done | dell26/agent-1 | 0 |  |
| web-gtd-convert-note | done | dell26/agent-1 | 0 |  |
| web-gtd-data | done | dell26/agent-2 | 0 |  |
| web-gtd-lists | done | dell26/agent-6 | 0 |  |
| web-habits | done | dell26/agent-3 | 0 |  |
| web-habits-shared-labels | done | Peter Gürtler | 0 |  |
| web-inbox | done | dell26/agent-6 | 0 |  |
| web-journal | done | dell26/agent-2 | 0 |  |
| web-journal-rollover | done | dell26/agent-2 | 0 |  |
| web-links-panel | done | dell26/agent-1 | 0 |  |
| web-mail-accounts | done | dell26/agent-3 | 0 |  |
| web-mail-compose | done | dell26/agent-1 | 0 |  |
| web-mail-contacts-autocomplete | done | dell26/agent-1 | 0 |  |
| web-mail-folder-manage | done | dell26/agent-1 | 0 |  |
| web-mail-list | done | dell26/agent-4 | 0 |  |
| web-mail-read | done | dell26/agent-2 | 0 |  |
| web-mail-read-archive-spam | done | dell26/agent-2 | 0 |  |
| web-mail-read-delete | done | dell26/agent-1 | 0 |  |
| web-mail-search | done | dell26/agent-1 | 0 |  |
| web-mail-single-click-open | done | dell26/agent-1 | 0 |  |
| web-new-view | done | dell26/agent-2 | 0 |  |
| web-next-today | done | dell26/agent-4 | 0 |  |
| web-notes-delete-tags | todo |  | 0 |  |
| web-notes-editor | done | dell26/agent-4 | 0 |  |
| web-notes-list | done | R1963MACOS/agent-1 | 0 |  |
| web-passkeys | done | dell26/agent-1 | 0 |  |
| web-projects | done | dell26/agent-6 | 0 |  |
| web-push | done | dell26/agent-1 | 0 |  |
| web-reminders | done | dell26/agent-2 | 0 |  |
| web-search | done | dell26/agent-4 | 0 |  |
| web-settings-backup | done | dell26/agent-3 | 0 |  |
| web-settings-gtd | done | dell26/agent-3 | 0 |  |
| web-task-detail | done | dell26/agent-1 | 0 |  |
| web-tests-load-robust | done | dell26/agent-1 | 0 |  |
| web-today-events | done | dell26/agent-1 | 0 |  |
| web-today-habits | done | dell26/agent-1 | 0 |  |
| web-totp-reenroll | done | dell26/agent-3 | 0 |  |
| web-trash | done | dell26/agent-6 | 0 |  |
| web-weekly-review | done | dell26/agent-2 | 0 |  |
