(ns grout.tunarr_scheduler
  "Thin HTTP client for the dimensions catalog at Tunarr Scheduler.

  At startup, the enrichment orchestrator fetches
  `GET <base-url>/api/dimensions` from Tunarr Scheduler to get the list
  of defined dimensions, then `GET /api/dimensions/{name}/values` for
  each one to get the allowed values. The result is a map of
  `dimension-name -> {:description ..., :values [...]}` — exactly the
  shape Tunabrain's `/categorize` `categories` parameter expects.

  For the `channel` dimension specifically, Grout also fetches
  `GET /api/dimensions/channel/descriptions` to get a per-channel
  description. Without those descriptions the LLM has to guess what
  an opaque slug like `toontown` means and routinely invents a
  hallucinated channel (`educational`, `thriller`, etc.) that the
  controlled-vocabulary guard drops, leaving rows with `channel: nil`.

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
;; updated 2026-07-20 to include /descriptions):
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
;;   GET /api/dimensions/channel/descriptions
;;     -> {:values [{:value "toontown" :description "Animated content..."}
;;                  ...]}
;;
;; Note: Tunarr Scheduler does NOT expose a `description` field per
;; non-channel dimension; we have to bring our own. The orchestrator
;; merges the dynamic value lists from Tunarr Scheduler with the static
;; `dimension-descriptions` table in the Grout config (which provides
;; the natural-language description for each dimension, fed to
;; Tunabrain's `CategoryDefinition`).
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

(defn- format-channel-description
  "Render the `channel` dimension's `CategoryDefinition.description` field
  as a Tunabrain-friendly prompt. The list pairs each channel slug with
  its Tunarr Scheduler description (e.g. `toontown: Animated content...`),
  sorted by value so the model sees a stable order across runs.

  Channels without a description get an empty trailing colon (`toontown:`)
  rather than being omitted — the controlled vocabulary stays the same
  set of values whether or not descriptions are populated, and a missing
  description is a content problem, not a structural one."
  [values-by-name channel-values]
  (->> channel-values
       (map (fn [v]
              (let [desc (or (get values-by-name v) "")]
                (str "  - " v ": " desc))))
       (str/join "\n")))

(defn- build-dimension
  "Build a single `CategoryDefinition` map for Tunabrain's `/categorize`
  call: `{:description ... :values [...]}`. The `dim-descriptions` table
  is a static config map (`{:audience \"...\" :channel \"...\"}`)
  that gives each non-channel dimension a natural-language description.

  For the `channel` dimension specifically, `channel-descriptions`
  (a `{value description}` map from `GET /api/dimensions/channel/descriptions`)
  takes precedence: the description becomes a per-channel prompt so the
  LLM can map content like `Computerphile → infobytes` instead of
  guessing an English description-word. When `channel-descriptions` is
  nil/empty (e.g. older Tunarr Scheduler without the new endpoint), the
  static `:channel` entry from `dim-descriptions` is used unchanged —
  same behaviour as before this PR. Only channels that have a non-blank
  description in the map contribute to the per-channel prompt block;
  channels with `nil`/missing/empty descriptions are still listed (so
  the controlled vocabulary stays the same set of values), but with a
  blank trailing colon to make the missing-content obvious."
  [^TunarrSchedulerClient client dim-name dim-descriptions channel-descriptions]
  (let [values      (fetch-dimension-values client dim-name)
        base-desc   (description-for dim-name dim-descriptions)
        description (if (= "channel" (name dim-name))
                      (let [descs    (or channel-descriptions {})
                            ;; If no channel descriptions are populated
                            ;; (older Tunarr Scheduler), the static
                            ;; :channel description is the right answer —
                            ;; appending "Available channels and what
                            ;; fits each:\n  - britannia: \n  - ..." would
                            ;; be misleading.
                            populated (some #(not (str/blank? (val %))) descs)]
                        (if populated
                          (str base-desc
                               "\n\nAvailable channels and what fits each:\n"
                               (format-channel-description descs values))
                          base-desc))
                      base-desc)]
    {:description description
     :values      (vec values)}))

(defn fetch-channel-descriptions!
  "Fetch the per-channel descriptions from Tunarr Scheduler.

  Calls `GET /api/dimensions/channel/descriptions` and returns a
  `{value-string description-string}` map, suitable for passing to
  `fetch-dimensions!`. Empty descriptions are kept (mapped to `\"\"`)
  rather than dropped — see `format-channel-description`.

  Returns an empty map on a 404 (older Tunarr Scheduler without the
  endpoint) so callers can keep working with the static `:channel`
  description. Other HTTP errors propagate as `ExceptionInfo` like the
  other `get-json` callers — connection errors and unknown hosts
  propagate out of `get-json`, so we don't need to handle them here."
  [^TunarrSchedulerClient client]
  (try
    (let [{:keys [values]} (get-json client "/api/dimensions/channel/descriptions")]
      (if (and (sequential? values) (seq values))
        (into {}
              (keep (fn [{:keys [value description]}]
                      (when value
                        [(name value) (or description "")])))
              values)
        (do (log/warn "tunarr-scheduler /descriptions returned unexpected shape;"
                      " falling back to static :channel description"
                      {:response-count (count (or values []))})
            {})))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        (do (log/warn "tunarr-scheduler returned 404 for /api/dimensions/channel/descriptions;"
                      " falling back to static :channel description")
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
    client              — a `TunarrSchedulerClient`
    dim-descriptions    — static config map `{:audience \"...\" :channel \"...\"}`
                          providing natural-language descriptions for
                          each non-channel dimension. Tunarr Scheduler
                          does not expose descriptions for these; we
                          ship them in the Grout config.
    channel-descriptions — optional `{value description}` map from
                          `fetch-channel-descriptions!`. When present,
                          the `channel` dimension's `:description` is
                          rendered as a per-channel prompt. When nil
                          or empty, the static `:channel` description
                          from `dim-descriptions` is used as-is (the
                          pre-PR behaviour).

  Throws if the catalog fetch fails (callers should retry with
  backoff). Individual dimension fetch failures are logged and
  skipped (the dimension just won't appear in the returned map)."
  ([client dim-descriptions] (fetch-dimensions! client dim-descriptions nil))
  ([^TunarrSchedulerClient client dim-descriptions channel-descriptions]
   (let [catalog (get-json client "/api/dimensions")
         dims    (or (:dimensions catalog) [])]
     (when-not (sequential? dims)
       (throw (ex-info "tunarr-scheduler /api/dimensions returned unexpected shape"
                       {:response catalog})))
     (log/info "Fetched dimension catalog" {:count (count dims)
                                            :channel-descriptions (count (or channel-descriptions {}))})
     (into {}
           (keep (fn [{dim-name :name}]
                   (when dim-name
                     (try
                       [(keyword (name dim-name)) (build-dimension client dim-name dim-descriptions channel-descriptions)]
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

  `channel-descriptions` is forwarded to `fetch-dimensions!` on every
  retry so the per-channel prompt is consistent across attempts —
  the retry is for connection failures, not for transient channel
  data, so the cached descriptions don't need re-fetching either."
  ([client dim-descriptions] (fetch-dimensions-with-retry! client dim-descriptions nil))
  ([client dim-descriptions channel-descriptions]
   (fetch-dimensions-with-retry! client dim-descriptions channel-descriptions 5))
  ([client dim-descriptions channel-descriptions max-attempts]
   (retry #(fetch-dimensions! client dim-descriptions channel-descriptions) max-attempts)))
