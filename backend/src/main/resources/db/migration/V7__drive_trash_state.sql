ALTER TABLE content_ref ADD COLUMN drive_trashed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE sync_state
SET sync_cursor = NULL
WHERE provider = 'GOOGLE_DRIVE' AND sync_cursor IS NOT NULL;
