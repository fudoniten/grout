(ns grout.media.store
  "Persistence layer for `grout_media` rows.

   Pure next.jdbc + honeysql; no HTTP concerns. Column keywords are snake_case
   to mirror the database (`:duration_ms`, `:source_url`, ...). The HTTP layer is
   responsible for shaping these into the API's kebab-case response bodies."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Array]
           [org.postgresql.util PGobject]))

;; Postgres `text[]` columns come back as java.sql.Array; unwrap to a vector so
;; callers always see a plain Clojure collection of strings.
(extend-protocol rs/ReadableColumn
  Array
  (read-column-by-label [^Array v _] (vec (.getArray v)))
  (read-column-by-index [^Array v _ _] (vec (.getArray v))))

(defn- as-jsonb
  "Wrap a JSON-serialised string in a `PGobject` typed as `jsonb` so the
  Postgres driver writes it as JSONB (with `::jsonb` cast) instead of
  trying to escape it as a string literal. Required because plain
  text-typed parameters get `error: column ... is of type jsonb`."
  [^String s]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue s)))

;; JSONB columns come back as either a `PGobject` (most JDBC drivers) or a raw
;; String (some configurations). Read both as parsed Clojure data so the
;; orchestrator can compare, log, and re-send the `MediaContext` without
;; caring about the driver representation.
(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [v _]
    (let [t (.getType v)]
      (if (= "jsonb" t)
        (try (json/parse-string (.getValue v) true)
             (catch Exception _ (.getValue v)))
        (.getValue v))))
  (read-column-by-index [v _ _]
    (let [t (.getType v)]
      (if (= "jsonb" t)
        (try (json/parse-string (.getValue v) true)
             (catch Exception _ (.getValue v)))
        (.getValue v)))))

;; `@>` (array containment) is not a built-in honeysql operator; register it so
;; we can express "tags contains all of" as `[:@> :tags ...]`. `@` is not a
;; valid Clojure keyword or symbol character, so the keyword is built via
;; `(keyword ...)` and the operator is bound to a regular local name.
(def ^:private at>-op (keyword "@>"))
(sql/register-op! at>-op)

(defn- text-array
  "honeysql fragment casting a Clojure collection to a Postgres `text[]`.
   Explicit cast avoids `text[] @> varchar[]` operator-mismatch errors."
  [xs]
  [:cast [:array (vec xs)] [:raw "text[]"]])

(def ^:private insertable-keys
  [:kind :path :content_hash :name :description :channel :tags :duration_ms
   :width :height :vcodec :acodec :source :source_url :enriched])

