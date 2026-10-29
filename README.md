# Grout — Filler & Interstitial Media Store

Grout is a small storage-and-retrieval service for **filler media**: bumpers,
interstitials, idents, and orphan long-form clips (YouTube channels, video
essays) that don't fit Jellyfin's metadata-provider worldview. It stores media
flat, filters by **tags + duration**, owns its metadata, and serves files to
Pseudovision. Metadata is AI-enriched through Tunabrain.

The name: grout fills the gaps between tiles; Grout fills the gaps between
programs.

Ongoing work lives in [PLAN.md](PLAN.md). Conventions and pitfalls for
contributors are in [AGENTS.md](AGENTS.md).

## Design rules

1. **Retrieval, not scheduling.** Grout answers "what filler matches X?" It
   never packs a timeline or decides ordering; that stays with PV.
2. **Serve by path first.** PV is co-mounted on `arr-data` and plays the
   `path` from query results directly. `GET /grout/media/:id/stream` is the
   fallback for callers without the mount.
3. **AI only through Tunabrain.** Grout holds no model credentials. If
   enrichment needs something new, extend Tunabrain's API.
4. **Normalize once, at intake.** Every item is ffprobed and, if needed,
   transcoded to PV's playout profile (`libx264 / yuv420p / aac 192k / 48 kHz
   stereo`, `+faststart`) before it's queryable. Video and audio are judged
   separately: a stream that already matches is stream-copied, so an h264 file
   with off-profile audio only re-encodes its audio. No transcoding on read.
5. **Flat and tag-based.** No seasons, episodes, watch state, or scrapers;
   don't reimplement Jellyfin. Groups (a creator, a series) are expressed as
   `parent-directory:<slug>` tags with a shared directory profile.

## HTTP API

| Method & path | Purpose |
|---|---|
| `POST /grout/media` | Upload media (`multipart/form-data`): hash → probe → normalize → insert. Dedups by content hash (`201` new, `200` matched/retagged/revived) |
| `GET /grout/by-hash/:hash` | Look up an item by SHA-256 of the source bytes (CLI pre-check) |
| `POST /grout/by-fingerprint` | Batch existence check by sampled fingerprint (`{fingerprints: [...]}`, up to 1000). Returns `matches`, one item per stored fingerprint |
| `PUT /grout/media/:id/fingerprint` | Attach a fingerprint to an item that has none (pre-fingerprint items) |
| `GET /grout/media` | Query by `channel`, `tags` (AND), `min_ms`/`max_ms`, `kind`, `random`, `limit`, `offset`. Summaries carry `kind`/`channel`/`description`/`source` so a consumer can sync without per-item fetches |
| `GET /grout/media/:id` | Fetch one |
| `PATCH /grout/media/:id` | Mutate `name`/`description`/`tags`/`channel` |
| `DELETE /grout/media/:id` | Soft-delete (supersede); `?hard=true` unlinks the file |
| `GET /grout/media/:id/stream` | Byte-range HTTP streaming fallback (`Range` → `206`) |
| `GET`/`POST /grout/media/:id/tags` | List / add tags |
| `POST /grout/media/:id/enrich` | Trigger Tunabrain enrichment for one item |
| `POST /grout/enrich-by-tag/:tag` | Derive or refresh a shared profile for every item carrying a tag (usually `parent-directory:<x>`); async unless `wait=true` |
| `GET /grout/directory-profiles` | List directory profiles with item counts and enrichment status |
| `GET`/`PATCH /grout/directory-profiles/:tag` | Read a profile, or override its dimensions/tags by hand (locks it against re-enrichment) |
| `GET /health` | Health/readiness |
| `GET /api/version` | Build info |
| `GET /openapi.json`, `/swagger-ui` | API docs |

Query example:

```
GET /grout/media?channel=britannia&tags=daytime,fun,kids&min_ms=65000&max_ms=90000&random=true&limit=5
```

**Channel semantics:** a `channel=X` query matches that channel **or**
generic (null-channel) items, which are usable on any channel. Response bodies use kebab-case keys (`duration-ms`,
`stream-url`) per the service-wide JSON convention.

### Content-addressed storage

Each item carries a `content-hash` — the **SHA-256 of the original source
bytes** (computed before normalization). Intake is idempotent: submit the same
file again and Grout matches the existing item by hash, unions any new tags,
fills blank metadata, and revives it if it had been superseded — returning
`200` instead of creating a duplicate. Stored (normalized) files live at a
content-addressed path (`<media-dir>/ab/abcd….mp4`); the caller's source file
is never mutated.

This makes tagging safe after the fact: if you upload something and forget to
tag it, just upload again with tags. A CLI can hash the local file, `GET
/grout/by-hash/:hash` to see whether an upload is even needed, and on a hit
simply add tags via `PATCH`/`POST …/tags` without re-uploading (see
`grout-cli` below, which does exactly this).

Items also carry a `fingerprint`: `fp1:` + SHA-256 over the file size and
three 64 KiB samples (start, middle, end) of the original bytes. It reads at
most 192 KiB per file, so clients use it for the "is this already stored?"
check (`POST /grout/by-fingerprint`) instead of a full hash. Two different
media files matching on size and all three samples is vanishingly rare but
possible, so `content-hash` stays the authoritative dedup key at intake.
Items stored before fingerprints existed have none until a client that matched
them by full hash attaches one.

## AI enrichment

A background worker picks up un-enriched items and calls Tunabrain's
`/enrich/short-form`; the directory worker calls `/enrich/profile` once per
`parent-directory:` group and fans the result out to every item in it.

- **Dimensions come from Tunarr Scheduler.** At startup Grout fetches
  `GET /api/dimensions` and each dimension's values from TS, and sends that
  catalog to Tunabrain. Per-dimension descriptions live in
  `resources/config.edn` (`:dimension-descriptions`). If TS is unreachable,
  enrichment runs with an empty catalog: free-form tags still work and
  dimension validation is skipped.
- **Dimension values are validated on the way back.** `grout.dimensions` drops
  any value Tunabrain returns that isn't in TS's vocabulary (and logs it)
  before it becomes a `dim:value` tag or sets `channel`. TS's
  `curation.dimensions` applies the same rule.
- **AI fills `name` and `description` only when they're blank.** Human edits
  are never overwritten. A directory profile you `PATCH` by hand is locked
  until `force=true` or `{"locked": false}`.

## Configuration

Environment variables (see `resources/config.edn` for the full set and defaults):

| Var | Purpose |
|---|---|
| `GROUT_DATABASE_URL` / `DATABASE_URL` | JDBC URL |
| `GROUT_DATABASE_USER` / `GROUT_DATABASE_PASS` | DB credentials |
| `GROUT_MEDIA_DIR` | Blob directory on the arr-data mount (default `/data/media/grout`) |
| `GROUT_STAGING_DIR` | Upload staging directory (default `/data/media/grout/.staging`); spooled uploads are deleted as soon as intake finishes, so this only needs headroom for in-flight uploads. Point it at a real volume — it defaults away from the JVM's temp dir, which is often a small ramdisk in containers |
| `TUNABRAIN_URL` | Tunabrain gateway endpoint |
| `TUNARR_SCHEDULER_URL` | Source of the dimension catalog (blank disables dimension validation) |
| `GROUT_FFMPEG_ACCEL` | Hardware acceleration for intake transcodes (default `auto`) |
| `GROUT_MAX_TRANSCODES` | Most intakes re-encoding video at once; the rest queue (default `2`). Stream copies don't count |
| `GROUT_HTTP_PORT` | HTTP port (default 8080) |
| `GROUT_ENRICHMENT_ENABLED`, `GROUT_DIRECTORY_ENRICHMENT_ENABLED`, `GROUT_RETENTION_ENABLED` | Toggle background jobs |

Finer knobs (intervals, retention cap/bucket, playout profile) are set in
`config.edn` or an override file passed with `-c`.

Video bytes live on the mount, **never** in Postgres. `ffmpeg`/`ffprobe` must be
on `PATH` (the nix flake provides them and sets `FFMPEG_PATH`/`FFPROBE_PATH`).

## CLI (`grout-cli`)

A Babashka CLI for tagging/uploading filler media, built by the flake as
`grout-cli` (`nix run .#grout-cli -- ...` or add the `grout-cli` package to
your profile). No shared filesystem with the server is required — it works
from any desktop or CI runner that can reach the Grout HTTP endpoint.

