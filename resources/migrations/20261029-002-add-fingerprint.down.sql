DROP INDEX IF EXISTS grout_media_fingerprint_idx;
--;;

ALTER TABLE grout_media
  DROP COLUMN fingerprint;
