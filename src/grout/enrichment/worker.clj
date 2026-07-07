(ns grout.enrichment.worker
  "Background enrichment worker (GROUT.md §9/§11). Periodically sweeps
   enriched=false rows and enriches them via Tunabrain, so intake never blocks
   on the AI call."
  (:require [grout.media.enrich :as enrich]
            [grout.media.store :as store]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn run-once!
  "Enrich up to `batch-size` pending rows. Returns the number attempted.

  The `tunabrain` arg is the *enrichment orchestrator* map (see system
  config): it carries both the `TunabrainClient` and the `dim-config`
  (the dimensions catalog passed to `/categorize`). Callers wire this
  map via `:grout/media` in the Integrant system."
  [ds tunabrain batch-size]
  (let [rows (store/unenriched ds batch-size)]
    (when (seq rows)
      (log/info "Enrichment sweep" {:pending (count rows)})
      (doseq [{:keys [id]} rows]
        (try (enrich/enrich-one! ds (:tunabrain tunabrain) (:dim-config tunabrain) id)
             (catch Exception e
               (log/warn e "Enrichment failed for row" {:id id})))))
    (count rows)))

(defn start!
  "Start the periodic worker. Returns a component map for stop!. Set
   :enabled false to run a no-op (e.g. in tests/dev)."
  [{:keys [ds tunabrain interval-ms batch-size enabled]
    :or   {interval-ms 60000 batch-size 10 enabled true}}]
  (if-not enabled
    (do (log/info "Enrichment worker disabled") {:executor nil})
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay
       exec
       (fn [] (try (run-once! ds tunabrain batch-size)
                   (catch Throwable t
                     (log/error t "Enrichment sweep crashed"))))
       (long interval-ms) (long interval-ms) TimeUnit/MILLISECONDS)
      (log/info "Enrichment worker started"
                {:interval-ms interval-ms :batch-size batch-size})
      {:executor exec})))

(defn stop! [{:keys [executor]}]
  (when executor
    (.shutdownNow executor)
    (log/info "Enrichment worker stopped")))
