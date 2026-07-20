(ns grout.tunarr_scheduler
  "Thin HTTP client for the dimensions catalog at Tunarr Scheduler.

  At startup, the enrichment orchestrator fetches
  `GET <base-url>/api/dimensions` from Tunarr Scheduler to get the list
  of defined dimensions, then `GET /api/dimensions/{name}/values` for
  each one to get the allowed values. The result is a map of
  `dimension-name -> {:description ..., :values [...]}` — exactly the
  shape Tunabrain's `/categorize` `categories` parameter expects.

  Grout also fetches `GET /api/dimensions/descriptions` to get
  per-value descriptions for every dimension (channel, audience,
  freshness, season, time-slot, ...), not just channel. Without those
  descriptions the LLM has to guess what an opaque slug like
  `toontown` or `kids` means and routinely invents a hallucinated
  value (`educational`, `thriller`, etc.) that the controlled-vocabulary
  guard drops, leaving rows with a `nil` value for that dimension.

  Why this is a single source of truth: the dimension catalog lives in
  Tunarr Scheduler's `config.edn` (see `tunarr-scheduler/AGENTS.md`
  pitfall #5 — dimensions are the truth source for channel-tagged
  media). Grout does not duplicate it; it asks Tunarr Scheduler. If
  Tunarr Scheduler is down at startup, Grout retries with backoff
  (see `fetch-dimensions-with-retry!`) and surfaces the error if
  Tunarr Scheduler stays unreachable — the safer failure mode than
  starting with an empty dimension catalog (which would silently
  degrade AI quality)."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defrecord TunarrSchedulerClient [endpoint http-opts]
  java.io.Closeable
  (close [_]
    (log/info "Closing tunarr-scheduler client" {:endpoint endpoint})))

