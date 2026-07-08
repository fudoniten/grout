CREATE TABLE directory_profiles (
  id                       bigserial PRIMARY KEY,
  tag_value                text NOT NULL UNIQUE,   -- join key, e.g. 'parent-directory:adam-neely-music'
  concept_name             text NOT NULL,          -- human-readable, e.g. 'Adam Neely Music' (fed to the LLM)
  status                   text NOT NULL DEFAULT 'pending',  -- 'pending' | 'ready' | 'failed'
  dimensions               jsonb,                  -- e.g. {"channel":["muse"],"audience":["adult"]}
  tags                     jsonb,                  -- e.g. ["music","music-theory","educational"]
  item_count_at_enrichment integer NOT NULL DEFAULT 0,  -- media count when last enriched (growth threshold)
  error                    text,                   -- last failure message when status='failed'
  enrichment_attempts      integer NOT NULL DEFAULT 0,
  last_enrichment_at       timestamptz,
  next_retry_at            timestamptz,            -- backoff schedule when status='failed'
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);
--;;

COMMENT ON TABLE directory_profiles IS
  'One LLM-derived profile (dimensions + tags) per cross-cutting tag group
   (typically a parent-directory:<x> tag). Directory-level enrichment computes
   this once per group and fans it out to every child grout_media row, replacing
   the per-file /enrich/short-form call for bulk imports.';
--;;

-- Worker sweeps: pending profiles awaiting a first enrichment.
CREATE INDEX idx_directory_profiles_pending
  ON directory_profiles (id) WHERE status = 'pending';
--;;

-- Worker sweeps: failed profiles whose backoff has elapsed.
CREATE INDEX idx_directory_profiles_retry
  ON directory_profiles (next_retry_at) WHERE status = 'failed';
--;;
