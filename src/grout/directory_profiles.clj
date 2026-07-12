(ns grout.directory-profiles
  "Persistence + pure helpers for the `directory_profiles` table.

   A directory profile holds the LLM-derived `dimensions` + `tags` for one
   cross-cutting tag group (typically a `parent-directory:<x>` tag). The
   directory-enrichment worker computes it once per group and fans it out to
   every child `grout_media` row (see `grout.media.store/apply-directory-profile!`),
   replacing the per-file `/enrich/short-form` call for bulk imports.

   Plain next.jdbc + parameterized SQL (rather than honeysql): the ON CONFLICT
   upsert, the JSONB columns, and the array-indexed backoff are all clearer as
   literal SQL, and this table has no honeysql query-builder to share."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.postgresql.util PGobject]))

;; Retry backoff schedule (seconds): 1m, 5m, 30m, 2h, 12h. The Nth failure
;; schedules the next attempt at index N (1-based); past the end of the array
;; Postgres subscripting yields NULL, so `next_retry_at` becomes NULL and the
;; profile is abandoned (left `failed` for manual intervention). Kept as a SQL
;; literal in `mark-failed!` — this var documents the intent.
(def ^:private backoff-seconds [60 300 1800 7200 43200])

(defn- ->jsonb
  "Serialise `x` to a jsonb-typed PGobject for a JSONB column parameter."
  [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string x))))

(defn- coerce-jsonb
  "Parse a JSONB column value to Clojure data. Handles a PGobject, a raw String,
   or an already-parsed value (when the global next.jdbc ReadableColumn
   extension in `grout.media.store` is loaded). nil stays nil."
  [v]
  (cond
    (nil? v)                nil
    (instance? PGobject v)  (json/parse-string (.getValue ^PGobject v) true)
    (string? v)             (json/parse-string v true)
    :else                   v))

(defn- coerce-dimensions
  "Parse the `dimensions` JSONB column to a Clojure map and canonicalize its
  top-level keys to keywords. jsonb round-trips object keys as strings, but the
  OpenAPI response schema ([:map-of :keyword …]) and the worker consumers
  expect `:channel`/`:audience`/… — a string-keyed map trips response coercion
  (a live 500 on GET /grout/directory-profiles/<tag>). nil, and defensively any
  non-map (mis-shaped legacy) value, become nil rather than 500."
  [v]
  (let [parsed (coerce-jsonb v)]
    (when (map? parsed)
      (update-keys parsed keyword))))

(defn- coerce-tags
  "Parse the `tags` JSONB column to a vector of strings (or nil). The normal
  shape is a JSON array of strings, but a mis-shaped row (a scalar or object in
  the tags column, e.g. from a hand-edited or legacy profile) must not trip the
  [:maybe [:vector :string]] response schema and 500 the endpoint — the tags
  analog of the dimensions coercion above. A JSON array is stringified with
  blanks dropped; a lone scalar becomes a single-element vector; an object (not
  representable as tags) and nil become nil."
  [v]
  (let [parsed (coerce-jsonb v)]
    (cond
      (nil? parsed)        nil
      (sequential? parsed) (->> parsed (map str) (map str/trim) (remove str/blank?) vec)
      (map? parsed)        nil
      :else                (let [s (str/trim (str parsed))]
                             (when-not (str/blank? s) [s])))))

(defn ->profile
  "Coerce a raw row map into a profile: the `dimensions` and `tags` JSONB
  columns parsed to Clojure data and normalized to the OpenAPI response shape
  (`dimensions` as a keyword-keyed map, `tags` as a vector of strings). Both are
  defensive against mis-shaped rows so a bad profile reports nothing rather than
  500ing the read endpoint (see `coerce-dimensions`/`coerce-tags`)."
  [row]
  (when row
    (-> row
        (update :dimensions coerce-dimensions)
        (update :tags coerce-tags))))

(defn- exec-one [ds sql-vec]
  (jdbc/execute-one! ds sql-vec {:builder-fn rs/as-unqualified-lower-maps}))

(defn- exec [ds sql-vec]
  (jdbc/execute! ds sql-vec {:builder-fn rs/as-unqualified-lower-maps}))

;; --- Pure helpers (unit-testable without a DB) -----------------------------

