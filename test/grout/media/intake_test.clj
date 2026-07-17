(ns grout.media.intake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [grout.media.hash :as hash]
            [grout.media.intake :as intake]
            [grout.media.probe :as probe]
            [grout.media.store :as store]))

(deftest dedup-unions-tags-fills-blanks-and-revives
  (let [captured (atom nil)]
    (with-redefs [hash/sha256-file    (fn [_] "h1")
                  store/find-by-hash  (fn [_ _] {:id 7 :tags ["old"] :name "Kept"
                                                 :description nil :channel nil
                                                 :superseded_at :some-ts})
                  store/update-fields! (fn [_ id fields] (reset! captured [id fields])
                                         (assoc fields :id id))]
      (let [{:keys [row deduplicated]}
            (intake/intake! {:ds nil}
                            {:path "/x.mp4" :kind "bumper" :tags ["new"]
                             :description "fresh" :channel "britannia"})]
        (is (true? deduplicated))
        (let [[id fields] @captured]
          (is (= 7 id))
          (is (= ["old" "new"] (:tags fields)) "tags unioned")
          (is (= "Kept" (:name fields)) "human name preserved")
          (is (= "fresh" (:description fields)) "blank description filled")
          (is (= "britannia" (:channel fields)) "blank channel filled")
          (is (nil? (:superseded_at fields)) "revived"))))))

(deftest derive-kind-splits-on-15-minutes
  ;; Bumpers preserved; everything else filler under 15 min, program at/over it.
  (is (= "bumper"  (intake/derive-kind "bumper"  3000)))
  (is (= "bumper"  (intake/derive-kind "bumper"  (* 60 60 1000))) "explicit bumper wins even if long")
  (is (= "filler"  (intake/derive-kind "filler"  60000)))
  (is (= "filler"  (intake/derive-kind "program" 60000)) "duration overrides an over-eager caller kind")
  (is (= "filler"  (intake/derive-kind "filler"  (dec intake/long-form-threshold-ms))))
  (is (= "program" (intake/derive-kind "filler"  intake/long-form-threshold-ms)) "exactly 15 min is content")
  (is (= "program" (intake/derive-kind "filler"  (* 44 60 1000))) "a 44-min episode is content, not filler")
  (is (= "filler"  (intake/derive-kind "filler"  nil)) "unknown duration defaults to filler"))

(deftest new-item-derives-kind-from-duration
  (let [created (atom nil)]
    (with-redefs [hash/sha256-file    (fn [_] "abcd")
                  store/find-by-hash  (fn [_ _] nil)
                  probe/normalize-to! (fn [_ out _] {:path out
                                                     :probe {:duration-ms (* 44 60 1000)}
                                                     :normalized false})
                  store/create!       (fn [_ row] (reset! created row) (assoc row :id 1))]
      ;; A 44-min upload that the CLI defaulted to filler is stored as program.
      (intake/intake! {:ds nil :media-dir "/m"}
                      {:path "/src/taskmaster.mkv" :kind "filler"})
      (is (= "program" (:kind @created))))))

(deftest new-item-normalizes-and-inserts
  (let [created (atom nil)]
    (with-redefs [hash/sha256-file     (fn [_] "abcd")
                  store/find-by-hash   (fn [_ _] nil)
                  probe/normalize-to!  (fn [_ out _] {:path out
                                                      :probe {:duration-ms 5000
                                                              :vcodec "h264" :acodec "aac"}
                                                      :normalized false})
                  store/create!        (fn [_ row] (reset! created row) (assoc row :id 1))]
      (let [{:keys [row deduplicated]}
            (intake/intake! {:ds nil :media-dir "/data/media/grout"}
                            {:path "/src/orig.mkv" :kind "bumper" :tags ["fun"]})]
        (is (false? deduplicated))
        (is (= "/data/media/grout/ab/abcd.mp4" (:path @created)) "content-addressed path")
        (is (= "abcd" (:content_hash @created)))
        (is (= 5000 (:duration_ms @created)))
        (is (false? (:enriched @created)))))))

