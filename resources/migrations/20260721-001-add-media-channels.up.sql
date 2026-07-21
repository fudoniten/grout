ALTER TABLE grout_media
  ADD COLUMN channels text[];
--;;

-- Backfill: every existing single-channel row gets an equivalent one-element
-- array, so the "both channel and channels null = generic/plays anywhere"
-- invariant holds from day one (no row ends up with a stray inconsistency
-- between the legacy scalar and the new array).
UPDATE grout_media SET channels = ARRAY[channel] WHERE channel IS NOT NULL AND channel <> '';
--;;

COMMENT ON COLUMN grout_media.channels IS
  'Full set of channels this item may play on. Superset of the legacy
   singular `channel` column, which is kept in sync as channels[1] by the
   application (never a generated column, so plain INSERT/UPDATE still work)
   for old consumers that only look at `channel`. Both NULL means
   channel-agnostic (usable on any channel), same meaning `channel IS NULL`
   had on its own before this column existed. Populated with more than one
   element when a directory profile''s `channel` dimension is manually
   reassigned to several channels at once (see grout.directory-profiles).';
--;;

CREATE INDEX grout_media_channels_gin ON grout_media USING gin (channels);
--;;
