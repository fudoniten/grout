(ns grout.media.store-test
  "Unit tests for the honeysql query builder. These exercise SQL generation
   only (no database), covering the query semantics from GROUT.md §7/§15."
  (:require [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [grout.media.store :as store]))

(defn- fmt [params]
  (sql/format (store/->query-sqlmap params)))

(deftest full-query-has-all-filters
  (let [[q & params] (fmt {:channel "britannia"
                           :tags ["fun" "daytime"]
                           :min-ms 65000
                           :max-ms 90000
                           :kind "bumper"
                           :limit 5})]
    (is (re-find #"superseded_at IS NULL" q) "excludes superseded by default")
    (is (re-find #"channel = \?" q) "legacy exact channel match")
    (is (re-find #"channels @> CAST\(ARRAY" q) "multi-channel containment match")
    (is (re-find #"channel IS NULL AND channels IS NULL" q) "generic = both unset")
    (is (re-find #"tags @> CAST\(ARRAY" q) "tag AND via containment")
    (is (re-find #"duration_ms >= \?" q) "min duration inclusive")
    (is (re-find #"duration_ms <= \?" q) "max duration inclusive")
    (is (re-find #"kind = \?" q))
    (is (re-find #"(?i)limit" q))
    (is (some #{65000} params))
    (is (some #{90000} params))
    (is (some #{"fun"} params))
    (is (some #{"daytime"} params))))

(deftest random-orders-randomly
  (let [[q] (fmt {:random true})]
    (is (re-find #"(?i)order by random\(\)" q))))

(deftest default-orders-by-created-at
  (let [[q] (fmt {})]
    (is (re-find #"(?i)order by created_at desc" q))))

(deftest default-order-has-id-tiebreaker
  ;; A stable secondary sort keeps equal created_at values in a deterministic
  ;; order so offset pagination doesn't shuffle rows between pages.
  (let [[q] (fmt {})]
    (is (re-find #"(?i)order by created_at desc, id desc" q))))

(deftest offset-paginates-when-positive
  (let [[q & params] (fmt {:offset 20 :limit 5})]
    (is (re-find #"(?i)offset" q))
    (is (some #{20} params))))

(deftest offset-omitted-when-zero-or-absent
  (is (not (re-find #"(?i)offset" (first (fmt {})))))
  (is (not (re-find #"(?i)offset" (first (fmt {:offset 0}))))))

(deftest no-filters-still-excludes-superseded
  (let [[q] (fmt {})]
    (is (re-find #"superseded_at IS NULL" q))))

(deftest include-superseded-drops-the-filter
  (let [[q] (fmt {:include-superseded? true})]
    (is (not (re-find #"superseded_at" q)))))

(deftest default-limit-is-applied
  (let [[q & params] (fmt {})]
    (is (re-find #"(?i)limit" q))
    (is (some #{10} params))))

;; --- multi-channel query semantics ------------------------------------------

(deftest channel-filter-omitted-when-no-channel-param
  ;; No channel filter at all -- neither the legacy nor multi-channel clause
  ;; should appear; every item (generic or channel-assigned) matches.
  (let [[q] (fmt {})]
    (is (not (re-find #"channels" q)))))

(deftest channel-filter-matches-legacy-or-multi-or-generic
  (let [[q & params] (fmt {:channel "toontown"})]
    (is (re-find #"channel = \?" q))
    (is (re-find #"channels @> CAST\(ARRAY\[\?\] AS text\[\]\)" q))
    (is (re-find #"channel IS NULL AND channels IS NULL" q))
    (is (= 2 (count (filter #{"toontown"} params)))
        "the channel param is bound twice: legacy exact match + containment check")))

;; --- tag-group (directory) helpers -----------------------------------------

(deftest pg-text-array-formats-array-literal
  (is (= "{\"a\",\"b\"}" (#'store/pg-text-array ["a" "b"])))
  (is (= "{}" (#'store/pg-text-array [])))
  (is (= "{\"channel:muse\"}" (#'store/pg-text-array ["channel:muse"]))))

(deftest pg-text-array-quotes-protect-special-chars
  ;; Double-quoting each element keeps commas/spaces inside a single element.
  (is (= "{\"a,b\",\"c\"}" (#'store/pg-text-array ["a,b" "c"]))))

(deftest pg-text-array-or-nil-distinguishes-unset-from-empty
  ;; Unlike pg-text-array (which renders "{}" for an empty collection),
  ;; pg-text-array-or-nil must produce nil (SQL NULL) for empty/nil input --
  ;; the query layer's "generic = both channel and channels unset" check
  ;; depends on channels being truly NULL, not an empty array.
  (is (nil? (#'store/pg-text-array-or-nil [])))
  (is (nil? (#'store/pg-text-array-or-nil nil)))
  (is (= "{\"toontown\"}" (#'store/pg-text-array-or-nil ["toontown"]))))

;; --- channels-for (single-channel -> channels[] mirror) --------------------

(deftest channels-for-wraps-a-non-blank-channel
  (is (= [:cast [:array ["toontown"]] [:raw "text[]"]]
         (#'store/channels-for "toontown"))))

(deftest channels-for-nil-for-blank-or-nil
  (is (nil? (#'store/channels-for nil)))
  (is (nil? (#'store/channels-for ""))))

(deftest row->filename-prefers-filename-tag
  (is (= "foo.mp4"
         (#'store/row->filename {:tags ["content-type:filler" "filename:foo.mp4"]}))))

(deftest row->filename-falls-back-to-name-then-basename
  (is (= "A Name" (#'store/row->filename {:tags ["x"] :name "A Name"})))
  (is (= "clip.mp4" (#'store/row->filename {:tags [] :name nil :path "/data/a/b/clip.mp4"})))
  (is (nil? (#'store/row->filename {:tags [] :name nil :path nil}))))
