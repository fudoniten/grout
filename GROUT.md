# Grout — Filler & Interstitial Media Store

> Implementation brief for a new service. Written for an implementer starting cold.

## 1. What Grout is

Grout is a small, purpose-built storage-and-retrieval service for **filler media**:
short bumpers, interstitials, idents, and (later) orphan long-form clips (e.g.
YouTube content) that don't fit Jellyfin's metadata-provider-oriented worldview.

It stores media in a **flat structure** (no collections/hierarchy), filters by
**tags + duration**, owns its metadata (name/description/tags are freely
mutable), and streams media to Pseudovision. Metadata is AI-enriched via
Tunabrain. It is accessed through Marquee as a peer service alongside Tunarr
Scheduler and Pseudovision.

The name: grout fills the gaps between tiles. Grout fills the gaps between
programs.

## 2. Where it fits (ecosystem context)

- **Pseudovision (PV)** — playout engine. Streams IPTV television channels.
  Queries Grout for gap-appropriate filler and streams it (ideally by
  shared-filesystem path).
- **Tunarr Scheduler (TS)** — plans schedules, generates bumpers. Will write
  generated bumpers into Grout via intake (replacing today's Jellyfin
  scan→poll→collection dance).
- **Tunabrain** — LLM/AI gateway (OpenRouter). Grout calls it for metadata
  enrichment (tags/name/description). Grout holds **no** model keys.
- **Marquee** — UI/gateway. Grout exposes the same REST conventions as TS/PV.
- **Jellyfin** — keeps owning "recognized" media with real external identity.
  Grout owns everything that *doesn't* fit that: synthetic + orphan web content.

## 3. Core principles (do NOT violate)

1. **Retrieval, not scheduling.** Grout answers "what filler is available that
   matches X, fast." It does **not** pack a timeline, own a clock, or decide
   ordering. Packing stays with the caller (PV). See non-goals.
2. **Stream by path first.** PV is co-mounted on arr-data; query responses
   include the file `path` so PV streams directly. The HTTP stream endpoint is
   a fallback for non-co-mounted callers, not the primary path.
3. **AI via Tunabrain only.** No model credentials in Grout. Where needed,
   request extensions to the Tunabrain API rather than going around it.
4. **Normalize + probe on intake.** Every item is ffprobed and (if off-profile)
   transcoded to PV's playout profile before it's queryable. PV must never
   choke on a Grout file.
5. **Stay in the flat/tag/disposable-or-orphan lane.** Do not reimplement
   Jellyfin (no seasons/episodes/watch-state/scrapers).

## 4. Non-goals (explicit)

- No timeline packing / gap-fitting algorithm in the MVP. (A thin, stateless
  `/pack` convenience wrapper *may* be added later, but it must not become a
  second scheduler.)
- No hierarchy/collections. Tags only.
- No metadata scraping from external providers.
- No transcoding-on-read. Normalize once at intake.

## 5. Tech stack

Mirror Tunarr Scheduler and Pseudovision for consistency (so Marquee treats
Grout as a peer and ops is familiar):

- Clojure, Integrant for lifecycle, reitit + Malli for HTTP/validation, the same
  middleware/error-envelope conventions as TS, OpenAPI service from the start.
- PostgreSQL via next.jdbc + HikariCP; migratus for migrations.
- ffmpeg/ffprobe on PATH (nix flake `pathEnv`, as TS does for bumpers).
- Blob storage on the arr-data mount; **never** store video bytes in Postgres.

## 6. Data model

Design the schema to accommodate long-form from day one via a `kind`
discriminator and nullable rich-metadata columns, but only *populate* filler in
the MVP.

```sql
CREATE TABLE grout_media (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind         text NOT NULL,          -- 'bumper' | 'filler' | 'program'
  path         text NOT NULL UNIQUE,   -- absolute path on arr-data mount
  name         text,
  description  text,
  channel      text,                   -- e.g. 'britannia' (nullable = generic)
  tags         text[] NOT NULL DEFAULT '{}',
  duration_ms  integer NOT NULL,
  width        integer,
  height       integer,
  vcodec       text,
  acodec       text,
  source       text,                   -- 'tunarr-bumper' | 'youtube' | 'upload'
  source_url   text,                   -- provenance for orphan content
  enriched     boolean NOT NULL DEFAULT false,  -- AI metadata pass complete?
  created_at   timestamptz NOT NULL DEFAULT now(),
  superseded_at timestamptz            -- soft-delete / retention
);

CREATE INDEX grout_media_tags_gin ON grout_media USING gin (tags);
CREATE INDEX grout_media_duration ON grout_media (duration_ms);
CREATE INDEX grout_media_channel_kind ON grout_media (channel, kind);
CREATE INDEX grout_media_live ON grout_media (superseded_at) WHERE superseded_at IS NULL;
```

