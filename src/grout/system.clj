(ns grout.system
  (:require [integrant.core :as ig]
            [grout.db :as db]
            [grout.http.server :as http]
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

(defn ->system-config
  "Produce the Integrant system configuration map from the raw config map."
  [{:keys [log-level server database media tunabrain]}]
  {:grout/logger {:level (parse-log-level (or log-level :info))}
   :grout/db {:jdbc-url (:jdbc-url database)
              :username (:username database)
              :password (:password database)}
   :grout/media (or media {:media-dir "/data/media/grout"})
   :grout/http {:port (parse-port (or (:port server) 8080))
                :db (ig/ref :grout/db)
                :media (ig/ref :grout/media)
                :tunabrain (or tunabrain {:endpoint "http://tunabrain:8080"})}})

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

(defmethod ig/init-key :grout/media [_ opts]
  (log/info "Media config ready" opts)
  opts)

(defmethod ig/halt-key! :grout/media [_ _]
  nil)

(defmethod ig/init-key :grout/http [_ opts]
  (http/start! opts))

(defmethod ig/halt-key! :grout/http [_ server]
  (http/stop! server))
