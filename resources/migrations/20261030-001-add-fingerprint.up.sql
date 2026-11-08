ALTER TABLE grout_media
  ADD COLUMN fingerprint text;
--;;

COMMENT ON COLUMN grout_media.fingerprint IS
  'Sampled fingerprint of the original source bytes (grout.media.hash/
   fingerprint-file: size plus 64 KiB from the start, middle and end). Lets
   clients check "is this file already stored?" without hashing the whole
   file. Not unique: content_hash stays the authoritative dedup key. NULL on
   rows ingested before this column existed until a client attaches one.';
--;;

CREATE INDEX grout_media_fingerprint_idx ON grout_media (fingerprint);
