# Grout — Filler & Interstitial Media Store

Grout is a small storage-and-retrieval service for **filler media** (bumpers,
interstitials, idents, and later orphan long-form clips). It stores media in a
flat structure, filters by **tags + duration**, owns its metadata, and streams
to Pseudovision. See [`GROUT.md`](GROUT.md) for the full brief.

Grout does **retrieval, not scheduling** — packing stays with the caller (PV).

## HTTP API

| Method & path | Purpose |
|---|---|
| `POST /grout/media` | Upload media (`multipart/form-data`): hash → probe → normalize → insert. No shared filesystem needed between caller and server. Dedups by content hash (`201` new, `200` matched/retagged/revived) |
| `GET /grout/by-hash/:hash` | Look up an item by SHA-256 of the source bytes (CLI pre-check) |
| `GET /grout/media` | Query by `channel`, `tags` (AND), `min_ms`/`max_ms`, `kind`, `random`, `limit`, `offset` (paginate a stable listing for bulk sync). Item summaries carry `kind`/`channel`/`description`/`source` so a consumer can sync them without a per-item fetch. |
| `GET /grout/media/:id` | Fetch one |
| `PATCH /grout/media/:id` | Mutate `name`/`description`/`tags`/`channel` |
| `DELETE /grout/media/:id` | Soft-delete (supersede); `?hard=true` unlinks the file |
| `GET /grout/media/:id/stream` | Byte-range HTTP streaming fallback (`Range` → `206`) |
| `GET`/`POST /grout/media/:id/tags` | List / add tags |
| `POST /grout/media/:id/enrich` | Trigger Tunabrain metadata enrichment |
| `GET /health` | Health/readiness |
| `GET /api/version` | Build info |
| `GET /openapi.json`, `/swagger-ui` | API docs |

Query example:

```
GET /grout/media?channel=britannia&tags=daytime,fun,kids&min_ms=65000&max_ms=90000&random=true&limit=5
```

**Channel semantics:** a `channel=X` query matches that channel **or**
generic (null-channel) items, which are usable on any channel (resolves
`GROUT.md` §14). Response bodies use kebab-case keys (`duration-ms`,
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

## Configuration

Environment variables (see `resources/config.edn` for the full set and defaults):

| Var | Purpose |
|---|---|
| `GROUT_DATABASE_URL` / `DATABASE_URL` | JDBC URL |
| `GROUT_DATABASE_USER` / `GROUT_DATABASE_PASS` | DB credentials |
| `GROUT_MEDIA_DIR` | Blob directory on the arr-data mount (default `/data/media/grout`) |
| `TUNABRAIN_URL` | Tunabrain gateway endpoint |
| `GROUT_HTTP_PORT` | HTTP port (default 8080) |
| `GROUT_ENRICHMENT_ENABLED`, `GROUT_RETENTION_ENABLED` | Toggle background jobs |

Finer knobs (intervals, retention cap/bucket, playout profile) are set in
`config.edn` or an override file passed with `-c`.

Video bytes live on the mount, **never** in Postgres. `ffmpeg`/`ffprobe` must be
on `PATH` (the nix flake provides them and sets `FFMPEG_PATH`/`FFPROBE_PATH`).

## CLI (`grout-cli`)

A Babashka CLI for tagging/uploading filler media, built by the flake as
`grout-cli` (`nix run .#grout-cli -- ...` or add the `grout-cli` package to
your profile). No shared filesystem with the server is required — it works
from any desktop or CI runner that can reach the Grout HTTP endpoint.

For each file it hashes the bytes with the same SHA-256 the server uses for
its content-hash dedup key, checks `GET /grout/by-hash/:hash`, and either
adds tags to the existing item or intakes it as new. Every file also gets a
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
directory already marked `done` is skipped entirely, so **its files are never
re-hashed**. Path is treated as identity — nothing re-reads bytes to decide
whether a directory is already uploaded.

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
file — logs and grout-cli's by-hash dedup remain the safety net). Anything after
`--` is forwarded verbatim to every `grout-cli` call
(`grout-bulk run -r … -- --kind=filler --tag=music`). The server comes from
`-s`/`--server` or `$GROUT_URL`; keep `--state-dir` on a persistent volume,
since the container filesystem is not. See `grout-bulk --help` for all options.

## Running

```sh
clojure -X:migrate            # apply migrations
clojure -M:run                # start the service
clojure -M:run -c my.edn      # with an override config
clojure -M:test               # run the test suite (kaocha)
```

The nix flake builds the service and migration containers and runs tests/lint as
flake checks.

## Integrant components

`logger → db → tunabrain → media (store) → enrichment-worker → retention-job → http`

## Build-order status (`GROUT.md` §13)

- ✅ MVP: schema/migrations, intake (probe + normalize + faststart), query,
  by-path + range streaming, health
- ✅ Enrichment worker (Tunabrain) + `PATCH` metadata editing
- ✅ Retention/lifecycle job
- ☐ Repoint TS bumper pipeline at Grout (lives in the TS service)
- ☐ YouTube/long-form ingest (later, separable)
