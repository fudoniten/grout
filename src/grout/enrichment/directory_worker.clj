(ns grout.enrichment.directory-worker
  "Background directory-enrichment worker. Sweeps `pending` (and retry-ready
   `failed`) `directory_profiles`, derives a shared profile for each via
   Tunabrain's `/enrich/profile`, and fans it out to every child media row.

   This runs ALONGSIDE the per-file enrichment sweeper (`grout.enrichment.worker`)
   but is entirely separate: the per-file sweeper skips `parent-directory:`-tagged
   rows (see `grout.media.store/unenriched`), so the two never process the same
   media. One LLM call here classifies a whole directory; the per-file sweeper
   still handles ad-hoc, non-directory uploads."
  (:require [grout.directory-profiles :as dp]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn enrich-profile-one!
  "Enrich a single directory profile end-to-end and return the updated profile.

   Steps: sample the group's filenames, call Tunabrain, fan the result out to
   every child row (union tags, COALESCE-fill channel, mark enriched), then mark
   the profile `ready`. Any failure (no samples, empty result, Tunabrain error)
   marks the profile `failed` with backoff so a later sweep retries.

   The `tunabrain` arg is the bare `TunabrainClient`. Returns the profile row as
   left in the DB (`ready` or `failed`), or nil if no profile exists for the tag."
  [ds tunabrain sample-count tag-value]
  (when-let [profile (dp/get-profile-for-tag ds tag-value)]
    (try
      (let [samples (store/sample-filenames-by-tag ds tag-value sample-count)]
        (if (empty? samples)
          (do (log/warn "Directory profile has no sample filenames; marking failed"
                        {:tag tag-value})
              (dp/mark-failed! ds tag-value "no sample filenames available for tag"))
          (let [resp        (tb/request-enrich-profile! tunabrain (:concept_name profile)
                                                        samples :sample-count sample-count)
                dimensions  (:dimensions resp)
                free-tags   (:tags resp)
                new-tags    (dp/profile->tags dimensions free-tags)]
            (if (empty? new-tags)
              (do (log/warn "Tunabrain returned no dimensions or tags; marking failed for retry"
                            {:tag tag-value :warnings (:warnings resp)})
                  (dp/mark-failed! ds tag-value "enrichment produced no dimensions or tags"))
              (let [old-tags   (dp/profile->tags (:dimensions profile) (:tags profile))
                    stale-tags (vec (remove (set new-tags) old-tags))
                    channel    (dp/profile-channel dimensions)
                    n          (store/apply-directory-profile! ds tag-value new-tags stale-tags channel)]
                (log/info "Directory profile applied"
                          {:tag tag-value :rows n :dimensions (count dimensions)
                           :tags (count free-tags) :channel channel})
                (dp/mark-ready! ds tag-value dimensions free-tags
                                (store/count-by-tag ds tag-value)))))))
      (catch Exception e
        (log/warn e "Directory enrichment failed; marking failed for retry" {:tag tag-value})
        (dp/mark-failed! ds tag-value (or (ex-message e) (str e)))))))

(defn run-once!
  "Enrich up to `batch-size` pending profiles plus any retry-ready failed ones.
   Returns the number of profiles attempted."
  [ds tunabrain sample-count batch-size]
  (let [profiles (into (dp/pending-profiles ds batch-size)
                       (dp/failed-profiles-ready-for-retry ds batch-size))]
    (when (seq profiles)
      (log/info "Directory-enrichment sweep" {:profiles (count profiles)})
      (doseq [{:keys [tag_value]} profiles]
        (try (enrich-profile-one! ds tunabrain sample-count tag_value)
             (catch Exception e
               (log/warn e "Directory enrichment sweep item failed" {:tag tag_value})))))
    (count profiles)))

(defn start!
  "Start the periodic directory-enrichment worker. Returns a component map for
   stop!. Set :enabled false to run a no-op (tests/dev)."
  [{:keys [ds tunabrain sample-count interval-ms batch-size enabled]
    :or   {sample-count 5 interval-ms 60000 batch-size 10 enabled true}}]
  (if-not enabled
    (do (log/info "Directory-enrichment worker disabled") {:executor nil})
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay
       exec
       (fn [] (try (run-once! ds tunabrain sample-count batch-size)
                   (catch Throwable t
                     (log/error t "Directory-enrichment sweep crashed"))))
       (long interval-ms) (long interval-ms) TimeUnit/MILLISECONDS)
      (log/info "Directory-enrichment worker started"
                {:interval-ms interval-ms :batch-size batch-size :sample-count sample-count})
      {:executor exec})))

(defn stop! [{:keys [executor]}]
  (when executor
    (.shutdownNow executor)
    (log/info "Directory-enrichment worker stopped")))
