(ns grout.directory-profiles-test
  "Unit tests for the pure helpers in grout.directory-profiles: the profile ->
  tag expansion, channel extraction, and the growth-threshold predicate. The
  table-access functions require a database and are exercised via the worker /
  route tests (with the DB layer mocked)."
  (:require [clojure.test :refer [deftest is]]
            [grout.directory-profiles :as dp]))

;; --- profile->tags ----------------------------------------------------------

(deftest profile->tags-expands-dimensions-and-appends-free-form
  (is (= #{"channel:muse" "audience:adult" "jazz" "music"}
         (set (dp/profile->tags {:channel ["muse"] :audience ["adult"]}
                                ["jazz" "music"])))))

(deftest profile->tags-handles-multi-valued-dimensions
  (is (= #{"channel:muse" "channel:chronicle"}
         (set (dp/profile->tags {:channel ["muse" "chronicle"]} [])))))

(deftest profile->tags-drops-blanks-and-dedups
  (is (= ["channel:muse" "ok"]
         (dp/profile->tags {:channel ["" "muse"]} ["" "  " "ok" "ok"]))))

(deftest profile->tags-accepts-string-dimension-keys
  ;; JSONB may deserialize keys as strings depending on the read path; name
  ;; works for both keyword and string keys.
  (is (= ["channel:muse"]
         (dp/profile->tags {"channel" ["muse"]} []))))

(deftest profile->tags-empty-profile-is-empty
  (is (= [] (dp/profile->tags {} [])))
  (is (= [] (dp/profile->tags nil nil))))

;; --- profile-channel --------------------------------------------------------

(deftest profile-channel-takes-first-channel-value
  (is (= "muse" (dp/profile-channel {:channel ["muse" "chronicle"]})))
  (is (= "muse" (dp/profile-channel {"channel" ["muse"]}))))

(deftest profile-channel-nil-when-absent-or-blank
  (is (nil? (dp/profile-channel {})))
  (is (nil? (dp/profile-channel {:channel []})))
  (is (nil? (dp/profile-channel {:channel [""]})))
  (is (nil? (dp/profile-channel nil))))

;; --- growth-exceeded? -------------------------------------------------------

(deftest growth-exceeded-triggers-above-threshold
  ;; 10 -> 13 is 30% growth, over the 20% threshold.
  (is (dp/growth-exceeded? {:item_count_at_enrichment 10} 13 20)))

(deftest growth-exceeded-false-at-or-below-threshold
  ;; 10 -> 12 is exactly 20%, not strictly over.
  (is (not (dp/growth-exceeded? {:item_count_at_enrichment 10} 12 20)))
  (is (not (dp/growth-exceeded? {:item_count_at_enrichment 10} 11 20))))

(deftest growth-exceeded-true-for-first-enrichment
  ;; A never-enriched profile (count 0) always needs enrichment.
  (is (dp/growth-exceeded? {:item_count_at_enrichment 0} 1 20)))

(deftest growth-exceeded-false-on-shrinkage
  ;; Deletions don't invalidate an existing profile.
  (is (not (dp/growth-exceeded? {:item_count_at_enrichment 100} 80 20))))