## 7. HTTP API

REST, Malli-validated, JSON. All list/query results exclude `superseded_at IS
NOT NULL` unless explicitly asked.

- `POST /grout/media` — **intake**. Body references a file already on the mount
  (`{:path ... :kind ... :channel ... :tags [...] :source ...}`) or accepts an
  upload. Runs the intake pipeline (§8) and returns the created row. `201`.
- `GET /grout/media` — **query**. Query params:
  - `channel` (optional; matches the channel OR generic/null items — decide and
    document the semantics)
  - `tags` (comma-separated; AND semantics — all required)
  - `min_ms`, `max_ms` (duration range, inclusive)
  - `kind` (optional)
  - `limit` (default e.g. 10), `random` (bool; when true, random sample)
  - Returns `{:count :items [{:id :name :duration_ms :path :stream_url
    :vcodec :acodec :tags ...}]}`. Include `path` for by-path streaming.
- `GET /grout/media/:id` — fetch one.
- `PATCH /grout/media/:id` — mutate name/description/tags/channel (this is the
  ergonomic win over Jellyfin — make it clean).
- `DELETE /grout/media/:id` — soft-delete (set `superseded_at`); hard-delete +
  unlink file behind a flag.
- `GET /grout/media/:id/stream` — **byte-range** HTTP streaming fallback.
  Must support `Range` requests and set correct content-type. Primary path for
  PV is by-path off the shared mount; this endpoint is for remote callers.
- `GET /health` — standard health/readiness.
- `GET /grout/media/:id/tags` — Returns tags associated with media id.
- `POST /grout/media/:id/tags` — Add a tag to the specified media item.

Query example (the target UX):
> "filler for `channel:britannia`, tags `daytime,fun,kids`, 65000–90000 ms,
> random, limit 5" →
> `GET /grout/media?channel=britannia&tags=daytime,fun,kids&min_ms=65000&max_ms=90000&random=true&limit=5`

## 8. Intake pipeline

For each incoming file (already on the arr-data mount):
1. `ffprobe` → extract `duration_ms`, `width`, `height`, `vcodec`, `acodec`.
2. If off playout-profile, **transcode/normalize** to the profile and rewrite
   the file. Profile = the same one TS uses for bumpers:
   `libx264 / yuv420p / aac 192k / 48000 Hz / stereo`, plus **`-movflags
   +faststart`** so the file is seekable/streamable without a full download.
   (Add `+faststart` to TS's `compose-bumper!` too, so bumpers arrive
   stream-ready.)
3. Insert the row with `enriched=false`.
4. Kick the async **enrichment** pass (§9) — do not block intake on the AI call.

## 9. AI enrichment (via Tunabrain)

- A worker (or a `POST /grout/media/:id/enrich` endpoint) picks up
  `enriched=false` rows and calls **Tunabrain** to derive tags + structured
  dimensions from the media (and any provided hint/context).
- Writes results back and sets `enriched=true`.
- Grout stays dumb about models; Tunabrain owns the OpenRouter key and prompt.
  Follow TS's existing Tunabrain-client pattern.

- **Two forms of media, two enrichment paths (both go through the same
  typed Tunabrain endpoints — `/categorize` + `/tags`):**
  - **Short form** (filler/bumper/ad, <10min): Tunabrain returns
    `audience` and `channel` dimensions (the two dimensions Grout
    queries for) plus free-form tags.
  - **Long form** (documentaries, essays, YouTube series, etc.):
    same endpoints, plus the `MediaContext` (Wikipedia / YouTube
    channel / STT excerpt) that the model grounded on. Grout persists
    the `MediaContext` to `enrichment_context` and replays it on
    retry so a human-corrected `summary` flows back to the model
    (per the `MediaContext` design).

- **Dimension catalog source of truth is Tunarr Scheduler.** Grout
  fetches `GET /api/dimensions` + `GET /api/dimensions/{name}/values`
  at startup and passes the result to Tunabrain's `/categorize` as
  the `categories` map. Tunarr Scheduler does not expose per-dimension
  descriptions; Grout ships those in its own config
  (`resources/config.edn` `:dimension-descriptions`).

