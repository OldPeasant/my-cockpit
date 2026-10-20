# Task board

```mermaid
graph LR
  a11y_audit["Accessibility audit"]:::todo
  and_api_client["Android API client and auth plumbing"]:::todo
  and_apk_distribution["APK build in CI and download from the server"]:::todo
  and_bookmarks_share["Android: share URL to read later"]:::todo
  and_cal_agenda["Android calendar agenda and day/week"]:::todo
  and_cal_edit["Android event create/edit"]:::todo
  and_capture_entrypoints["Android widget, quick-settings tile, app shortcut"]:::todo
  and_capture_queue["Android offline capture queue"]:::todo
  and_contacts["Android contacts"]:::todo
  and_deps["Android dependencies and test setup"]:::todo
  and_files["Android files"]:::todo
  and_gtd_data["Android GTD repositories and read cache"]:::todo
  and_inbox["Android inbox processing"]:::todo
  and_journal_habits["Android journal and habits"]:::todo
  and_login["Android login and secure token storage"]:::todo
  and_mail_compose["Android mail compose"]:::todo
  and_mail_list_read["Android mail list and reading"]:::todo
  and_next_today["Android Next and Today"]:::todo
  and_notes["Android notes"]:::todo
  and_passkeys["Android passkeys"]:::todo
  and_projects_lists["Android projects, waiting, someday"]:::todo
  and_reminders["Android reminders"]:::todo
  and_review["Android weekly review (read-only)"]:::todo
  and_share_target["Android share target"]:::todo
  and_shell["Android app shell: theme, navigation, placeholders"]:::todo
  backup_contrib_bookmarks["Backup contributor: bookmarks"]:::todo
  backup_contrib_calendar["Backup contributor: calendar"]:::todo
  backup_contrib_contacts["Backup contributor: contacts"]:::todo
  backup_contrib_core["Backup contributors: core entities"]:::todo
  backup_contrib_files["Backup contributor: files"]:::todo
  backup_contrib_gtd["Backup contributor: GTD"]:::todo
  backup_contrib_journal["Backup contributor: journal and habits"]:::todo
  backup_contrib_mail["Backup contributor: mail accounts"]:::todo
  backup_contrib_notes["Backup contributor: notes"]:::todo
  backup_core["Backup export: zip format and orchestration"]:::todo
  backup_restore["Backup restore: validation, full replace, CLI"]:::todo
  backup_secrets["Backup secrets with passphrase"]:::todo
  bookmarks_api["Bookmarks API"]:::todo
  bookmarks_snapshot["Readable snapshots"]:::todo
  cal_caldav["CalDAV adapter"]:::todo
  cal_google["Google Calendar adapter"]:::todo
  cal_ics["ICS feed adapter"]:::todo
  cal_invitations["Respond to invitations"]:::todo
  cal_local_api["Calendar API for local calendars"]:::todo
  cal_microsoft["Microsoft Graph calendar adapter"]:::todo
  cal_model["Calendar data model"]:::todo
  cal_recurrence["Recurrence expansion"]:::todo
  cal_sync_engine["Calendar sync engine"]:::todo
  carddav_sync["CardDAV contacts sync"]:::todo
  contacts_aggregate["Contact detail aggregation and autocomplete"]:::todo
  contacts_api["Contacts API"]:::todo
  contacts_birthdays["Birthdays calendar"]:::todo
  contacts_google["Google contacts sync"]:::todo
  contacts_microsoft["Microsoft contacts sync"]:::todo
  files_api["Files API: upload, download, operations"]:::todo
  files_preview["Thumbnails and previews"]:::todo
  files_storage["Files: model and blob storage"]:::todo
  gtd_capture_api["Quick capture endpoint and syntax parser"]:::todo
  gtd_convert_event["Convert task to calendar event"]:::todo
  gtd_convert_note["Convert task to reference note"]:::todo
  gtd_model["GTD data model"]:::todo
  gtd_projects_api["Projects, areas, contexts API and next-step rules"]:::todo
  gtd_recurrence["Recurring tasks"]:::todo
  gtd_tasks_api["Tasks API"]:::todo
  gtd_tickler["Tickler and scheduled tasks job"]:::todo
  h_android_device["HUMAN: real phone test"]:::todo
  h_cal_real["HUMAN: connect real calendars"]:::todo
  h_deploy_p0["HUMAN: first deploy on the owner's server"]:::todo
  h_mail_real["HUMAN: connect real mail accounts"]:::todo
  h_oauth_apps["HUMAN: register Google and Azure OAuth apps"]:::todo
  h_prod_p1["HUMAN: phase 1 on production"]:::todo
  h_prod_p2["HUMAN: phase 2 on production + real restore drill"]:::todo
  h_prod_p6["HUMAN: phase 6 on production"]:::todo
  habits_api["Habits API"]:::todo
  i18n_german["German UI"]:::todo
  itip_invitations["Send invitations for local events (iTIP)"]:::todo
  journal_api["Journal API"]:::todo
  mail_actions_api["Mail actions"]:::todo
  mail_compose["Drafts and sending"]:::todo
  mail_gtd["Mail to GTD integration"]:::todo
  mail_header_sync["Header sync and threading"]:::todo
  mail_idle["IMAP IDLE push and polling"]:::todo
  mail_imap["IMAP/SMTP connectivity and folder sync"]:::todo
  mail_model["Mail data model"]:::todo
  mail_read_api["Mail read API"]:::todo
  mail_sanitizer["HTML mail sanitizer"]:::todo
  mail_search["Mail search"]:::todo
  notes_api["Notes API"]:::todo
  oauth_connections["OAuth connections for Google and Microsoft"]:::todo
  p0_api_conventions["API conventions: errors, ETags, pagination, OpenAPI contract file"]:::todo
  p0_auth_login["Login with password + TOTP, sessions, CSRF, lockout"]:::todo
  p0_auth_setup["First-run setup: owner account, password hashing, TOTP enrollment"]:::todo
  p0_auth_tokens["Device tokens for the Android app"]:::todo
  p0_ci["GitHub Actions CI"]:::todo
  p0_crypto["Secret encryption at rest"]:::todo
  p0_db_conventions["Persistence conventions: base entity, UUIDv7, clock, migrations layout"]:::todo
  p0_deploy["Docker image, compose stack, Caddy"]:::todo
  p0_e2e_harness["Playwright e2e harness against the compose stack"]:::todo
  p0_events_sse["Change events and SSE stream"]:::todo
  p0_items_links["Universal item references and links"]:::todo
  p0_search_core["Global search infrastructure"]:::todo
  p0_server_deps["Server dependencies, profiles and test infrastructure"]:::todo
  p0_settings["User settings API"]:::todo
  p0_smoke["Phase 0 demo script + README"]:::todo
  p0_trash_core["Trash: listing, restore, purge"]:::todo
  p0_web_api_client["Generated API client and HTTP infrastructure"]:::todo
  p0_web_auth["Web setup and login pages"]:::todo
  p0_web_deps["Web dependencies, theme and i18n baseline"]:::todo
  p0_web_devices["Settings: security and devices"]:::todo
  p0_web_settings["Settings page: structure and general section"]:::todo
  p0_web_shell["Application shell: navigation, top bar, palette skeleton, placeholders"]:::todo
  p0_web_sse["Web SSE client"]:::todo
  p1_smoke["Phase 1 demo script"]:::todo
  p2_smoke["Phase 2 demo script incl. restore on a fresh install"]:::todo
  p3_smoke["Phase 3 demo script (emulator)"]:::todo
  p4_smoke["Phase 4 demo script"]:::todo
  p5_smoke["Phase 5 demo script"]:::todo
  p6_smoke["Phase 6 demo script"]:::todo
  passkeys_server["Passkeys (WebAuthn) backend"]:::todo
  pdf_fulltext["Full-text search in PDFs and text files"]:::todo
  perf_tuning["Performance baseline and tuning"]:::todo
  reminders_core["Reminders and notifications backend"]:::todo
  review_api["Weekly review API"]:::todo
  unifiedpush_android["UnifiedPush for Android"]:::todo
  web_bookmarks["Bookmarks UI"]:::todo
  web_cal_edit["Calendar event editing"]:::todo
  web_cal_overlay["Calendar task overlay and cross-links"]:::todo
  web_cal_sources["Settings: calendars and integrations"]:::todo
  web_cal_views["Calendar views"]:::todo
  web_capture["Quick capture dialog"]:::todo
  web_contacts["Contacts UI"]:::todo
  web_dashboard["Dashboard"]:::todo
  web_files_browser["Files: browser and operations"]:::todo
  web_files_upload_preview["Files: upload and preview"]:::todo
  web_gtd_convert_event["Inbox/task detail: to calendar"]:::todo
  web_gtd_convert_note["Inbox/task detail: to reference"]:::todo
  web_gtd_data["Web GTD data layer and shared task components"]:::todo
  web_gtd_lists["Waiting For, Someday, Scheduled, Logbook views"]:::todo
  web_habits["Habits UI"]:::todo
  web_inbox["Inbox processing mode"]:::todo
  web_journal["Journal UI"]:::todo
  web_links_panel["Linked items panel (shared)"]:::todo
  web_mail_accounts["Settings: mail accounts"]:::todo
  web_mail_compose["Mail compose, reply, forward"]:::todo
  web_mail_contacts_autocomplete["Mail compose: contacts autocomplete"]:::todo
  web_mail_list["Mail list and triage"]:::todo
  web_mail_read["Mail reading view"]:::todo
  web_next_today["Next Actions and Today views"]:::todo
  web_notes_editor["Notes: Markdown editor with images"]:::todo
  web_notes_list["Notes: list and organization"]:::todo
  web_passkeys["Web passkeys"]:::todo
  web_projects["Projects views"]:::todo
  web_push["Web Push notifications"]:::todo
  web_reminders["Browser notifications for reminders"]:::todo
  web_search["Command palette search and commands"]:::todo
  web_settings_backup["Settings: backup and restore"]:::todo
  web_settings_gtd["Settings: contexts and areas"]:::todo
  web_task_detail["Task detail editor"]:::todo
  web_today_events["Today view: calendar events"]:::todo
  web_today_habits["Today view: habits due"]:::todo
  web_trash["Trash view"]:::todo
  web_weekly_review["Weekly review wizard"]:::todo
  p6_smoke --> a11y_audit
  and_shell --> and_api_client
  p0_auth_tokens --> and_api_client
  and_login --> and_apk_distribution
  p0_ci --> and_apk_distribution
  p0_deploy --> and_apk_distribution
  and_capture_entrypoints --> and_bookmarks_share
  bookmarks_api --> and_bookmarks_share
  and_login --> and_cal_agenda
  cal_local_api --> and_cal_agenda
  and_cal_agenda --> and_cal_edit
  and_share_target --> and_capture_entrypoints
  and_login --> and_capture_queue
  gtd_capture_api --> and_capture_queue
  files_api --> and_capture_queue
  and_login --> and_contacts
  contacts_aggregate --> and_contacts
  and_login --> and_files
  files_preview --> and_files
  and_login --> and_gtd_data
  gtd_projects_api --> and_gtd_data
  and_gtd_data --> and_inbox
  and_shell --> and_journal_habits
  journal_api --> and_journal_habits
  habits_api --> and_journal_habits
  and_login --> and_journal_habits
  and_api_client --> and_login
  and_mail_list_read --> and_mail_compose
  mail_compose --> and_mail_compose
  and_login --> and_mail_list_read
  mail_actions_api --> and_mail_list_read
  mail_gtd --> and_mail_list_read
  and_gtd_data --> and_next_today
  and_login --> and_notes
  notes_api --> and_notes
  passkeys_server --> and_passkeys
  and_login --> and_passkeys
  and_gtd_data --> and_projects_lists
  and_gtd_data --> and_reminders
  reminders_core --> and_reminders
  and_capture_entrypoints --> and_reminders
  and_login --> and_review
  review_api --> and_review
  and_capture_queue --> and_share_target
  and_deps --> and_shell
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
  p0_settings --> backup_core
  p0_items_links --> backup_core
  p0_crypto --> backup_core
  backup_core --> backup_restore
  backup_restore --> backup_secrets
  p0_items_links --> bookmarks_api
  p0_search_core --> bookmarks_api
  p0_trash_core --> bookmarks_api
  bookmarks_api --> bookmarks_snapshot
  files_api --> bookmarks_snapshot
  cal_sync_engine --> cal_caldav
  cal_sync_engine --> cal_google
  oauth_connections --> cal_google
  cal_sync_engine --> cal_ics
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
  files_storage --> files_api
  files_api --> files_preview
  p0_trash_core --> files_storage
  p0_search_core --> files_storage
  p0_events_sse --> files_storage
  gtd_tickler --> gtd_capture_api
  gtd_convert_note --> gtd_convert_event
  cal_local_api --> gtd_convert_event
  notes_api --> gtd_convert_note
  gtd_capture_api --> gtd_convert_note
  p0_db_conventions --> gtd_model
  p0_items_links --> gtd_model
  gtd_tasks_api --> gtd_projects_api
  gtd_projects_api --> gtd_recurrence
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
  oauth_connections --> h_oauth_apps
  p1_smoke --> h_prod_p1
  h_deploy_p0 --> h_prod_p1
  p2_smoke --> h_prod_p2
  h_prod_p1 --> h_prod_p2
  p6_smoke --> h_prod_p6
  h_mail_real --> h_prod_p6
  p0_items_links --> habits_api
  reminders_core --> habits_api
  p6_smoke --> i18n_german
  cal_local_api --> itip_invitations
  mail_compose --> itip_invitations
  p0_items_links --> journal_api
  p0_search_core --> journal_api
  p0_trash_core --> journal_api
  files_api --> journal_api
  mail_read_api --> mail_actions_api
  mail_actions_api --> mail_compose
  files_api --> mail_compose
  mail_actions_api --> mail_gtd
  gtd_capture_api --> mail_gtd
  notes_api --> mail_gtd
  mail_imap --> mail_header_sync
  mail_header_sync --> mail_idle
  p0_events_sse --> mail_idle
  mail_model --> mail_imap
  oauth_connections --> mail_imap
  p0_items_links --> mail_model
  p0_search_core --> mail_model
  p0_crypto --> mail_model
  mail_header_sync --> mail_read_api
  mail_sanitizer --> mail_read_api
  p0_server_deps --> mail_sanitizer
  mail_read_api --> mail_search
  p0_search_core --> mail_search
  files_api --> notes_api
  p0_search_core --> notes_api
  p0_trash_core --> notes_api
  p0_events_sse --> notes_api
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
  contacts_microsoft --> p6_smoke
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
  p0_events_sse --> reminders_core
  p0_items_links --> reminders_core
  gtd_projects_api --> review_api
  cal_local_api --> review_api
  and_reminders --> unifiedpush_android
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
  p0_web_shell --> web_habits
  habits_api --> web_habits
  p0_e2e_harness --> web_habits
  web_gtd_data --> web_inbox
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
  p0_web_shell --> web_mail_list
  mail_actions_api --> web_mail_list
  p0_web_sse --> web_mail_list
  p0_e2e_harness --> web_mail_list
  web_mail_list --> web_mail_read
  web_links_panel --> web_mail_read
  mail_gtd --> web_mail_read
  web_gtd_data --> web_next_today
  web_notes_list --> web_notes_editor
  web_links_panel --> web_notes_editor
  p0_web_shell --> web_notes_list
  notes_api --> web_notes_list
  p0_e2e_harness --> web_notes_list
  passkeys_server --> web_passkeys
  p0_web_devices --> web_passkeys
  p0_web_auth --> web_passkeys
  web_gtd_data --> web_projects
  reminders_core --> web_push
  web_reminders --> web_push
  reminders_core --> web_reminders
  p0_web_sse --> web_reminders
  p0_web_shell --> web_search
  p0_search_core --> web_search
  p0_e2e_harness --> web_search
  p0_web_settings --> web_settings_backup
  backup_secrets --> web_settings_backup
  p0_web_settings --> web_settings_gtd
  gtd_projects_api --> web_settings_gtd
  web_gtd_data --> web_task_detail
  web_links_panel --> web_task_detail
  gtd_recurrence --> web_task_detail
  web_next_today --> web_today_events
  web_cal_views --> web_today_events
  web_today_events --> web_today_habits
  web_habits --> web_today_habits
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
| p0-server-deps | todo |  | 158 | yes |
| p0-api-conventions | todo |  | 154 |  |
| p0-crypto | todo |  | 150 |  |
| p0-db-conventions | todo |  | 150 |  |
| p0-auth-setup | todo |  | 149 |  |
| p0-auth-login | todo |  | 148 |  |
| p0-items-links | todo |  | 131 |  |
| p0-search-core | todo |  | 117 |  |
| p0-events-sse | todo |  | 111 |  |
| p0-trash-core | todo |  | 106 |  |
| p0-web-deps | todo |  | 65 | yes |
| p0-settings | todo |  | 62 |  |
| p0-web-api-client | todo |  | 61 |  |
| files-storage | todo |  | 60 |  |
| files-api | todo |  | 59 |  |
| gtd-model | todo |  | 59 |  |
| gtd-tasks-api | todo |  | 58 |  |
| p0-web-shell | todo |  | 57 |  |
| gtd-projects-api | todo |  | 54 |  |
| p0-deploy | todo |  | 50 |  |
| p0-web-auth | todo |  | 49 |  |
| p0-e2e-harness | todo |  | 47 |  |
| p0-auth-tokens | todo |  | 43 |  |
| p0-web-sse | todo |  | 39 |  |
| cal-model | todo |  | 38 |  |
| cal-recurrence | todo |  | 37 |  |
| cal-local-api | todo |  | 36 |  |
| gtd-recurrence | todo |  | 36 |  |
| oauth-connections | todo |  | 36 |  |
| and-deps | todo |  | 35 | yes |
| and-shell | todo |  | 34 |  |
| gtd-tickler | todo |  | 34 |  |
| and-api-client | todo |  | 33 |  |
| gtd-capture-api | todo |  | 33 |  |
| reminders-core | todo |  | 33 |  |
| and-login | todo |  | 32 |  |
| notes-api | todo |  | 30 |  |
| mail-model | todo |  | 28 |  |
| web-gtd-data | todo |  | 28 |  |
| mail-imap | todo |  | 26 |  |
| backup-core | todo |  | 25 |  |
| mail-header-sync | todo |  | 24 |  |
| web-links-panel | todo |  | 24 |  |
| mail-sanitizer | todo |  | 23 |  |
| p0-web-settings | todo |  | 23 |  |
| mail-read-api | todo |  | 22 |  |
| backup-contrib-core | todo |  | 21 |  |
| web-inbox | todo |  | 19 |  |
| p0-ci | todo |  | 18 |  |
| p0-web-devices | todo |  | 18 |  |
| web-next-today | todo |  | 18 |  |
| files-preview | todo |  | 17 |  |
| gtd-convert-note | todo |  | 17 |  |
| mail-actions-api | todo |  | 17 |  |
| web-gtd-lists | todo |  | 17 |  |
| web-task-detail | todo |  | 17 |  |
| and-capture-queue | todo |  | 16 |  |
| and-gtd-data | todo |  | 16 |  |
| backup-restore | todo |  | 16 |  |
| p0-smoke | todo |  | 16 |  |
| web-cal-views | todo |  | 16 |  |
| web-reminders | todo |  | 16 |  |
| and-share-target | todo |  | 15 |  |
| backup-secrets | todo |  | 15 |  |
| cal-sync-engine | todo |  | 15 |  |
| web-capture | todo |  | 15 |  |
| web-notes-list | todo |  | 15 |  |
| web-projects | todo |  | 15 |  |
| web-search | todo |  | 15 |  |
| web-settings-gtd | todo |  | 15 |  |
| web-trash | todo |  | 15 |  |
| and-capture-entrypoints | todo |  | 14 |  |
| contacts-api | todo |  | 14 |  |
| p1-smoke | todo |  | 14 |  |
| web-files-browser | todo |  | 14 |  |
| web-gtd-convert-note | todo |  | 14 |  |
| backup-contrib-files | todo |  | 13 |  |
| backup-contrib-gtd | todo |  | 13 |  |
| backup-contrib-notes | todo |  | 13 |  |
| web-files-upload-preview | todo |  | 13 |  |
| web-notes-editor | todo |  | 13 |  |
| web-settings-backup | todo |  | 13 |  |
| and-reminders | todo |  | 12 |  |
| mail-gtd | todo |  | 12 |  |
| p2-smoke | todo |  | 12 |  |
| and-inbox | todo |  | 11 |  |
| and-next-today | todo |  | 11 |  |
| and-notes | todo |  | 11 |  |
| and-projects-lists | todo |  | 11 |  |
| cal-google | todo |  | 11 |  |
| cal-microsoft | todo |  | 11 |  |
| mail-compose | todo |  | 11 |  |
| web-mail-list | todo |  | 11 |  |
| web-today-events | todo |  | 11 |  |
| and-cal-agenda | todo |  | 10 |  |
| bookmarks-api | todo |  | 10 |  |
| cal-caldav | todo |  | 10 |  |
| cal-ics | todo |  | 10 |  |
| gtd-convert-event | todo |  | 10 |  |
| habits-api | todo |  | 10 |  |
| p3-smoke | todo |  | 10 |  |
| web-cal-edit | todo |  | 10 |  |
| and-cal-edit | todo |  | 9 |  |
| backup-contrib-calendar | todo |  | 9 |  |
| cal-invitations | todo |  | 9 |  |
| web-cal-overlay | todo |  | 9 |  |
| web-cal-sources | todo |  | 9 |  |
| web-gtd-convert-event | todo |  | 9 |  |
| web-mail-read | todo |  | 9 |  |
| and-mail-list-read | todo |  | 8 |  |
| contacts-aggregate | todo |  | 8 |  |
| journal-api | todo |  | 8 |  |
| p4-smoke | todo |  | 8 |  |
| review-api | todo |  | 8 |  |
| web-mail-compose | todo |  | 8 |  |
| and-mail-compose | todo |  | 7 |  |
| backup-contrib-mail | todo |  | 7 |  |
| mail-idle | todo |  | 7 |  |
| mail-search | todo |  | 7 |  |
| passkeys-server | todo |  | 7 |  |
| web-habits | todo |  | 7 |  |
| web-mail-accounts | todo |  | 7 |  |
| p5-smoke | todo |  | 6 |  |
| web-bookmarks | todo |  | 6 |  |
| web-today-habits | todo |  | 6 |  |
| web-weekly-review | todo |  | 6 |  |
| and-bookmarks-share | todo |  | 5 |  |
| and-contacts | todo |  | 5 |  |
| and-files | todo |  | 5 |  |
| and-journal-habits | todo |  | 5 |  |
| and-passkeys | todo |  | 5 |  |
| and-review | todo |  | 5 |  |
| backup-contrib-bookmarks | todo |  | 5 |  |
| backup-contrib-contacts | todo |  | 5 |  |
| backup-contrib-journal | todo |  | 5 |  |
| bookmarks-snapshot | todo |  | 5 |  |
| contacts-birthdays | todo |  | 5 |  |
| contacts-google | todo |  | 5 |  |
| contacts-microsoft | todo |  | 5 |  |
| h-deploy-p0 | todo |  | 5 |  |
| web-contacts | todo |  | 5 |  |
| web-dashboard | todo |  | 5 |  |
| web-journal | todo |  | 5 |  |
| web-mail-contacts-autocomplete | todo |  | 5 |  |
| web-passkeys | todo |  | 5 |  |
| h-prod-p1 | todo |  | 4 |  |
| p6-smoke | todo |  | 4 |  |
| h-oauth-apps | todo |  | 3 |  |
| h-prod-p2 | todo |  | 3 |  |
| h-cal-real | todo |  | 2 |  |
| and-apk-distribution | todo |  | 1 |  |
| h-mail-real | todo |  | 1 |  |
| a11y-audit | todo |  | 0 |  |
| carddav-sync | todo |  | 0 |  |
| h-android-device | todo |  | 0 |  |
| h-prod-p6 | todo |  | 0 |  |
| i18n-german | todo |  | 0 |  |
| itip-invitations | todo |  | 0 |  |
| pdf-fulltext | todo |  | 0 |  |
| perf-tuning | todo |  | 0 |  |
| unifiedpush-android | todo |  | 0 |  |
| web-push | todo |  | 0 |  |
