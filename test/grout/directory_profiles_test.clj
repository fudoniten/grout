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

;; --- ->profile (DB row → response shape) -----------------------------------
;;
;; Regression for the live 500 on `GET /grout/directory-profiles/<tag>`:
;; jsonb columns deserialize to Clojure data with STRING keys on the object
;; values; the OpenAPI response schema declares `[:map-of :keyword ...]`,
;; so the response-coercion layer rejects the body. The fix canonicalizes
;; dimension keys to keywords in `->profile`.

(deftest ->profile-canonicalizes-dimension-keys-to-keywords
  (let [row {:status "ready"
             :tag_value "parent-directory:2019"
             :concept_name "Intelligence Squared 2019"
             :dimensions {"channel" ["IQ2 Debates"] "audience" ["adult"]}
             :tags ["debates" "iq2"]}
        out (dp/->profile row)]
    (is (= {:channel ["IQ2 Debates"] :audience ["adult"]}
           (:dimensions out))
        "string-keyed dimensions must be converted to keyword keys")
    (is (= ["debates" "iq2"] (:tags out)))))

(deftest ->profile-preserves-nil-dimensions
  ;; A profile that has never been enriched (no row in directory_profiles with
  ;; a dimensions jsonb value) must not 500.
  (is (nil? (-> (dp/->profile {:status "pending" :tag_value "x" :dimensions nil :tags nil})
                :dimensions))))

(deftest ->profile-leaves-tags-alone
  ;; `tags` is a vector of strings, not a map; it passes through as-is.
  (is (= ["a" "b"] (-> (dp/->profile {:dimensions {} :tags ["a" "b"]}) :tags))))

;; Regression for the live 500 on `GET /grout/directory-profiles/parent-directory:2019`
;; where the *tags* jsonb held a non-array value; the response schema
;; `[:maybe [:vector :string]]` rejected it ({:tags ["invalid type"]}). The tags
;; analog of the dimensions fix: `->profile` normalizes any mis-shaped tags
;; value so the read endpoint reports it rather than 500ing.

(deftest ->profile-coerces-scalar-tags-to-vector
  (is (= ["iq2"] (-> (dp/->profile {:dimensions {} :tags "iq2"}) :tags))))

(deftest ->profile-coerces-object-tags-to-nil
  ;; A jsonb object can't be represented as tags; normalize to nil, not a 500.
  (is (nil? (-> (dp/->profile {:dimensions {} :tags {"a" 1}}) :tags))))

(deftest ->profile-drops-blank-tag-elements
  (is (= ["a" "b"] (-> (dp/->profile {:dimensions {} :tags ["a" "" "  " "b"]}) :tags))))

(deftest ->profile-preserves-nil-tags
  (is (nil? (-> (dp/->profile {:dimensions {} :tags nil}) :tags))))
