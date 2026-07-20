(ns grout.tunabrain
  "HTTP client for the Tunabrain service.

  Tunabrain exposes typed, purposeful endpoints — not a generic chat
  completions gateway. Grout uses the single composite endpoint
  `/enrich/short-form`, which orchestrates describe + categorize + tags
  internally and returns the merged result. See the Tunabrain OpenAPI
  spec for the request/response shape; this client just threads Clojure
  data through `cheshire` + `clj-http` and adds a small layer of
  error context.

  The `/enrich/short-form` response carries:

    * `:media`     — the request `MediaItem` echoed back (caller's
                     working title, NOT the AI's refinement)
    * `:describe`  — a `DescribeMedia` map with the AI's refined title
                     and description; nil if the describe step failed
    * `:dimensions` — sequence of `DimensionSelection` maps
    * `:tags`     — sequence of recommended tag strings
    * `:context`  — the `MediaContext` Tunabrain actually used
    * `:cost_estimate` — the `CostEstimate` for the call(s) made
    * `:warnings` — list of non-fatal issues

  The `context` is persisted verbatim into `enrichment_context` and
  replayed on retry, per the `MediaContext` design. The
  `description` (the AI's) is the field the caller adopts into the
  row's `description` column via the never-clobber rule in
  `media.enrich/merge-enrichment`. The same applies to the describe
  `title` for the row's `name`."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private default-timeout-ms
  "Default socket timeout for Tunabrain calls. The composite
  `/enrich/short-form` endpoint orchestrates describe + categorize + tags
  internally, so a single call can take 20-50s on slow LLM providers.
  We default to 90s so a slow call still succeeds; callers can override
  per-request via `:timeout-ms` in `request-enrich-short-form!`'s opts.
  Real outages still fail fast (90s socket timeout) — this is the
  upper bound on a single call, not the round-trip SLA."
  90000)

(defrecord TunabrainClient [endpoint http-opts]
  java.io.Closeable
  (close [_]
    (log/info "Closing tunabrain client" {:endpoint endpoint})))

(defn- sanitize-endpoint [endpoint]
  (some-> endpoint (str/replace #"/+$" "")))

(defn create
  "Build a Tunabrain client from a config map.

  Keys:
    * `:endpoint`   — base URL, e.g. `http://tunabrain.arr.svc.cluster.local:5546`
                      (trailing slash optional; stripped)
    * `:http-opts`  — additional `clj-http` options (e.g. `:headers`,
                      `:socket-timeout` defaults)

  Returns a `TunabrainClient` record. Implements `java.io.Closeable`."
  [{:keys [endpoint http-opts]}]
  (let [endpoint (sanitize-endpoint endpoint)]
    (when-not endpoint
      (throw (ex-info "grout.tunabrain/create requires :endpoint"
                      {:config-keys [:endpoint]})))
    (log/info "Initialised tunabrain client" {:endpoint endpoint})
    (->TunabrainClient endpoint (or http-opts {}))))

(defn- json-post!
  [^TunabrainClient client path payload & {:keys [timeout-ms]}]
  (let [url          (str (:endpoint client) path)
        timeout-ms   (or timeout-ms default-timeout-ms)
        request-opts (cond-> (merge {:accept             :json
                                     :as                 :text
                                     :headers            {"Content-Type" "application/json"}
                                     :throw-exceptions   false
                                     :body               (json/generate-string payload)}
                                    (:http-opts client))
                           timeout-ms (assoc :socket-timeout timeout-ms))]
    (log/debug (format "tunabrain POST %s, payload: %s" url (json/generate-string payload)))
    (try
      (let [{:keys [status body]} (http/post url request-opts)]
        (if (<= 200 status 299)
          (json/parse-string body true)
          (let [error-details (try (json/parse-string body true)
                                   (catch Exception _ body))]
            (log/error "Tunabrain request failed"
                      {:status status :url url :path path
                       :error-details error-details
                       :request-payload (json/parse-string (json/generate-string payload) true)})
            (throw (ex-info (format "tunabrain request failed: %s - %s"
                                    status error-details)
                            {:status status :url url :path path
                             :error-details error-details})))))
      (catch java.net.ConnectException e
        (throw (ex-info (format "connection refused to tunabrain at %s" url)
                        {:url url :path path :cause :connection-refused} e)))
      (catch java.net.UnknownHostException e
        (throw (ex-info (format "unknown host when connecting to tunabrain at %s" url)
                        {:url url :path path :cause :unknown-host} e))))))

;; ---------------------------------------------------------------------------
;; Dimension contract
;;
;; Grout sends a `categories` map of `dimension-name -> {description, values}`.
;; Tunabrain picks `values` for each dimension from the allowed set, with
;; `notes` explaining each choice. The `values` for `audience` and `channel`
;; are Grout's static + dynamic vocabularies (see `audience-values` in
;; config; `channel` values come from Tunarr Scheduler's `/api/dimensions`).
;; ---------------------------------------------------------------------------

(defn- derive-title
  "Pick the best available working title for a Grout row, in priority order:

    1. `:name` (the human/AI-set title; may be nil on first enrichment)
    2. the first `<filename>` tag (the original source filename, preserved
       on every row at intake time)
    3. the basename of `:path` (the on-disk content-addressed path; not
       the source filename, but better than nothing)
    4. the literal string `\"<unnamed>\"`

  This fallback chain exists because the `/enrich/short-form` describe
  step (PR #48 in Tunabrain) calls a Wikipedia auto-search when the
  caller doesn't supply a `MediaContext`. If we send the literal
  `\"<unnamed>\"` placeholder, the auto-search latches onto whatever
  ambiguous term it can find — we observed it latching onto a
  disambiguation page and producing confident-but-wrong titles like
  \"Unnamed Memory\" for items that are actually animation tutorials.
  The describe chain's system prompt is correct (\"do NOT invent facts\");
  the only fix is to give it a working title that has some signal."
  [{:keys [name tags path]}]
  (or (and name (not (str/blank? name)) name)
      (some (fn [t]
              (when (str/starts-with? t "filename:")
                (str/replace t #"^filename:" "")))
            tags)
      (when path
        (let [basename (last (str/split path #"/"))]
          (when-not (str/blank? basename)
            basename)))
      "<unnamed>"))

(defn- media->tunabrain
  "Build a Tunabrain `MediaItem` from a Grout row map. The title is
  derived from the row's `:name` (preferred) or the original source
  filename captured in the `filename:` tag at intake (fallback). Without
  this fallback, the LLM-grounded describe step has nothing to work from
  and the Wikipedia auto-search latches onto garbage. The `MediaContext`
  is the caller-supplied grounding; see `request-enrich-short-form!` for
  the call site."
  [{:keys [id] :as row}]
  {:id (str id)
   :title (derive-title row)})

(defn- category-def
  "Format a single dimension's allowed values as a Tunabrain
  `CategoryDefinition`: `{:description ... :values [...]}`."
  [description values]
  {:description description
   :values (vec values)})

(defn- build-categories
  "Build the `categories` payload from a config map of static + dynamic
  dimension definitions. The dynamic side (e.g. `channel` values from
  Tunarr Scheduler) is whatever the caller passes in; the static side
  (e.g. `audience` values) is fixed in Grout's config.

  Input shape:
    {:audience    {\"description\" \"...\" \"values\" [\"daytime\" ...]}
     :channel     {\"description\" \"...\" \"values\" [\"goldenreels\" ...]}}"
  [dim-config]
  (into {}
        (map (fn [[dim-name {:keys [description values]}]]
               [(name dim-name)
                (category-def description (vec values))]))
        dim-config))

(defn- dimensions->tag-prefix
  "Convert Tunabrain `DimensionSelection[]` into a Grout tag list with
  the `dim:value` prefix convention used by Tunarr Scheduler and the
  PV tag namespace. The result is a flat vector of strings like
  `[\"audience:daytime\" \"channel:goldenreels\"]` ready to be unioned
  into the row's `tags` array.

  Transducer order note: we want `map` (per-dimension expansion) FIRST,
  then `cat` (flattening the per-dimension seqs). The order matters —
  with `(comp cat (map f))` the cat would pull raw dimension maps out
  of the source and then `map f` would apply to dimension maps, not
  to the values."
  [dimension-selections]
  (into []
        (comp (map (fn [{:keys [dimension values]}]
                     (map (fn [v] (str (name dimension) ":" v)) values)))
              cat)
        dimension-selections))

;; ---------------------------------------------------------------------------
;; Public client API
;; ---------------------------------------------------------------------------

(defn request-enrich-short-form!
  "POST /enrich/short-form — the single-call composite that replaces the
  prior two-call pattern (request-categorization! + request-tags!).

  Tunabrain orchestrates categorize + tags internally and adds a
  describe step that produces a display title + description.

  Arguments:
    client     — a `TunabrainClient`
    row        — a Grout `Media` map (or any map with `:id` and `:name`)
    dim-config — dimension definitions, see `build-categories`
    existing-tags — the row's current tags (Tunabrain reuses these when
                    possible)
    context    — optional `MediaContext` from a previous call. Replays
                 the operator's last corrected grounding. Defaults to nil.

  Returns a map with these keys:
    `:media`     — the request `MediaItem` echoed back (caller's working
                   title, not the AI-refined one)
    `:describe`  — a `DescribeMedia` map with the AI-refined title and
                   description; `nil` only if the describe step failed
    `:dimensions` — sequence of `DimensionSelection` maps
    `:tags`     — sequence of recommended tag strings
    `:context`  — the `MediaContext` Tunabrain actually used
    `:cost_estimate` — the `CostEstimate` map
    `:warnings` — list of non-fatal issues

  Throws on HTTP error (5xx, 4xx other than 422), connection refused,
  or unknown host. The orchestrator catches the exception and leaves
  the row `enriched=false` for the next sweep."
  [client row dim-config existing-tags & {:keys [context]}]
  (let [payload {:media        (media->tunabrain row)
                 :categories   (build-categories dim-config)
                 :existing_tags (vec existing-tags)
                 :context      context
                 :channels     []}
        resp    (json-post! client "/enrich/short-form" payload)]
    (when-not (map? resp)
      (throw (ex-info "Tunabrain /enrich/short-form returned non-map"
                      {:response resp})))
    (when-not (sequential? (:dimensions resp))
      (throw (ex-info "Tunabrain /enrich/short-form missing :dimensions"
                      {:response resp})))
    (when-not (sequential? (:tags resp))
      (throw (ex-info "Tunabrain /enrich/short-form missing :tags"
                      {:response resp})))
    (log/info (format "Enrich short-form: %d dimensions, %d tags, describe=%s"
                      (count (:dimensions resp))
                      (count (:tags resp))
                      (if (:describe resp) "yes" "no")))
    {:media         (:media resp)
     :describe      (:describe resp)
     :dimensions    (vec (:dimensions resp))
     :tags          (vec (:tags resp))
     :context       (:context resp)
     :cost_estimate (:cost_estimate resp)
     :warnings      (vec (or (:warnings resp) []))}))

(defn request-enrich-profile!
  "POST /enrich/profile — derive ONE shared profile (dimensions + tags) for a
  group of related media from the group's `concept-name` and a sample of its
  filenames. This is the directory-level enrichment call: one round trip per
  group, fanned out to every child item by the caller — not a per-file call.

  Arguments:
    client            — a `TunabrainClient`
    concept-name      — human-readable group name (e.g. \"Adam Neely Music\")
    sample-filenames  — a small seq of representative filenames (the only
                        content signal Tunabrain gets)
    :sample-count     — optional; the intended sample size (informational).
                        Defaults to the count actually supplied.
    :dim-config       — optional `dimension-name -> {:description, :values}`
                        map from `grout.tunarr_scheduler/fetch-dimensions!`.
                        When supplied, it's sent as the `:categories` field
                        so the model sees the controlled vocabulary
                        (including per-channel descriptions) before it
                        picks dimensions. Without this, the model has
                        no channel vocabulary and routinely invents a
                        description-word (`educational`, `thriller`)
                        that the controlled-vocabulary guard drops.

  Returns a map:
    `:concept-name`     — echoed back
    `:dimensions`       — `{dimension-keyword [values]}`, e.g. {:channel [\"muse\"]}
    `:tags`             — vector of free-form tag strings
    `:grounding-source` — always \"filename-pattern\" in v1
    `:warnings`         — vector of non-fatal issues

  Throws on HTTP error, connection refused, or unknown host — the caller
  (directory worker) catches and marks the profile failed for retry."
  [client concept-name sample-filenames & {:keys [sample-count dim-config]}]
  (let [filenames (vec sample-filenames)
        payload   (cond-> {:concept_name     concept-name
                           :sample_filenames filenames
                           :sample_count     (or sample-count (count filenames))}
                    (seq dim-config) (assoc :categories (build-categories dim-config)))
        resp      (json-post! client "/enrich/profile" payload)]
    (when-not (map? resp)
      (throw (ex-info "Tunabrain /enrich/profile returned non-map"
                      {:response resp})))
    (log/info (format "Enrich profile '%s': %d dimensions, %d tags%s"
                      concept-name
                      (count (:dimensions resp))
                      (count (:tags resp))
                      (if (seq dim-config) " (with categories)" "")))
    {:concept-name     (:concept_name resp)
     :dimensions       (or (:dimensions resp) {})
     :tags             (vec (or (:tags resp) []))
     :grounding-source (or (:grounding_source resp) "filename-pattern")
     :warnings         (vec (or (:warnings resp) []))}))

(defn build-dimension-config
  "Assemble the dimension config for the `request-categorization!`
  call from a Grout-style config map.

  Input shape:
    {:audience {\"description\" \"...\" \"values\" [\"daytime\" ...]}
     :channel  {\"description\" \"...\" \"values\" [\"goldenreels\" ...]}}

  Output: a plain Clojure map with string keys (dimension names) and
  values being `{\"description\" ..., \"values\" [...]}` maps."
  [dim-config]
  (into {}
        (map (fn [[k v]]
               [(name k) {:description (:description v)
                          :values      (vec (:values v))}]))
        dim-config))

(def dimension-selections->tag-prefix
  "Exposed for the orchestrator and tests: convert Tunabrain's
  `DimensionSelection[]` into a Grout `tags` array (dimension-as-tag
  prefix). See `dimensions->tag-prefix`."
  dimensions->tag-prefix)
