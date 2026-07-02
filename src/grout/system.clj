(ns grout.system
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [grout.db :as db]
            [grout.enrichment.worker :as worker]
            [grout.http.server :as http]
            [grout.retention :as retention]
            [grout.tunabrain :as tunabrain]
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

(defn ->system-config
  "Produce the Integrant system configuration map from the raw config map."
  [{:keys [log-level server database media tunabrain enrichment retention]}]
  {:grout/logger {:level (parse-log-level (or log-level :info))}
   :grout/db {:jdbc-url (:jdbc-url database)
              :username (:username database)
              :password (:password database)}
   :grout/tunabrain (or tunabrain {:endpoint "http://tunabrain:8080"})
   :grout/media {:db (ig/ref :grout/db)
                 :media-dir (:media-dir (or media {:media-dir "/data/media/grout"}))
                 :profile (:profile media)
                 :tunabrain (ig/ref :grout/tunabrain)}
   :grout/enrichment-worker (-> (merge {:enabled true :interval-ms 60000 :batch-size 10}
                                       enrichment
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
  (tunabrain/client cfg))

(defmethod ig/init-key :grout/media [_ {:keys [db media-dir profile tunabrain]}]
  (log/info "Media store ready" {:media-dir media-dir})
  {:ds db :media-dir media-dir :profile profile :tunabrain tunabrain})

(defmethod ig/halt-key! :grout/media [_ _]
  nil)

(defmethod ig/init-key :grout/enrichment-worker [_ {:keys [db] :as cfg}]
  (worker/start! (assoc cfg :ds db)))

(defmethod ig/halt-key! :grout/enrichment-worker [_ w]
  (worker/stop! w))

(defmethod ig/init-key :grout/retention-job [_ {:keys [db] :as cfg}]
  (retention/start! (assoc cfg :ds db)))

(defmethod ig/halt-key! :grout/retention-job [_ j]
  (retention/stop! j))

(defmethod ig/init-key :grout/http [_ opts]
  (http/start! opts))

(defmethod ig/halt-key! :grout/http [_ server]
  (http/stop! server))
