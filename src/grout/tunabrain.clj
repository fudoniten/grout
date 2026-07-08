(ns grout.tunabrain
  "HTTP client for the Tunabrain service.

  Tunabrain exposes typed, purposeful endpoints — not a generic chat
  completions gateway. Grout uses two of them:

    * `POST /categorize` — structured dimensions (audience, channel, ...).
      The caller (Grout) supplies the `categories` map (the dimension
      definitions); Tunabrain returns `DimensionSelection[]` drawn from
      those allowed values.

    * `POST /tags` — free-form tags. Caller supplies `existing_tags`;
      Tunabrain returns a `tags: string[]` it recommends, with
      preference to reuse values from `existing_tags` when possible.

  Both endpoints return a `MediaContext` object in the response
  (`{text, links, summary, source}`) that Grout persists verbatim and
  replays on the next attempt. The `summary` is the resolved reference
  text the model actually saw (Wikipedia article, YouTube page, or
  whatever the operator stored); the `source` is the provenance. If the
  model landed on a bad Wikipedia match, the operator fixes the
  `summary` and the next call re-tags against the fix.

  See `resources/grout-tunabrain-enrichment-requirements.md` for the
  full design (the design doc explains why we don't use the OpenAI
  chat-completions shape and how the two-form media model maps to these
  two endpoints)."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private default-timeout-ms
  "Default socket timeout for Tunabrain calls. Categorization may invoke
  a heavier LLM call than tagging, so we keep the default modest; callers
  can override per-request."
  30000)

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
    (log/debug (format "tunabrain POST %s, payload: %s" url (with-out-str (pprint payload))))
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

(defn- path->title
  "Derive a rough display title from an on-disk media path: take the file
  name, drop the extension, and turn `.`/`_` separators into spaces.
  Returns nil when the path yields nothing usable, so callers can fall
  back to a sentinel."
  [path]
  (when-not (str/blank? path)
    (let [base   (.getName (java.io.File. ^String path))
          no-ext (str/replace base #"\.[A-Za-z0-9]{1,5}$" "")
          spaced (-> no-ext (str/replace #"[._]+" " ") str/trim)]
      (not-empty spaced))))

(defn- media->tunabrain
  "Build a Tunabrain `MediaItem` from a Grout row map. `:name` is human-set
  and usually absent for bulk media, so the title falls back to a filename
  derived from `:path`, and only then to the sentinel \"Unknown\".

  We deliberately send the filename (not `<unnamed>`) so Tunabrain has a
  real string to refine: it strips a placeholder-only title before doing
  any search, and the filename often carries the actual work name. A
  `MediaContext` (transcript/keyframes/operator notes) is still the primary
  grounding — see `request-categorization!` and `request-tags!`."
  [{:keys [id name path]}]
  {:id (str id)
   :title (or (not-empty name) (path->title path) "Unknown")})

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

(defn request-categorization!
  "POST /categorize.

  Arguments:
    client     — a `TunabrainClient`
    row        — a Grout `Media` map (or any map with `:id` and `:name`)
    dim-config — dimension definitions, see `build-categories`
    channels   — optional sequence of `Channel` maps (`{:name ... :description ...}`)
                 to consider for the deprecated `mappings` response field.
                 Defaults to `[]`.
    context    — optional `MediaContext` from a previous call. Replays
                 the operator's last corrected grounding. Defaults to nil.

  Returns a map with three keys:
    `:dimensions` — sequence of `DimensionSelection` maps from Tunabrain
    `:mappings`   — sequence of `ChannelMapping` maps (deprecated but
                     still returned; we persist it for backwards compat)
    `:context`    — the `MediaContext` that was actually used. Caller
                     must persist this verbatim. `:source` reveals
                     which grounding path was used.

  Throws on HTTP error (5xx, 4xx other than 422), connection refused,
  or unknown host. The orchestrator catches the exception and leaves
  the row `enriched=false` for the next sweep."
  [client row dim-config & {:keys [channels context]
                            :or   {channels []}}]
  (let [payload {:media      (media->tunabrain row)
                 :categories (build-categories dim-config)
                 :channels   (vec channels)
                 :context    context}
        resp    (json-post! client "/categorize" payload)]
    (when-not (map? resp)
      (throw (ex-info "Tunabrain /categorize returned non-map"
                      {:response resp})))
    (when-not (sequential? (:dimensions resp))
      (throw (ex-info "Tunabrain /categorize missing :dimensions"
                      {:response resp})))
    (log/info (format "Categorize response: %d dimensions, %d mappings"
                      (count (:dimensions resp))
                      (count (or (:mappings resp) []))))
    {:dimensions (vec (:dimensions resp))
     :mappings   (vec (or (:mappings resp) []))
     :context    (:context resp)}))

(defn request-tags!
  "POST /tags.

  Arguments:
    client        — a `TunabrainClient`
    row           — a Grout `Media` map
    existing-tags — the row's current tags (Tunabrain reuses these when
                    possible)
    context       — optional `MediaContext` from a previous call

  Returns a map with two keys:
    `:tags`    — sequence of recommended tag strings
    `:context` — the `MediaContext` Tunabrain actually used (caller
                 persists verbatim). `:source` reveals which grounding
                 path was used.

  Throws on HTTP error. The orchestrator catches and logs."
  [client row existing-tags & {:keys [context]}]
  (let [payload {:media         (media->tunabrain row)
                 :existing_tags (vec existing-tags)
                 :context       context}
        resp    (json-post! client "/tags" payload)]
    (when-not (map? resp)
      (throw (ex-info "Tunabrain /tags returned non-map"
                      {:response resp})))
    (when-not (sequential? (:tags resp))
      (throw (ex-info "Tunabrain /tags missing :tags"
                      {:response resp})))
    (log/info (format "Tag response: %d tags" (count (:tags resp))))
    {:tags    (vec (:tags resp))
     :context (:context resp)}))

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