(defn- exec-one [ds sqlmap]
  (jdbc/execute-one! ds (sql/format sqlmap)
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn- exec [ds sqlmap]
  (jdbc/execute! ds (sql/format sqlmap)
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn create!
  "Insert a media row and return it. `m` uses snake_case column keywords;
   `:tags` (if present) is a vector of strings."
  [ds m]
  (let [row (-> (select-keys m insertable-keys)
                (assoc :tags (text-array (:tags m []))))]
    (exec-one ds {:insert-into :grout_media
                  :values [row]
                  :returning [:*]})))

(defn find-by-id
  "Fetch one row by id. Excludes superseded rows unless
   `:include-superseded?` is true. Returns nil when not found."
  ([ds id] (find-by-id ds id {}))
  ([ds id {:keys [include-superseded?]}]
   (exec-one ds {:select [:*]
                 :from :grout_media
                 :where (if include-superseded?
                          [:= :id id]
                          [:and [:= :id id] [:= :superseded_at nil]])})))

(defn find-by-hash
  "Fetch a row by content hash, including superseded rows (so intake can revive
   a previously superseded item). Returns nil when not found."
  [ds content-hash]
  (exec-one ds {:select [:*]
                :from   :grout_media
                :where  [:= :content_hash content-hash]}))

(defn update-fields!
  "Set arbitrary columns on a row by id and return it. `:tags` (if present) is
   coerced to text[]. Intended for intake dedup (retag/revive)."
  [ds id fields]
  (let [set-map (cond-> fields
                  (contains? fields :tags) (assoc :tags (text-array (:tags fields))))]
    (exec-one ds {:update :grout_media
                  :set    set-map
                  :where  [:= :id id]
                  :returning [:*]})))

(defn ->query-sqlmap
  "Build the honeysql map for a media query. Pure, so it is unit-testable
   without a database.

   Params (all optional): :channel :tags(seq) :min-ms :max-ms :kind
   :limit(default 10) :random :include-superseded?

   Channel semantics: a channel filter matches the given channel OR
   generic (null-channel) items, which are usable on any channel."
  [{:keys [channel tags min-ms max-ms kind limit random include-superseded?]
    :or   {limit 10}}]
  (let [conds (cond-> []
                (not include-superseded?) (conj [:= :superseded_at nil])
                channel    (conj [:or [:= :channel channel] [:= :channel nil]])
                (seq tags) (conj [at>-op :tags (text-array tags)])
                min-ms     (conj [:>= :duration_ms min-ms])
                max-ms     (conj [:<= :duration_ms max-ms])
                kind       (conj [:= :kind kind]))]
    (cond-> {:select [:*] :from :grout_media :limit limit}
      (seq conds)  (assoc :where (into [:and] conds))
      random       (assoc :order-by [[[:raw "random()"]]])
      (not random) (assoc :order-by [[:created_at :desc]]))))

(defn query
  "Run a media query (see ->query-sqlmap) and return the matching rows."
  [ds params]
  (exec ds (->query-sqlmap params)))

(defn update-metadata!
  "Patch mutable metadata (name/description/channel/tags) on a live row.
   Returns the updated row, or nil when the row is absent/superseded or the
   patch has no recognised fields."
  [ds id patch]
  (let [set-map (cond-> (select-keys patch [:name :description :channel])
                  (contains? patch :tags) (assoc :tags (text-array (:tags patch))))]
    (when (seq set-map)
      (exec-one ds {:update :grout_media
                    :set    set-map
                    :where  [:and [:= :id id] [:= :superseded_at nil]]
                    :returning [:*]}))))

(defn soft-delete!
  "Set `superseded_at = now()` on a live row. Returns the row, or nil if it was
   already absent/superseded."
  [ds id]
  (exec-one ds {:update :grout_media
                :set    {:superseded_at [:now]}
                :where  [:and [:= :id id] [:= :superseded_at nil]]
                :returning [:*]}))

(defn hard-delete!
  "Delete a row outright (any state) and return it, so the caller can unlink the
   backing file. Returns nil when not found."
  [ds id]
  (exec-one ds {:delete-from :grout_media
                :where [:= :id id]
                :returning [:*]}))

(defn add-tag!
  "Append `tag` to a live row's tags (idempotent). Returns the updated row, or
   nil when absent/superseded."
  [ds id tag]
  (exec-one ds {:update :grout_media
                :set    {:tags [:case [at>-op :tags (text-array [tag])] :tags
                                 :else [:array_append :tags tag]]}
                :where  [:and [:= :id id] [:= :superseded_at nil]]
                :returning [:*]}))

(defn unenriched
  "Return up to `limit` live rows still awaiting per-file enrichment.

  Excludes rows carrying any `parent-directory:` tag: those are managed by the
  directory-enrichment pipeline (a shared profile fanned out group-wide), NOT by
  the per-file Tunabrain sweep. Without this exclusion a bulk import of 200k
  directory-tagged files would be swept per-file — exactly the cost this feature
  exists to avoid. Plain SQL (rather than honeysql) so the `unnest`/`LIKE`
  prefix-exclusion reads unambiguously."
  [ds limit]
  (jdbc/execute! ds
                 [(str "SELECT id FROM grout_media "
                       "WHERE enriched = false AND superseded_at IS NULL "
                       "AND NOT EXISTS ("
                       "  SELECT 1 FROM unnest(tags) AS t WHERE t LIKE 'parent-directory:%'"
                       ") LIMIT ?")
                  limit]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn set-enriched!
  "Mark a row as enriched and persist the AI-derived metadata + the
  `MediaContext` Tunabrain returned (so we can replay it on a future
  enrichment attempt if a human corrects the grounding).

  Writeable fields:
    * `:name`                  — AI fills when missing, never overwrites
                                 a human-set value. The merge step
                                 (`media.enrich/merge-enrichment`)
                                 omits `:name` from the payload
                                 entirely when the row already has one,
                                 so this fn only writes the column
                                 when there's actually something new
                                 to write.
    * `:description`           — same shape as `:name`.
    * `:tags`                  — replaces the column; union is the
                                 caller's responsibility (see
                                 `media.enrich/merge-enrichment`).
    * `:enrichment-context`    — the `MediaContext` Tunabrain returned;
                                 stored as JSONB and replayed on retry.
    * `:enrichment-grounding-source` — the `context.source` value
                                 (provided-text / provided-summary /
                                 provided-link / wikipedia / none);
                                 diagnostic for \"did the auto-search
                                 land right?\" — diagnostic only.

  Returns the row, or nil if the row is missing."
  [ds id {:keys [name description tags enrichment-context
                 enrichment-grounding-source]}]
  (let [set-map (cond-> {:enriched true}
                  (some? name)                     (assoc :name name)
                  (some? description)              (assoc :description description)
                  (some? tags)                     (assoc :tags (text-array tags))
                  (some? enrichment-context)       (assoc :enrichment_context
                                                       (as-jsonb
                                                         (json/generate-string
                                                           enrichment-context)))
                  (some? enrichment-grounding-source) (assoc :enrichment_grounding_source
                                                            enrichment-grounding-source))]
    (exec-one ds {:update :grout_media
                  :set    set-map
                  :where  [:= :id id]
                  :returning [:*]})))

;; ---------------------------------------------------------------------------
;; Tag-group (directory-level) enrichment support
;;
;; These operate on the flat `tags text[]` array via containment (`@>`) and,
;; for the fan-out UPDATE, Postgres array functions. They use plain
;; parameterized SQL with a text[] array-literal param (`?::text[]`) rather than
;; honeysql, because the array/`unnest` shapes are far clearer as literal SQL.
;; ---------------------------------------------------------------------------

(defn- pg-text-array
  "Format a Clojure collection of strings as a Postgres array literal string
   (e.g. `{\"a\",\"b\"}`) suitable for a `?::text[]` cast parameter. Elements are
   double-quoted with `\\` and `\"` escaped, so tag values with punctuation are
   safe. An empty collection yields `{}`."
  [xs]
  (str "{"
       (str/join ","
                 (map (fn [x]
                        (str \" (-> (str x)
                                    (str/replace "\\" "\\\\")
                                    (str/replace "\"" "\\\"")) \"))
                      xs))
       "}"))

(defn count-by-tag
  "Count live rows carrying `tag-value` in their tags array."
  [ds tag-value]
  (-> (jdbc/execute-one! ds
                         ["SELECT count(*) AS n FROM grout_media
                           WHERE superseded_at IS NULL AND tags @> ?::text[]"
                          (pg-text-array [tag-value])]
                         {:builder-fn rs/as-unqualified-lower-maps})
      :n
      (or 0)))

(defn- row->filename
  "Best available original filename for a row: the first `filename:` tag,
   else `:name`, else the basename of `:path`. Returns nil when nothing usable."
  [{:keys [tags name path]}]
  (or (some (fn [t] (when (and (string? t) (str/starts-with? t "filename:"))
                      (subs t (count "filename:"))))
            tags)
      (not-empty name)
      (when path (not-empty (last (str/split path #"/"))))))

(defn sample-filenames-by-tag
  "Return up to `limit` representative filenames for the media group carrying
   `tag-value`, newest first. Used to ground the directory-profile LLM call."
  [ds tag-value limit]
  (->> (jdbc/execute! ds
                      ["SELECT tags, name, path FROM grout_media
                        WHERE superseded_at IS NULL AND tags @> ?::text[]
                        ORDER BY created_at DESC LIMIT ?"
                       (pg-text-array [tag-value]) limit]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (keep row->filename)
       vec))

(defn apply-directory-profile!
  "Fan a directory profile out to every live row carrying `tag-value`.

  In one set-based UPDATE:
    * unions `profile-tags` (the dimension-as-tag expansion + free-form tags)
      into each row's `tags`, removing any `stale-tags` (tags applied by a
      previous version of this profile that the new one no longer includes),
    * COALESCE-fills the `channel` column from `channel` (never clobbers a
      channel set at intake), and
    * marks the rows `enriched=true`.

  `stale-tags` should be (old-profile-tags \\ new-profile-tags) so a re-enrich
  that drops a tag also removes it from already-stamped rows, while tags the new
  profile keeps are preserved. Returns the number of rows updated."
  [ds tag-value profile-tags stale-tags channel]
  (let [result (jdbc/execute-one! ds
                 [(str "UPDATE grout_media "
                       "SET tags = (SELECT array(SELECT DISTINCT x "
                       "                         FROM unnest(array_cat(tags, ?::text[])) AS x "
                       "                         WHERE x <> ALL(?::text[]))), "
                       "    channel = COALESCE(channel, ?::text), "
                       "    enriched = true "
                       "WHERE superseded_at IS NULL AND tags @> ?::text[]")
                  (pg-text-array profile-tags)
                  (pg-text-array stale-tags)
                  channel
                  (pg-text-array [tag-value])]
                 {:builder-fn rs/as-unqualified-lower-maps})]
    (:next.jdbc/update-count result)))

(defn live-rows-for-retention
  "Return the columns the retention job needs for every live row."
  [ds]
  (exec ds {:select [:id :channel :kind :duration_ms :created_at]
            :from   :grout_media
            :where  [:= :superseded_at nil]}))

(defn supersede-many!
  "Soft-delete (supersede) the given ids in one statement. No-op for empty ids."
  [ds ids]
  (when (seq ids)
    (exec ds {:update :grout_media
              :set    {:superseded_at [:now]}
              :where  [:in :id (vec ids)]})))
