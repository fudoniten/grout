(ns grout.db
  (:require [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defn make-datasource
  "Creates a HikariCP connection pool from a config map with keys
   :jdbc-url, :username, :password."
  [{:keys [jdbc-url username password]}]
  (log/info "Creating datasource" {:jdbc-url jdbc-url :username username})
  (conn/->pool HikariDataSource
               {:jdbcUrl jdbc-url
                :username username
                :password password
                :maximumPoolSize 10}))

(defn close-datasource! [^HikariDataSource ds]
  (when ds (.close ds)))

(defn- migratus-config [ds]
  {:store :database
   :migration-dir "migrations"
   :db {:datasource ds}})

(defn migrate! [ds]
  (let [cfg (migratus-config ds)
        pending (migratus/pending-list cfg)]
    (if (seq pending)
      (log/info "Pending migrations to apply:" (mapv :id pending))
      (log/info "No pending migrations"))
    (migratus/migrate cfg)
    (log/info "Migrations complete")))

(defn rollback! [ds]
  (migratus/rollback (migratus-config ds)))

(defn run-migrations!
  "Exec-fn entry point for `clojure -X:migrate`."
  [{:keys [action] :or {action "migrate"}}]
  (let [ds (make-datasource {:jdbc-url (or (System/getenv "GROUT_DATABASE_URL")
                                           "jdbc:postgresql://localhost:5432/grout")
                             :username (System/getenv "GROUT_DATABASE_USER")
                             :password (System/getenv "GROUT_DATABASE_PASS")})]
    (try
      (case action
        "migrate" (migrate! ds)
        "rollback" (rollback! ds)
        (log/error "Unknown migration action" action))
      (finally
        (close-datasource! ds)))))

(defn check-connection
  "Run a lightweight connectivity probe against the datasource.
   Returns {:ok true} or {:ok false :error ...}."
  [ds]
  (try
    (jdbc/execute-one! ds ["SELECT 1 AS ping"]
                       {:builder-fn rs/as-unqualified-lower-maps})
    {:ok true}
    (catch Throwable t
      {:ok false :error (ex-message t)})))
