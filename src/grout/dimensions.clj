(ns grout.dimensions
  "Validate AI-produced dimension values against the controlled vocabulary
   fetched from Tunarr Scheduler.

   Tunarr Scheduler is the source of truth for which dimension values are
   legal (channels, audiences, ...). Grout already fetches that catalog at
   startup (`grout.tunarr_scheduler/fetch-dimensions!`) and sends it to
   Tunabrain as the `categories` map, so the model is *told* the allowed
   values. But the model still occasionally invents values outside the set —
   a typo (`spectum` for `spectrum`) or an entirely fabricated channel. This
   namespace is the guard on the way back in: it drops any dimension value
   that is not in the configured vocabulary before it is persisted as a
   `dim:value` tag or written to the `channel` column.

   Mirrors `tunarr.scheduler.curation.dimensions` on the scheduler side. The
   two services share the same controlled vocabulary (Tunarr Scheduler owns
   it; Grout fetches it), so the same value is legal — or a hallucination —
   in both. Grout is the second line of defence; the scheduler guards its own
   output too.

   Two response shapes are validated because the two enrichment paths differ:

     * `/enrich/short-form` (per-file) returns a `DimensionSelection[]`:
       `[{:dimension \"audience\" :values [\"kids\" ...]} ...]`
       → `filter-selections`.
     * `/enrich/profile` (directory) returns a `{dimension-kw [value ...]}`
       map: `{:channel [\"muse\"]}` → `filter-dimension-map`.

   The rule is the same for both: a dimension with no configured vocabulary
   passes through untouched (we cannot judge values for a dimension we have
   no vocabulary for); within a configured dimension, values outside the
   vocabulary are dropped and reported under `:rejected`."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn allowed-values
  "The set of allowed value-strings for `dimension` (a keyword or string) in
   `dim-config`, or nil when the dimension has no configured vocabulary.

   `dim-config` is the `{dimension-kw {:description ... :values [...]}}`
   catalog built from Tunarr Scheduler (see `grout.system/build-dim-config`).
   Values are compared as trimmed strings so a stray space in the model's
   answer doesn't read as a new value."
  [dim-config dimension]
  (when-let [{:keys [values]} (get dim-config (keyword dimension))]
    (into #{}
          (comp (map #(some-> % str str/trim))
                (remove str/blank?))
          values)))

(defn value-allowed?
  "True when `value` is legal for `dimension`. A dimension with no configured
   vocabulary is treated as allowed — we cannot judge values for a dimension
   we have no vocabulary for (matches the scheduler-side rule in
   `tunarr.scheduler.curation.dimensions/value-allowed?`)."
  [dim-config dimension value]
  (if-let [allowed (allowed-values dim-config dimension)]
    (contains? allowed (some-> value str str/trim))
    true))

(defn- split-values
  "Partition `values` into `[valid invalid]` against `allowed` (a set of
   trimmed value-strings). Comparison is on the trimmed string form, but the
   original values are returned so downstream tag/channel expansion is
   unchanged for the kept ones."
  [allowed values]
  (let [{valid true invalid false}
        (group-by (fn [v] (contains? allowed (some-> v str str/trim))) values)]
    [(vec valid) (vec invalid)]))

(defn filter-selections
  "Filter Tunabrain `DimensionSelection[]` (the `/enrich/short-form` shape)
   against the allowed vocabulary in `dim-config`.

   Each selection is `{:dimension \"audience\" :values [\"kids\" ...]}`.
   Returns

     {:dimensions <valid-selections> :rejected [{:dimension d :value v} ...]}

   A selection whose dimension has no configured vocabulary passes through
   untouched. Within a configured dimension, values outside the vocabulary are
   dropped and reported; a selection left with no valid values is removed from
   `:dimensions` entirely."
  [dim-config selections]
  (reduce
   (fn [acc {:keys [dimension values] :as sel}]
     (if-let [allowed (allowed-values dim-config dimension)]
       (let [[valid invalid] (split-values allowed values)]
         (cond-> acc
           (seq valid)   (update :dimensions conj (assoc sel :values valid))
           (seq invalid) (update :rejected into
                                 (map (fn [v] {:dimension dimension :value v}))
                                 invalid)))
       (update acc :dimensions conj sel)))
   {:dimensions [] :rejected []}
   selections))

(defn filter-dimension-map
  "Filter a `{dimension-kw [value ...]}` map (the `/enrich/profile` shape)
   against the allowed vocabulary in `dim-config`.

   Returns

     {:dimensions <valid-only-map> :rejected [{:dimension d :value v} ...]}

   Same rule as `filter-selections`: a dimension with no configured
   vocabulary passes through untouched, and a dimension left with no valid
   values is dropped from the map entirely."
  [dim-config dimensions]
  (reduce
   (fn [acc [dimension values]]
     (if-let [allowed (allowed-values dim-config dimension)]
       (let [[valid invalid] (split-values allowed values)]
         (cond-> acc
           (seq valid)   (assoc-in [:dimensions dimension] valid)
           (seq invalid) (update :rejected into
                                 (map (fn [v] {:dimension dimension :value v}))
                                 invalid)))
       (assoc-in acc [:dimensions dimension] (vec values))))
   {:dimensions {} :rejected []}
   dimensions))

(defn log-rejected!
  "Emit a single warning summarising the dimension values that were dropped
   as hallucinations. No-op when `rejected` is empty. `context` is a map of
   extra log fields (e.g. `{:id ...}` or `{:tag ...}`)."
  [rejected context]
  (when (seq rejected)
    (log/warn (format "dropped %d hallucinated dimension value(s) outside the Tunarr Scheduler vocabulary: %s"
                      (count rejected)
                      (->> rejected
                           (map (fn [{:keys [dimension value]}]
                                  (str (name dimension) ":" value)))
                           (str/join ", ")))
              context)))