(deftest new-item-without-duration-throws
  (with-redefs [hash/sha256-file    (fn [_] "abcd")
                store/find-by-hash  (fn [_ _] nil)
                probe/normalize-to! (fn [_ out _] {:path out :probe {} :normalized false})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (intake/intake! {:ds nil :media-dir "/m"}
                                 {:path "/x.mp4" :kind "bumper"})))))

(deftest content-hash-insert-race-folds-into-existing
  ;; The content_hash check-then-insert is not atomic: find-by-hash misses on
  ;; the pre-check, a racing (or interrupted-then-retried) identical upload
  ;; commits, and create! then trips the unique index (SQLSTATE 23505). Intake
  ;; must treat that as a dedup (-> 200), never surface it as a 500.
  (let [calls  (atom 0)
        merged (atom nil)]
    (with-redefs [hash/sha256-file     (fn [_] "dupehash")
                  ;; miss on the pre-check (call 1), hit on the post-violation retry
                  store/find-by-hash   (fn [_ _]
                                         (when (> (swap! calls inc) 1)
                                           {:id 99 :tags ["existing"] :name nil
                                            :description nil :channel nil :superseded_at nil}))
                  probe/normalize-to!  (fn [_ out _] {:path out
                                                      :probe {:duration-ms 5000}
                                                      :normalized false})
                  store/create!        (fn [_ _]
                                         (throw (java.sql.SQLException.
                                                 "duplicate key value violates unique constraint"
                                                 "23505")))
                  store/update-fields! (fn [_ id fields] (reset! merged [id fields])
                                         (assoc fields :id id))]
      (let [{:keys [row deduplicated]}
            (intake/intake! {:ds nil :media-dir "/data/media/grout"}
                            {:path "/src/orig.mkv" :kind "bumper" :tags ["fun"]})]
        (is (true? deduplicated) "a lost insert race is reported as a dedup, not an error")
        (is (= 99 (:id row)))
        (let [[id fields] @merged]
          (is (= 99 id))
          (is (= ["existing" "fun"] (:tags fields)) "tags unioned into the winner's row"))))))

(deftest non-unique-sql-error-propagates
  ;; Only a unique violation is swallowed as a dedup; any other DB error must
  ;; still surface so it isn't silently misreported as a successful store.
  (with-redefs [hash/sha256-file    (fn [_] "hh")
                store/find-by-hash  (fn [_ _] nil)
                probe/normalize-to! (fn [_ out _] {:path out :probe {:duration-ms 1000} :normalized false})
                store/create!       (fn [_ _] (throw (java.sql.SQLException. "connection lost" "08006")))]
    (is (thrown? java.sql.SQLException
                 (intake/intake! {:ds nil :media-dir "/m"}
                                 {:path "/x.mp4" :kind "bumper"})))))

(deftest non-unique-sql-error-cleans-up-orphaned-file
  ;; The normalized file is written to its content-addressed path before the
  ;; insert runs. If the insert then fails for a non-dedup reason, that file
  ;; must not be left behind orphaned on disk with no DB row pointing at it.
  (let [deleted (atom nil)]
    (with-redefs [hash/sha256-file    (fn [_] "hh")
                  store/find-by-hash  (fn [_ _] nil)
                  probe/normalize-to! (fn [_ out _] {:path out :probe {:duration-ms 1000} :normalized false})
                  store/create!       (fn [_ _] (throw (java.sql.SQLException. "connection lost" "08006")))
                  io/delete-file      (fn [path _] (reset! deleted path))]
      (is (thrown? java.sql.SQLException
                   (intake/intake! {:ds nil :media-dir "/m"}
                                   {:path "/x.mp4" :kind "bumper"})))
      (is (= "/m/hh/hh.mp4" @deleted) "orphaned normalized file removed after failed insert"))))