(defn profile->tags
  "Expand a profile's `dimensions` map and free-form `tags` into a flat tag
   vector using the `dim:value` prefix convention (matching per-file enrichment
   and the PV tag namespace). E.g. {:channel [\"muse\"]}, [\"jazz\"] ->
   [\"channel:muse\" \"jazz\"]. Blanks are dropped; order is dimensions then tags."
  [dimensions tags]
  (let [dim-tags (for [[dim values] dimensions
                       v            values
                       :let [v (some-> v str str/trim)]
                       :when (and v (not (str/blank? v)))]
                   (str (name dim) ":" v))
        free     (->> tags (map str) (map str/trim) (remove str/blank?))]
    (vec (distinct (concat dim-tags free)))))

(defn profile-channel
  "The channel column value implied by a profile: the first value of the
   `channel` dimension, or nil when the profile sets no channel."
  [dimensions]
  (some-> (or (:channel dimensions) (get dimensions "channel"))
          first
          str
          not-empty))

(defn growth-exceeded?
  "True when `current-count` has grown more than `threshold-pct` beyond the
   count at last enrichment — the signal to re-enrich. Shrinkage never triggers
   (a smaller group's existing profile is still valid)."
  [{:keys [item_count_at_enrichment]} current-count threshold-pct]
  (> current-count
     (* (or item_count_at_enrichment 0) (+ 1 (/ (double threshold-pct) 100.0)))))

;; --- Table access ----------------------------------------------------------

(defn get-profile-for-tag
  "Fetch the profile for `tag-value`, or nil. JSONB columns are parsed."
  [ds tag-value]
  (->profile (exec-one ds ["SELECT * FROM directory_profiles WHERE tag_value = ?"
                           tag-value])))

(defn ensure-profile!
  "Ensure a `pending` profile row exists for `tag-value` with `concept-name`,
   race-free via the UNIQUE(tag_value) constraint. First writer wins the
   concept name. Returns the (possibly pre-existing) profile."
  [ds tag-value concept-name]
  (exec-one ds ["INSERT INTO directory_profiles (tag_value, concept_name)
                 VALUES (?, ?) ON CONFLICT (tag_value) DO NOTHING"
                tag-value concept-name])
  (get-profile-for-tag ds tag-value))

(defn mark-pending!
  "Reset a profile to `pending` (from ready or failed) so the worker re-enriches
   it. Clears the retry backoff. Returns the updated profile."
  [ds tag-value]
  (->profile (exec-one ds ["UPDATE directory_profiles
                            SET status = 'pending', next_retry_at = NULL, updated_at = now()
                            WHERE tag_value = ? RETURNING *"
                           tag-value])))

(defn mark-ready!
  "Persist a successful enrichment: store `dimensions`/`tags`, record the count
   at enrichment (for the growth threshold), clear the error/backoff, and flip
   status to `ready`. Returns the updated profile."
  [ds tag-value dimensions tags current-count]
  (->profile (exec-one ds ["UPDATE directory_profiles
                            SET status = 'ready',
                                dimensions = ?, tags = ?,
                                item_count_at_enrichment = ?,
                                error = NULL, next_retry_at = NULL,
                                enrichment_attempts = 0,
                                last_enrichment_at = now(), updated_at = now()
                            WHERE tag_value = ? RETURNING *"
                           (->jsonb dimensions) (->jsonb tags)
                           current-count tag-value])))

(defn mark-failed!
  "Record a failed enrichment: set the error, increment the attempt count, and
   schedule the next retry from the backoff array (NULL once exhausted -> the
   profile is abandoned). Returns the updated profile."
  [ds tag-value error-message]
  (->profile (exec-one ds [(str "UPDATE directory_profiles
                                 SET status = 'failed', error = ?,
                                     enrichment_attempts = enrichment_attempts + 1,
                                     next_retry_at = now() + "
                                "     ((ARRAY[60,300,1800,7200,43200])[enrichment_attempts + 1] * interval '1 second'),"
                                "     last_enrichment_at = now(), updated_at = now()
                                 WHERE tag_value = ? RETURNING *")
                           error-message tag-value])))

(defn pending-profiles
  "Up to `limit` profiles awaiting a first (or forced) enrichment, oldest first."
  [ds limit]
  (mapv ->profile
        (exec ds ["SELECT * FROM directory_profiles
                   WHERE status = 'pending' ORDER BY created_at LIMIT ?"
                  limit])))

(defn failed-profiles-ready-for-retry
  "Up to `limit` failed profiles whose backoff has elapsed, soonest-due first.
   Profiles with a NULL next_retry_at (backoff exhausted) are excluded."
  [ds limit]
  (mapv ->profile
        (exec ds ["SELECT * FROM directory_profiles
                   WHERE status = 'failed' AND next_retry_at IS NOT NULL
                     AND next_retry_at <= now()
                   ORDER BY next_retry_at LIMIT ?"
                  limit])))
