-- SettingsService reads overrides as a JSON object {key: value}: any other JSON value (array, string, null) would
-- break every settings read, so the database rejects it (e.g. from a restore or a manual fix).
alter table user_settings
    add constraint user_settings_overrides_object check (jsonb_typeof(overrides) = 'object');