It fingerprints every file and checks them all in one `POST
/grout/by-fingerprint`. A file that doesn't match gets a full SHA-256 and a
`GET /grout/by-hash/:hash`, which catches items stored before fingerprints
existed (on a hit the CLI attaches the fingerprint, so the next run is fast).
Then it either adds tags to the existing item or intakes it as new. Re-running
over already-uploaded files costs about 192 KiB of reads per file, so the CLI
keeps no local state (`--manifest` is accepted but ignored). Every file also gets a
`filename:<basename>` tag by default, so the original name stays searchable.

```sh
grout-cli --tags=daytime,fun --tag=kids bumper1.mp4 bumper2.mp4
GROUT_URL=http://grout:8080 grout-cli --kind=bumper --channel=britannia ident.mp4
grout-cli --dry-run --json *.mp4   # preview without uploading/tagging
grout-cli --help
```

Server URL comes from `-s`/`--server` or `GROUT_URL`. See `grout-cli --help`
for the full option list (`--kind`, `--channel`, `--source`, `--source-url`,
`--name`, `--description`, `--no-filename-tag`, `--dry-run`, `--json`,
`--verbose`).

## Bulk uploads (`grout-bulk`)

`grout-bulk` is a resumable, trackable driver around `grout-cli` for uploading a
large collection — hundreds of thousands of files — a directory at a time. It's
built by the flake as `grout-bulk` (and bundled into the `kube-util` image,
which mounts the media filesystem). It walks a content root, and for **every
directory that directly contains files** it runs
`grout-cli --upload-dir <dir> --json`, capturing the per-file JSON as a
per-directory manifest and recording per-directory progress in a JSON state
file. A crash, kill, or pod eviction resumes without redoing finished work: a
directory already marked `done` is skipped entirely. The state is a convenience
for progress and `retry`, not a correctness requirement: since grout-cli's
fingerprint check makes re-running a finished directory cheap, losing or
`reset`-ing the state file just means one quick re-check pass.

