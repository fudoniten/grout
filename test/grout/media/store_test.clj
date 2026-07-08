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
    (is (re-find #"channel = \? OR channel IS NULL" q) "channel OR generic")
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

;; --- tag-group (directory) helpers -----------------------------------------

(deftest pg-text-array-formats-array-literal
  (is (= "{\"a\",\"b\"}" (#'store/pg-text-array ["a" "b"])))
  (is (= "{}" (#'store/pg-text-array [])))
  (is (= "{\"channel:muse\"}" (#'store/pg-text-array ["channel:muse"]))))

(deftest pg-text-array-quotes-protect-special-chars
  ;; Double-quoting each element keeps commas/spaces inside a single element.
  (is (= "{\"a,b\",\"c\"}" (#'store/pg-text-array ["a,b" "c"]))))

(deftest row->filename-prefers-filename-tag
  (is (= "foo.mp4"
         (#'store/row->filename {:tags ["content-type:filler" "filename:foo.mp4"]}))))

(deftest row->filename-falls-back-to-name-then-basename
  (is (= "A Name" (#'store/row->filename {:tags ["x"] :name "A Name"})))
  (is (= "clip.mp4" (#'store/row->filename {:tags [] :name nil :path "/data/a/b/clip.mp4"})))
  (is (nil? (#'store/row->filename {:tags [] :name nil :path nil}))))
