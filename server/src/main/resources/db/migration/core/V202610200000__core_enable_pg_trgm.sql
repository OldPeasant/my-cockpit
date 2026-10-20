-- First migration (SPEC.md §4.2): trigram matching for global search. Module tables come in module migrations.
create extension if not exists pg_trgm;