Because `grout-cli --upload-dir` is non-recursive and keys its shared enrichment
profile on the *parent* of each leaf, the common pinchflat layout
(`Content/<Creator>/<Year>/<files>`) maps cleanly: one upload unit per year
folder, one shared `parent-directory:<creator>` profile. Point `--root` at a
single creator or at all of `Content/` — discovery handles both.

```sh
# Upload a creator's tree, 4 directories in flight, state on a persistent mount
grout-bulk run -r '/pinchflat-media/Content/Adam Neely' -s http://grout:8080 \
  -d /mnt/grout-bulk -j 4

grout-bulk status -r '/pinchflat-media/Content/Adam Neely' -d /mnt/grout-bulk
grout-bulk retry  -r '/pinchflat-media/Content/Adam Neely' -d /mnt/grout-bulk -j 4
grout-bulk --help
```

Commands: `run` (resumes; skips `done`, re-runs crashed `in_progress`), `retry`
(re-runs only `failed` directories, e.g. after a Grout outage), `status`
(counts + per-directory table; `--json` for `jq`), `reset` (drop a root's state
file; the next run re-checks every directory by fingerprint). Anything after
`--` is forwarded verbatim to every `grout-cli` call
(`grout-bulk run -r … -- --kind=filler --tag=music`). The server comes from
`-s`/`--server` or `$GROUT_URL`; keep `--state-dir` on a persistent volume,
since the container filesystem is not. See `grout-bulk --help` for all options.

## Running and testing

Needs PostgreSQL, a writable `GROUT_MEDIA_DIR`, Java 21+, and `ffmpeg`/`ffprobe`
on `PATH` (`nix develop .#grout` provides them).

```sh
clojure -X:migrate            # apply migrations
clojure -M:run                # start the service (config: resources/config.edn)
clojure -M:run -c my.edn      # with an override config
clojure -M:test               # server test suite (Kaocha)
bb test                       # grout-cli / grout-bulk tests (Babashka)
nix flake check               # server tests + clj-kondo lint
```

Integrant components:
`logger → db → tunarr-scheduler → dim-catalog → tunabrain → media → enrichment-worker / directory-worker / retention-job → http`.

`archive/GROUT.md` is the original design brief, kept because code comments
cite its sections.
