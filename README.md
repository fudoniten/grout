# Grout — Filler & Interstitial Media Store

Grout is a small storage-and-retrieval service for **filler media** (bumpers,
interstitials, idents, and later orphan long-form clips). It stores media in a
flat structure, filters by **tags + duration**, owns its metadata, and streams
to Pseudovision. See [`GROUT.md`](GROUT.md) for the full brief.

Grout does **retrieval, not scheduling** — packing stays with the caller (PV).

## HTTP API

| Method & path | Purpose |
|---|---|
| `POST /grout/media` | Intake a file on the mount: probe → normalize → insert |
| `GET /grout/media` | Query by `channel`, `tags` (AND), `min_ms`/`max_ms`, `kind`, `random`, `limit` |
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