- **Dimension values are validated against that catalog on the way
  back in.** Telling the model the allowed values doesn't guarantee it
  stays inside them — it still occasionally invents a channel or
  audience (a typo like `spectum` for `spectrum`, or a fabricated
  value). `grout.dimensions` is the guard: any dimension value returned
  by Tunabrain that is not in the Tunarr Scheduler vocabulary is dropped
  before it becomes a `dim:value` tag or sets a row's `channel`, and the
  drop is logged. This applies to both enrichment paths — per-file
  (`/enrich/short-form`) and directory (`/enrich/profile`). A dimension
  with no configured vocabulary passes through untouched (we can't judge
  values we have no vocabulary for), so an empty catalog (Tunarr
  Scheduler unreachable) disables the check rather than dropping
  everything. This mirrors Tunarr Scheduler's own
  `curation.dimensions` guard — the two services share the vocabulary,
  so Grout is the second line of defence on the same rule.

- **Tunabrain has no generic chat-completions endpoint.** Per design
  decision (2026-07-07), Grout does NOT use the OpenAI
  `/v1/chat/completions` shape. All Grout → Tunabrain traffic is
  typed-purposeful endpoints. See the design doc at
  `/opt/data/home/docs/grout-tunabrain-enrichment-requirements.md`.

- **AI does not set name or description.** Both stay human-only.
  The AI's job is classification (what is it, which dimensions, which
  channel), not naming.

- **v2: long-form orchestrated endpoint** (deferred). A single
  Tunabrain endpoint `POST /enrich/long-form` that orchestrates
  STT + YouTube scraping + categorize + tags as one call and
  returns the merged result, with the `context` stored for replay.
  This is the v2 path for long-form enrichment quality; the current
  two-call `/categorize` + `/tags` flow already works for both
  forms but is noisier on long-form because no STT or YouTube
  grounding is performed.

- **Grout → Tunarr Scheduler dimensions fetch** lives in
  `grout.tunarr-scheduler.clj`; the HTTP client mirrors
  `tunarr.scheduler.tunabrain.clj` style. Single retry-with-backoff
  at startup; failure logs a warning and the enrichment orchestrator
  starts with an empty dim-config (free-form `/tags` still works,
  structured `/categorize` is skipped).

## 10. Lifecycle / retention

The capability Jellyfin never gave us — own the pool size:
- A retention job enforces caps like "keep at most N live items per
  `(channel, kind, duration-bucket)`," superseding oldest first.
- This finally gives teeth to TS's `max-bumpers-per-channel` intent and keeps
  the AI-regenerated pool from growing unbounded.
- Duration buckets can mirror TS (5s/10s/15s) for bumpers; make the bucketing
  configurable.

## 11. Config / deploy

- Env: `DATABASE_URL`, `TUNABRAIN_URL`, `GROUT_MEDIA_DIR` (default
  `/data/media/grout`), playout-profile overrides, retention caps.
- ffmpeg/ffprobe in the container image (nix flake, as TS).
- Integrant components: logger → db (pool + migratus) → tunabrain-client →
  media-store → enrichment-worker → retention-job → http-server.

## 12. Integration changes in other services (call out, don't necessarily do)

- **TS**: repoint the bumper pipeline — after `compose-bumper!`, call Grout
  intake instead of Jellyfin scan/poll + PV collection. This deletes the
  scan-polling, the filename-match fragility, and the collections hack.
- **PV**: add a filler source that queries Grout and streams by path. Packing
  stays in PV.
- **Marquee**: register Grout as a peer service (auth/health/OpenAPI shape).

## 13. Build order

1. **MVP**: schema + migrations, intake (probe + normalize + insert), query
   (tags AND + duration range + random), by-path + range streaming, health.
2. Enrichment worker (Tunabrain) + `PATCH` metadata editing.
3. Retention/lifecycle job.
4. Repoint TS bumper pipeline at Grout.
5. **Later, separable**: YouTube/long-form ingest (yt-dlp download → normalize →
   richer metadata). Same query/stream layer; different ingest. Do not let this
   delay the MVP.

## 14. Open questions for the human (surface, don't guess)

- `channel` match semantics: does a `channel=britannia` query include
  generic/null-channel items, or only exact matches?
- Tag query semantics beyond MVP AND: do we want must/should/must-not?
- Are Grout files ever visible to Jellyfin? (Recommended: no — keep them out of
  Jellyfin scan paths to avoid the churn we're escaping.)
- Streaming: confirm PV will stream by path (preferred) vs. always via the HTTP
  endpoint.

## 15. Testing

- Intake: probe + normalize a non-conforming sample → correct duration/codecs,
  faststart present.
- Query: tag AND + duration-range boundaries (inclusive), random sampling,
  superseded exclusion.
- Streaming: `Range` request returns `206` with correct byte slice and headers.
- Retention: cap enforcement supersedes oldest, respects buckets.