(defn create
  "Build a Tunarr Scheduler client.

  Keys:
    * `:endpoint`  — base URL, e.g. `http://tunarr-scheduler.arr.svc.cluster.local:5545`
                     (trailing slash optional; stripped)
    * `:http-opts` — additional `clj-http` options"
  [{:keys [endpoint http-opts]}]
  (let [endpoint (some-> endpoint (str/replace #"/+$" ""))]
    (when-not endpoint
      (throw (ex-info "grout.tunarr_scheduler/create requires :endpoint"
                      {:config-keys [:endpoint]})))
    (log/info "Initialised tunarr-scheduler client" {:endpoint endpoint})
    (->TunarrSchedulerClient endpoint (or http-opts {}))))

(defn- get-json
  [^TunarrSchedulerClient client path]
  (let [url  (str (:endpoint client) path)
        opts (merge {:accept             :json
                     :as                 :text
                     :throw-exceptions   false
                     :socket-timeout     10000
                     :connection-timeout 5000}
                    (:http-opts client))]
    (try
      (let [{:keys [status body]} (http/get url opts)]
        (if (<= 200 status 299)
          (json/parse-string body true)
          (throw (ex-info (format "tunarr-scheduler GET %s failed: %s" path status)
                          {:status status :url url :path path}))))
      (catch java.net.ConnectException e
        (throw (ex-info (format "connection refused to tunarr-scheduler at %s" url)
                        {:url url :path path :cause :connection-refused} e)))
      (catch java.net.UnknownHostException e
        (throw (ex-info (format "unknown host when connecting to tunarr-scheduler at %s" url)
                        {:url url :path path :cause :unknown-host} e))))))

;; ---------------------------------------------------------------------------
;; Dimensions catalog
;; ---------------------------------------------------------------------------
;;
;; Live API (verified 2026-07-07 against tunarr-scheduler.arr.svc.cluster.local:5545,
;; updated 2026-07-20 to include /dimensions/descriptions, covering every
;; dimension rather than only channel):
;;
;;   GET /api/dimensions
;;     -> {:dimensions [{:name "audience" :value-count 4}
;;                      {:name "channel"  :value-count 14}
;;                      ...]}
;;
;;   GET /api/dimensions/{dimension}/values
;;     -> {:values [{:value "kids" :usage-count 50}
;;                  {:value "teen" :usage-count 260}
;;                  ...]}
;;
;;   GET /api/dimensions/descriptions
;;     -> {:dimensions {"channel"  {:description "" :values [{:value "toontown" :description "Animated content..."} ...]}
;;                      "audience" {:description "Who this is for" :values [{:value "kids" :description "..."} ...]}
;;                      ...}}
;;
;; Note: Tunarr Scheduler's per-value descriptions are not guaranteed to be
;; populated for every value or dimension (a config-driven feature, filled
;; in incrementally); the whole-dimension natural-language description we
;; bring ourselves in the static `dimension-descriptions` table in the
;; Grout config is always the fallback, fed to Tunabrain's
;; `CategoryDefinition`.
;; ---------------------------------------------------------------------------

(defn- description-for
  "Look up a dimension's natural-language description. Falls back to
  a generic sentence if the dimension is not in the table (so a new
  dimension added to Tunarr Scheduler doesn't crash the catalog
  fetch — it just gets a bland description and the LLM still works)."
  [dim-name dim-descriptions]
  (or (get dim-descriptions (keyword dim-name))
      (str "Dimension '" dim-name "' from Tunarr Scheduler")))

(defn- fetch-dimension-values
  "Fetch the list of allowed values for a single dimension from
  `GET /api/dimensions/{dim}/values`. Returns a sorted vector of
  value strings (sorted alphabetically so the model prompt is
  stable across runs)."
  [^TunarrSchedulerClient client dim-name]
  (let [{:keys [values]} (get-json client (str "/api/dimensions/" (name dim-name) "/values"))]
    (->> (or values [])
         (map :value)
         (map name)
         sort
         vec)))

(defn- format-value-description
  "Render a dimension's `CategoryDefinition.description` per-value prompt
  block. Each line pairs a value with its Tunarr Scheduler description
  (e.g. `toontown: Animated content...`), sorted by value so the model
  sees a stable order across runs.

  Values without a description get an empty trailing colon
  (`toontown:`) rather than being omitted — the controlled vocabulary
  stays the same set of values whether or not descriptions are
  populated, and a missing description is a content problem, not a
  structural one."
  [values-by-name dim-values]
  (->> dim-values
       (map (fn [v]
              (let [desc (or (get values-by-name v) "")]
                (str "  - " v ": " desc))))
       (str/join "\n")))

(defn- build-dimension
  "Build a single `CategoryDefinition` map for Tunabrain's `/categorize`
  call: `{:description ... :values [...]}`. The `dim-descriptions` table
  is a static config map (`{:audience \"...\" :channel \"...\"}`)
  that gives each dimension a natural-language whole-dimension
  description.

  `value-descriptions` is the `{dim-keyword {value description}}` map
  from `fetch-value-descriptions!`. When this dimension has any
  non-blank per-value description, it takes precedence: the description
  becomes a per-value prompt so the LLM can map opaque slugs (e.g.
  `toontown`, `kids`) to what they actually mean instead of guessing
  from the name alone. When this dimension has no populated
  descriptions (e.g. older Tunarr Scheduler without the new endpoint,
  or a dimension nobody has annotated yet), the static description from
  `dim-descriptions` is used unchanged — same behaviour as before
  per-value descriptions existed. Only values with a non-blank
  description in the map contribute a real description to the prompt
  block; values with `nil`/missing/empty descriptions are still listed
  (so the controlled vocabulary stays the same set of values), but with
  a blank trailing colon to make the missing content obvious."
  [^TunarrSchedulerClient client dim-name dim-descriptions value-descriptions]
  (let [values      (fetch-dimension-values client dim-name)
        base-desc   (description-for dim-name dim-descriptions)
        descs       (get value-descriptions (keyword (name dim-name)) {})
        ;; If no per-value descriptions are populated for this
        ;; dimension, the static description is the right answer —
        ;; appending "Available values and what fits each:\n  - kids:
        ;; \n  - ..." would be misleading.
        populated   (some #(not (str/blank? (val %))) descs)
        description (if populated
                      (str base-desc
                           "\n\nAvailable values and what fits each:\n"
                           (format-value-description descs values))
                      base-desc)]
    {:description description
     :values      (vec values)}))

(defn fetch-value-descriptions!
  "Fetch per-value descriptions for every dimension from Tunarr Scheduler.

  Calls `GET /api/dimensions/descriptions` and returns a
  `{dim-keyword {value-string description-string}}` map, suitable for
  passing to `fetch-dimensions!`. Empty descriptions are kept (mapped
  to `\"\"`) rather than dropped — see `format-value-description`.

  Returns an empty map on a 404 (older Tunarr Scheduler without the
  endpoint) so callers can keep working with only the static
  `dim-descriptions` table. Other HTTP errors propagate as
  `ExceptionInfo` like the other `get-json` callers — connection errors
  and unknown hosts propagate out of `get-json`, so we don't need to
  handle them here."
  [^TunarrSchedulerClient client]
  (try
    (let [{:keys [dimensions]} (get-json client "/api/dimensions/descriptions")]
      (if (map? dimensions)
        (into {}
              (map (fn [[dim-name {:keys [values]}]]
                     [(keyword (name dim-name))
                      (into {}
                            (keep (fn [{:keys [value description]}]
                                    (when value
                                      [(name value) (or description "")])))
                            (or values []))]))
              dimensions)
        (do (log/warn "tunarr-scheduler /api/dimensions/descriptions returned unexpected shape;"
                      " falling back to static dimension descriptions"
                      {:response-type (type dimensions)})
            {})))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        (do (log/warn "tunarr-scheduler returned 404 for /api/dimensions/descriptions;"
                      " falling back to static dimension descriptions")
            {})
        (throw e)))))

(defn fetch-dimensions!
  "Fetch the full dimensions catalog from Tunarr Scheduler.

  Calls `GET /api/dimensions` to get the dimension list, then
  `GET /api/dimensions/{name}/values` for each one to get the
  values. Returns a map of `dimension-name -> {:description ..., :values [...]}`,
  exactly the shape Tunabrain's `/categorize` `categories` parameter
  expects.

  Arguments:
    client             — a `TunarrSchedulerClient`
    dim-descriptions   — static config map `{:audience \"...\" :channel \"...\"}`
                         providing natural-language whole-dimension
                         descriptions. Tunarr Scheduler may not have
                         per-value descriptions for every dimension; we
                         ship these as a fallback in the Grout config.
    value-descriptions — optional `{dim-keyword {value description}}`
                         map from `fetch-value-descriptions!`. When a
                         dimension has entries here, its `:description`
                         is rendered as a per-value prompt. When nil,
                         empty, or the dimension has no entries, the
                         static description from `dim-descriptions` is
                         used as-is (the pre-per-value-descriptions
                         behaviour).

  Throws if the catalog fetch fails (callers should retry with
  backoff). Individual dimension fetch failures are logged and
  skipped (the dimension just won't appear in the returned map)."
  ([client dim-descriptions] (fetch-dimensions! client dim-descriptions nil))
  ([^TunarrSchedulerClient client dim-descriptions value-descriptions]
   (let [catalog (get-json client "/api/dimensions")
         dims    (or (:dimensions catalog) [])]
     (when-not (sequential? dims)
       (throw (ex-info "tunarr-scheduler /api/dimensions returned unexpected shape"
                       {:response catalog})))
     (log/info "Fetched dimension catalog" {:count (count dims)
                                            :dimensions-with-value-descriptions (count (or value-descriptions {}))})
     (into {}
           (keep (fn [{dim-name :name}]
                   (when dim-name
                     (try
                       [(keyword (name dim-name)) (build-dimension client dim-name dim-descriptions value-descriptions)]
                       (catch Exception e
                         (log/warn e "Failed to build dimension; skipping"
                                   {:dim dim-name})
                         nil)))))
           dims))))

(defn- retry
  "Retry `f` up to `max-attempts` times with exponential backoff
  (100ms, 200ms, 400ms, ...). Returns the first non-throwing result.
  Rethrows the last exception if all attempts fail."
  [f max-attempts]
  (loop [attempt 1
         delay-ms 100]
    (let [result (try {:ok (f)}
                    (catch Exception e
                      (if (>= attempt max-attempts)
                        (throw e)
                        e)))]
      (if (:ok result)
        (:ok result)
        (do (log/warn "Tunarr Scheduler fetch failed; retrying"
                      {:attempt attempt :delay-ms delay-ms
                       :error (.getMessage ^Exception result)})
            (Thread/sleep (long delay-ms))
            (recur (inc attempt) (* delay-ms 2)))))))

(defn fetch-dimensions-with-retry!
  "Like `fetch-dimensions!` but retries with exponential backoff on
  connection failures. Use at startup; surface the error if all
  attempts fail (Grout's enrichment degrades silently without a
  catalog, which we want to make loud).

  `value-descriptions` is forwarded to `fetch-dimensions!` on every
  retry so the per-value prompts are consistent across attempts — the
  retry is for connection failures, not for transient value data, so
  the cached descriptions don't need re-fetching either."
  ([client dim-descriptions] (fetch-dimensions-with-retry! client dim-descriptions nil))
  ([client dim-descriptions value-descriptions]
   (fetch-dimensions-with-retry! client dim-descriptions value-descriptions 5))
  ([client dim-descriptions value-descriptions max-attempts]
   (retry #(fetch-dimensions! client dim-descriptions value-descriptions) max-attempts)))
