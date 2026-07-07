(ns grout.media.enrich
  "Enrichment orchestration (GROUT.md §9): read a row, ask Tunabrain to
  enrich it via /enrich/short-form, merge, and persist with
  `enriched=true`.

  The Tunabrain composite endpoint orchestrates describe + categorize +
  tags internally. Grout consumes the response:

    * `:media` is the request echo (caller's working title).
    * `:describe` carries the AI-refined title + description
      (`{id, title, description}` or `null` if the step failed).
    * `:dimensions` is the structured dimension selections.
    * `:tags` is the free-form tag list.
    * `:context` is the MediaContext Tunabrain actually used; we
      persist it into `enrichment_context` for replay on retry.

  The merge step applies the never-clobber-a-human-set-value rule to
  `:name` and `:description`: AI fills them in only when the row's
  existing value is empty. Tags are unioned (existing + AI), never
  dropped. See `merge-enrichment` for the full contract."
  (:require [clojure.string :as str]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]
            [taoensso.timbre :as log]))

(defn- union-tags
  "Concatenate `existing` and `incoming` tag vectors, drop blanks,
  dedup (preserve first-seen order). Returns a vector of strings."
  [existing incoming]
  (->> (concat (vec (or existing [])) (vec (or incoming [])))
       (map str)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn merge-enrichment
  "Merge an existing row with the AI response from
  `request-enrich-short-form!`. The result is the payload to write via
  `store/set-enriched!`:

    * `:tags` — union of row's existing tags and the AI-derived tags
      (incl. the dimension-as-tag prefix expansion). Human-set tags
      are preserved.
    * `:name` — set ONLY if the AI provided a non-blank title AND the
      row had no existing name. The key is omitted from the payload
      entirely otherwise, so `set-enriched!` cannot clobber a
      human-set value. (Writing the same value back would also be a
      no-op but a needless UPDATE.)
    * `:description` — same shape as `:name`.
    * `:enrichment-context` — the `MediaContext` Tunabrain returned,
      stored as JSONB and replayed on retry.
    * `:enrichment-grounding-source` — the `context.source` value
      (provided-text / provided-summary / provided-link / wikipedia /
      none). Diagnostic only."
  [row resp]
  (let [existing-tags  (vec (:tags row))
        dim-tag-prefix (tb/dimension-selections->tag-prefix
                         (:dimensions resp))
        new-tags       (:tags resp)
        merged-tags    (union-tags existing-tags
                                     (concat dim-tag-prefix new-tags))
        describe       (:describe resp)
        ai-name        (when describe (:title describe))
        ai-description (when describe (:description describe))
        last-context   (:context resp)
        ;; `:name` is included only when the AI is filling a missing value.
        row-has-name?        (and (:name row)        (not (str/blank? (:name row))))
        row-has-description? (and (:description row) (not (str/blank? (:description row))))
        ai-has-name?         (and ai-name            (not (str/blank? ai-name)))
        ai-has-description?  (and ai-description     (not (str/blank? ai-description)))
        include-name?        (and ai-has-name?         (not row-has-name?))
        include-description? (and ai-has-description?  (not row-has-description?))]
    (cond-> {:tags                       merged-tags
             :enrichment-context         last-context
             :enrichment-grounding-source (or (:source last-context) "none")}
      include-name?        (assoc :name        ai-name)
      include-description? (assoc :description ai-description))))

(defn- with-existing-context
  "Pick the right `MediaContext` to send to Tunabrain on a new attempt.

  Priority:
    1. The context stored in the row from the last enrichment
       (`enrichment_context`) — this is what was last echoed back by
       Tunabrain, possibly corrected by a human in the UI.
    2. nil — first attempt; Tunabrain will use its default
       Wikipedia/auto-search grounding (which is wrong for short-form
       content, but we send the human-set `name` as `title` and let
       Tunabrain's category validation work).

  Returns nil if the row has no stored context (caller passes nil to
  `request-enrich-short-form!` to opt out)."
  [row]
  (when-let [ctx (:enrichment_context row)]
    (when (map? ctx)
      ctx)))

(defn enrich-one!
  "Enrich a single media row via Tunabrain. Returns the updated row, or
  nil when:
    * the row is missing or superseded (and `include-superseded?` is false), or
    * the AI returned no usable dimensions AND no new tags (i.e. the
      AI had nothing to add — we leave the row as `enriched=false` so
      a later sweep can retry after a UI correction).

  Tunabrain HTTP errors are caught and logged; the row is left
  `enriched=false` for the next sweep.

  Arguments:
    ds              — a `javax.sql.DataSource`
    tunabrain       — a `TunabrainClient` (from `grout.tunabrain/create`)
    dim-config      — a map of `dimension-name -> {:description ... :values [...]}`
                      for the `/categorize` call. Built by the system
                      layer from a static config (audience) plus the
                      dynamic channel catalog fetched from Tunarr Scheduler.
    id              — the row id to enrich."
  [ds tunabrain dim-config id]
  (when-let [row (store/find-by-id ds id {:include-superseded? true})]
    (let [ctx (with-existing-context row)]
      (try
        (let [resp (tb/request-enrich-short-form! tunabrain row dim-config
                                                  (:tags row)
                                                  :context ctx)
              merged   (merge-enrichment row resp)
              ;; Only flip enriched=true if the AI actually contributed
              ;; something (a dimension or a new tag). If the model
              ;; returned nothing, the row stays enriched=false so a
              ;; later sweep can retry.
              nothing-new? (and (empty? (:dimensions resp))
                                (empty? (:tags resp)))]
          (if nothing-new?
            (do (log/warn "No enrichment produced; leaving enriched=false"
                          {:id id})
                nil)
            (do (log/info "Enriched media"
                          {:id id
                           :dimensions (count (:dimensions resp))
                           :tags (count (:tags resp))
                           :grounding-source (:enrichment-grounding-source merged)})
                (store/set-enriched! ds id merged))))
        (catch Exception e
          (log/warn e "Tunabrain enrichment call failed; leaving enriched=false"
                    {:id id})
          nil)))))
