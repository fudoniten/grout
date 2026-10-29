# Grout plan

Last reviewed: 2026-10-29

This is the one place for ongoing work on Grout: what's being worked on,
what's next, known issues, and the decisions that still shape the code. Delete
items once they're done (git history keeps them). Cross-repo priorities live
in the [ecosystem plan](https://github.com/fudoniten/pseudovision/blob/master/PLAN.md#ecosystem).

## Now

- [ ] Send long-form `program` items to Tunabrain's `/enrich/long-form`
      instead of `/enrich/short-form` (ecosystem step 3). Tunabrain already
      implements it.
- [ ] Add GitHub CI that runs `nix flake check` (ecosystem step 4).

## Next

- [ ] Have PV fetch Grout clips over HTTP, or document the shared `arr-data`
      mount as a hard deployment requirement. Also decide how PV handles clips
      Grout has superseded or hard-deleted.
- [ ] Add `bb test` (the CLI tests) to the flake checks.

## Later

- YouTube / long-form ingest inside Grout (yt-dlp download → normalize →
  richer metadata). Today long-form arrives through `grout-bulk` from an
  external downloader (pinchflat).
- Auth on write and delete routes (`DELETE ?hard=true` is currently open),
  once development winds down.
- Richer tag queries (must / should / must-not) if AND turns out too blunt.
- Merge `grout-cli` with pvcli's Grout commands (see pvcli's plan).

## Known issues

- No service-level auth: anyone who can reach Grout can upload, edit, or
  hard-delete. Acceptable while `*.sea.fudo.link` is local-only behind NAT.

## Decisions

- `channel=X` matches that channel **or** generic (null-channel) items.
- Grout files stay out of Jellyfin scan paths.
- 2026-07-07: Grout talks to Tunabrain only through typed, purpose-built
  endpoints, never a generic chat-completions API.
- TS is the source of truth for the dimension vocabulary; Grout validates
  against it rather than keeping its own.
- 2026-10-29: intake normalizes per stream (`probe/stream-plan`). A conforming
  video or audio stream is copied, never re-encoded, so a file with only bad
  audio skips the video encode. Silent files count as audio-conforming.
- 2026-10-29: video encodes are capped (`GROUT_MAX_TRANSCODES`, default 2,
  sized for the P2200 that PV also uses for live playout) and each ffmpeg run
  is killed after 5× the media duration (30-minute floor).
- 2026-10-29: the per-file enrichment sweep orders by `enrich_attempted_at`
  (never-tried first), so rows Tunabrain can't enrich rotate to the back
  instead of blocking the queue. Failing rows are retried forever, just less
  often; add an attempt cap if that turns out noisy.
- 2026-10-29: clients check existence by sampled fingerprint (`fp1:`, size +
  three 64 KiB samples) via `POST /grout/by-fingerprint`, falling back to a
  full hash only on a miss. Peter accepted the tiny collision risk (~99.5%
  was the bar). `content_hash` stays the intake dedup key. grout-cli's
  `--manifest` hash cache was removed for it.
