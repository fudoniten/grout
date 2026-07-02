CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE grout_media (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind         text NOT NULL,
  path         text NOT NULL UNIQUE,
  name         text,
  description  text,
  channel      text,
  tags         text[] NOT NULL DEFAULT '{}',
  duration_ms  integer NOT NULL,
  width        integer,
  height       integer,
  vcodec       text,
  acodec       text,
  source       text,
  source_url   text,
  enriched     boolean NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  superseded_at timestamptz
);

CREATE INDEX grout_media_tags_gin ON grout_media USING gin (tags);
CREATE INDEX grout_media_duration ON grout_media (duration_ms);
CREATE INDEX grout_media_channel_kind ON grout_media (channel, kind);
CREATE INDEX grout_media_live ON grout_media (superseded_at) WHERE superseded_at IS NULL;
