(ns grout.media.intake
  "Intake pipeline (GROUT.md §8) with content-addressed dedup.

   Flow: hash the ORIGINAL source bytes -> if a row with that hash exists,
   deduplicate (union tags, fill blank metadata, revive if superseded) without
   re-storing; otherwise copy-normalize the source into a content-addressed
   path under the media dir and insert a new row (enriched=false). The caller's
   source file is never mutated."
  (:require [clojure.java.io :as io]
            [grout.media.hash :as hash]
            [grout.media.probe :as probe]
            [grout.media.store :as store]
            [taoensso.timbre :as log]))

;; Grout owns the filler/content boundary, not the caller. grout-cli defaults
;; kind to "filler" and bulk uploads pass one kind for a whole directory, so the
;; caller-supplied kind is unreliable (e.g. 44-min episodes arriving as filler).
;; The probed duration is authoritative: >= 15 min is long-form `program`
;; ("content"), under 15 min is `filler`. Bumpers are the exception — a
;; short-form special case of filler (idents/interstitials, always well under
;; the threshold, only ever used to fill gaps), kept distinct so retention
;; buckets by (channel, kind, duration) still see them.
(def long-form-threshold-ms (* 15 60 1000))

(defn derive-kind
  "The stored kind for a new item, from the caller's `kind` and the probed
   `duration-ms`. Explicit bumpers are preserved; everything else is `program`
   at/over the 15-minute threshold and `filler` under it."
  [caller-kind duration-ms]
  (cond
    (= caller-kind "bumper")                                   "bumper"
    (and duration-ms (>= duration-ms long-form-threshold-ms))  "program"
    :else                                                      "filler"))

(defn- unique-violation?
  "True when `e` — or any cause in its chain — is a Postgres unique-constraint
   violation (SQLSTATE 23505). Intake's content_hash check-then-insert is not
   atomic: a concurrent (or interrupted-then-retried) upload of the same bytes
   can commit between our `find-by-hash` miss and our `create!`, so the insert
   then trips the unique content_hash/path index. That is a dedup, not a server
   error — the item is (now) stored."
  [e]
  (some (fn [t]
          (and (instance? java.sql.SQLException t)
               (= "23505" (.getSQLState ^java.sql.SQLException t))))
        (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- dedup!
  "Merge an intake request into an existing row: union tags, fill blank
   name/description/channel, and revive if superseded."
  [ds existing {:keys [tags name description channel]}]
  (let [merged {:tags          (vec (distinct (concat (vec (:tags existing))
                                                      (vec (or tags [])))))
                :name          (or (not-empty (:name existing)) name)
                :description   (or (not-empty (:description existing)) description)
                :channel       (or (:channel existing) channel)
                :superseded_at nil}]
    (log/info "Intake deduplicated by hash"
              {:id (:id existing) :hash (:content_hash existing)})
    (store/update-fields! ds (:id existing) merged)))

(defn intake!
  "Run the intake pipeline for `req` (a map with :path :kind and optional
   :channel :tags :source :source-url :name :description). Returns
   {:row created-or-updated-row :deduplicated bool}."
  [{:keys [ds media-dir profile]}
   {:keys [path kind channel tags source source-url name description] :as req}]
  (let [content-hash (hash/sha256-file path)]
    (if-let [existing (store/find-by-hash ds content-hash)]
      {:row (dedup! ds existing req) :deduplicated true}
      (let [out (hash/content-path (or media-dir (.getParent (java.io.File. ^String path)))
                                   content-hash)
            {final-path :path pr :probe normalized :normalized}
            (probe/normalize-to! path out (or profile probe/default-profile))]
        (when-not (:duration-ms pr)
          (throw (ex-info "Could not determine media duration" {:path final-path})))
        (log/info "Intake stored new item"
                  {:path final-path :normalized normalized :hash content-hash})
        (try
          {:row (store/create! ds
                               {:kind (derive-kind kind (:duration-ms pr))
                                :path final-path
                                :content_hash content-hash
                                :name name
                                :description description
                                :channel channel
                                :tags (vec (or tags []))
                                :duration_ms (:duration-ms pr)
                                :width (:width pr)
                                :height (:height pr)
                                :vcodec (:vcodec pr)
                                :acodec (:acodec pr)
                                :source source
                                :source_url source-url
                                :enriched false})
           :deduplicated false}
          (catch Exception e
            ;; Lost the content_hash check-then-insert race: an identical upload
            ;; committed after our find-by-hash miss. Fold into the now-existing
            ;; row instead of surfacing the unique violation as a 500.
            (if-let [existing (and (unique-violation? e)
                                   (store/find-by-hash ds content-hash))]
              {:row (dedup! ds existing req) :deduplicated true}
              (do
                ;; The normalized file already landed at final-path before the
                ;; insert failed; without cleanup it's orphaned on disk with no
                ;; DB row pointing at it.
                (io/delete-file final-path true)
                (throw e)))))))))
