# ADR 0001: Normalize Image Condition references

Status: Accepted

## Context

An Image Condition can match 1 through 20 ordered Reference Images. Reference order must survive edits, copies, database migrations, and backup round trips. Duplicate images are valid. Bitmap files also need cascade-aware and usage-aware cleanup.

Storing the list inside `condition_table` as serialized content would hide individual paths and priorities from Room and SQLite. That would make foreign-key cleanup, path indexing, file usage counts, and compatibility migrations dependent on application-side parsing.

## Decision

Store each Reference Image in `image_reference_table` with:

- composite key `(conditionId, priority)`;
- bitmap path and reference rectangle coordinates;
- foreign key to `condition_table` with cascading deletion;
- index on bitmap path.

Room relations load the entries and domain mapping sorts them by priority. Related rows take precedence when present. The first related entry remains mirrored in legacy Image Condition columns so older backups can still be imported. Database migration 26 to 27 creates one priority-zero entry for every existing Image Condition.

## Consequences

- Reordering updates explicit priorities without changing the Image Condition identifier.
- Duplicate paths remain representable because priority is part of the key.
- Backup validation and file usage queries can inspect every Reference Image directly.
- Cascading database deletion is automatic; PNG deletion remains usage-aware and occurs only after no persisted reference uses a path.
- Legacy columns remain transitional compatibility fields and must mirror the first Reference Image on every write.
