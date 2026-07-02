(ns grout.migrate
  (:gen-class)
  (:require [grout.db :as db]
            [taoensso.timbre :as log]))

(defn- getenv [k] (System/getenv k))

(defn -main [& _]
  (let [ds (db/make-datasource {:jdbc-url (or (getenv "GROUT_DATABASE_URL")
                                               "jdbc:postgresql://localhost:5432/grout")
                                :username (getenv "GROUT_DATABASE_USER")
                                :password (getenv "GROUT_DATABASE_PASS")})]
    (try
      (db/migrate! ds)
      (println "migrations complete")
      (System/exit 0)
      (catch Throwable t
        (log/error t "migration failed")
        (println "migration failed:" (ex-message t))
        (System/exit 1))
      (finally
        (db/close-datasource! ds)))))
