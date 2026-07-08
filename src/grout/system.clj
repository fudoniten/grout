(ns grout.system
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [grout.db :as db]
            [grout.enrichment.worker :as worker]
            [grout.enrichment.directory-worker :as directory-worker]
            [grout.http.server :as http]
            [grout.retention :as retention]
            [grout.tunabrain :as tunabrain]
            [grout.tunarr_scheduler :as tunarr-scheduler]
            [taoensso.timbre :as log]))

(defn- parse-log-level [level]
  (cond
    (keyword? level) level
    (string? level) (keyword level)
    :else :info))

(defn- parse-port [port]
  (cond
    (int? port) port
    (string? port) (Integer/parseInt port)
    :else 8080))

(defn- parse-bool
  "Coerce a config/env value to boolean. Env vars arrive as strings, so a bare
   \"false\" must not read as truthy."
  [v]
  (cond
    (boolean? v) v
    (string? v)  (contains? #{"1" "true" "yes" "on"} (str/lower-case v))
    :else        (boolean v)))

(defn- build-dim-config
  "Convert the live dimension catalog (from `tunarr-scheduler`) into
  the shape Tunabrain's `/categorize` `categories` parameter expects.
  Each entry `{:description ..., :values [...]}` is already in the
  right shape; this is a no-op transform kept explicit so the call
  site reads obviously."
  [dim-catalog]
  (into {}
        (map (fn [[dim-name {:keys [description values]}]]
               [dim-name {:description description
                          :values      (vec values)}]))
        dim-catalog))

(defn ->system-config
  "Produce the Integrant system configuration map from the raw config map."
  [{:keys [log-level server database media tunabrain tunarr-scheduler
           dimension-descriptions enrichment directory-enrichment retention]}]
  {:grout/logger {:level (parse-log-level (or log-level :info))}
   :grout/db {:jdbc-url (:jdbc-url database)
              :username (:username database)
              :password (:password database)}
   :grout/tunabrain (or tunabrain {:endpoint "http://tunabrain:8080"})
   ;; Tunarr Scheduler is optional. If :endpoint is empty/missing,
   ;; the client won't be built and the enrichment orchestrator will
   ;; start with an empty dim-config (logged at startup).
   :grout/tunarr-scheduler (when-let [ep (:endpoint tunarr-scheduler)]
                             (when (seq ep)
                               {:endpoint ep}))
   :grout/dim-catalog {:client (ig/ref :grout/tunarr-scheduler)
                       :descriptions (or dimension-descriptions {})}
   :grout/media {:db (ig/ref :grout/db)
                 :media-dir (:media-dir (or media {:media-dir "/data/media/grout"}))
                 :profile (:profile media)
                 :tunabrain (ig/ref :grout/tunabrain)
                 ;; dim-config is wired at init time (see :grout/media
                 ;; ig/init-key below). The static value here is
                 ;; unused; the real one is built from the live catalog.
                 :dim-catalog (ig/ref :grout/dim-catalog)
                 ;; Sample size the enrich-by-tag endpoint uses for inline
                 ;; (wait=true) directory enrichment; mirrors the worker's.
                 :sample-count (:sample-count directory-enrichment 5)}
   :grout/enrichment-worker (-> (merge {:enabled true :interval-ms 60000 :batch-size 10}
                                       enrichment
                                       {:db (ig/ref :grout/db)
                                        :tunabrain (ig/ref :grout/media)})
                                (update :enabled parse-bool))
   ;; Directory-level enrichment worker: sweeps directory_profiles and fans a
   ;; shared profile out to every child row. Uses the bare Tunabrain client
   ;; (not the per-file orchestrator map) — it calls /enrich/profile directly.
   :grout/directory-worker (-> (merge {:enabled true :interval-ms 60000 :batch-size 10 :sample-count 5}
                                      directory-enrichment
                                      {:db (ig/ref :grout/db)
                                       :tunabrain (ig/ref :grout/tunabrain)})
                               (update :enabled parse-bool))
   :grout/retention-job (-> (merge {:enabled true :interval-ms 3600000 :cap 20 :bucket-ms 5000}
                                   retention
                                   {:db (ig/ref :grout/db)})
                            (update :enabled parse-bool))
   :grout/http {:port (parse-port (or (:port server) 8080))
                :db (ig/ref :grout/db)
                :media (ig/ref :grout/media)}})

(defmethod ig/init-key :grout/logger [_ {:keys [level]}]
  (log/set-level! level)
  (log/info "Logger initialised" {:level level})
  {:level level})

(defmethod ig/halt-key! :grout/logger [_ _]
  (log/info "Logger shut down"))

(defmethod ig/init-key :grout/db [_ opts]
  (let [ds (db/make-datasource opts)]
    (db/migrate! ds)
    (log/info "Database ready")
    ds))

(defmethod ig/halt-key! :grout/db [_ ds]
  (db/close-datasource! ds)
  (log/info "Database connection closed"))

(defmethod ig/init-key :grout/tunabrain [_ cfg]
  (log/info "Tunabrain client ready" {:endpoint (:endpoint cfg)})
  (tunabrain/create cfg))

(defmethod ig/halt-key! :grout/tunabrain [_ client]
  (when (and client (satisfies? java.io.Closeable client))
    (.close client)))

(defmethod ig/init-key :grout/tunarr-scheduler [_ cfg]
  ;; If the upstream config resolves to nil (i.e. TUNARR_SCHEDULER_URL
  ;; is unset, or resolves to an empty string), skip init and let the
  ;; catalog fetch fall back to an empty dim-config. Integrant
  ;; invokes init-key even for nil values in the system map (it does
  ;; not skip them), so we have to guard here.
  (if-not cfg
    (do (log/info "Tunarr Scheduler disabled (no endpoint configured)")
        nil)
    (let [client (tunarr-scheduler/create cfg)]
      (log/info "Tunarr Scheduler client ready" {:endpoint (:endpoint cfg)})
      client)))

(defmethod ig/halt-key! :grout/tunarr-scheduler [_ client]
  (when (and client (satisfies? java.io.Closeable client))
    (.close client)))

(defmethod ig/init-key :grout/dim-catalog [_ {:keys [client descriptions]}]
  ;; Fetch the live dimension catalog at startup. If the client is
  ;; nil (TUNARR_SCHEDULER_URL not set), the catalog is empty and a
  ;; warning is logged. If the client exists but Tunarr Scheduler is
  ;; unreachable, we throw — the safer failure mode than starting
  ;; with an empty catalog (which would silently degrade AI quality).
  (if-not client
    (do (log/warn "TUNARR_SCHEDULER_URL not set; enrichment will skip /categorize (only /tags will run)")
        {})
    (try
      (let [catalog (tunarr-scheduler/fetch-dimensions-with-retry! client descriptions)]
        (log/info "Dimension catalog loaded" {:dimensions (count catalog)})
        catalog)
      (catch Exception e
        (log/error e "Failed to fetch dimensions from Tunarr Scheduler at startup; enrichment will skip /categorize")
        ;; Non-fatal: free-form /tags enrichment still works, just no
        ;; structured dimensions. The /categorize path will short-circuit.
        {}))))

(defmethod ig/halt-key! :grout/dim-catalog [_ _]
  nil)

(defmethod ig/init-key :grout/media [_ {:keys [db media-dir profile tunabrain dim-catalog sample-count]}]
  (let [dim-config (build-dim-config dim-catalog)]
    (log/info "Media store ready" {:media-dir media-dir :dim-config-count (count dim-config)})
    {:ds db :media-dir media-dir :profile profile
     :tunabrain tunabrain
     ;; The orchestrator reads (:dim-config tunabrain) — see
     ;; enrichment.worker/run-once! and http.media/enrich-handler.
     :dim-config dim-config
     ;; Consumed by the enrich-by-tag handler for inline (wait=true) enrichment.
     :sample-count (or sample-count 5)}))

(defmethod ig/halt-key! :grout/media [_ _]
  nil)

(defmethod ig/init-key :grout/enrichment-worker [_ {:keys [db] :as cfg}]
  (worker/start! (assoc cfg :ds db)))

(defmethod ig/halt-key! :grout/enrichment-worker [_ w]
  (worker/stop! w))

(defmethod ig/init-key :grout/directory-worker [_ {:keys [db] :as cfg}]
  ;; cfg already carries :tunabrain (the bare client, via the :grout/tunabrain
  ;; ref) and the interval/batch/sample-count knobs; just supply :ds.
  (directory-worker/start! (assoc cfg :ds db)))

(defmethod ig/halt-key! :grout/directory-worker [_ w]
  (directory-worker/stop! w))

(defmethod ig/init-key :grout/retention-job [_ {:keys [db] :as cfg}]
  (retention/start! (assoc cfg :ds db)))

(defmethod ig/halt-key! :grout/retention-job [_ j]
  (retention/stop! j))

(defmethod ig/init-key :grout/http [_ opts]
  (http/start! opts))

(defmethod ig/halt-key! :grout/http [_ server]
  (http/stop! server))
