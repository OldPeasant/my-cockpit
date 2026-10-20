-- Key-value user settings (SPEC.md §4.7). Single-user application: one row holds every setting the owner changed,
-- as a JSON object {key: value}; keys that are absent use the default registered in SettingsService.
-- version is the ETag of the whole settings document (optimistic locking for PATCH /api/v1/settings).
create table user_settings (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    overrides  jsonb       not null default '{}'::jsonb
);

-- At most one row, also under concurrent first writes.
create unique index user_settings_single_row on user_settings ((true));
