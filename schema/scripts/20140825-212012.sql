-- To complete services.visibility migration:
-- update services set visibility = 'organization' where visibility is null;
-- alter table services alter column visibility set not null;
