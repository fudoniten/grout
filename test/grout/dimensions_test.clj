(ns grout.dimensions-test
  "Tests for the dimension-value guard. These are pure functions over the
   controlled vocabulary (`dim-config`); no DB or network."
  (:require [clojure.test :refer [deftest is testing]]
            [grout.dimensions :as dim]))

(def ^:private dim-config
  {:audience {:description "who it's for" :values ["kids" "teen" "family" "adult"]}
   :channel  {:description "channels"     :values ["goldenreels" "britannia" "toontown"]}})

;; --- allowed-values / value-allowed? ---------------------------------------

(deftest allowed-values-returns-set-of-trimmed-strings
  (is (= #{"kids" "teen" "family" "adult"} (dim/allowed-values dim-config :audience)))
  (is (= #{"kids" "teen" "family" "adult"} (dim/allowed-values dim-config "audience"))
      "dimension may be given as a string"))

(deftest allowed-values-nil-for-unconfigured-dimension
  (is (nil? (dim/allowed-values dim-config :freshness))
      "a dimension with no configured vocabulary has no allowed set"))

(deftest value-allowed?-respects-vocabulary
  (is (true?  (dim/value-allowed? dim-config :channel "goldenreels")))
  (is (false? (dim/value-allowed? dim-config :channel "spectum")))
  (is (true?  (dim/value-allowed? dim-config :channel "  goldenreels  "))
      "values are compared trimmed")
  (is (true?  (dim/value-allowed? dim-config :freshness "whatever"))
      "unconfigured dimension → everything allowed"))

;; --- filter-selections (per-file /enrich/short-form shape) -----------------

(deftest filter-selections-drops-hallucinated-values-per-dimension
  (let [sels [{:dimension "audience" :values ["kids" "grownups"]}
              {:dimension "channel"  :values ["goldenreels"]}]
        {:keys [dimensions rejected]} (dim/filter-selections dim-config sels)]
    (is (= [{:dimension "audience" :values ["kids"]}
            {:dimension "channel"  :values ["goldenreels"]}]
           dimensions)
        "invalid 'grownups' dropped; valid values kept, selection order preserved")
    (is (= [{:dimension "audience" :value "grownups"}] rejected))))

(deftest filter-selections-drops-selection-with-no-valid-values
  (let [sels [{:dimension "channel" :values ["madeup" "spectum"]}]
        {:keys [dimensions rejected]} (dim/filter-selections dim-config sels)]
    (is (= [] dimensions)
        "a selection left with no valid values is removed entirely")
    (is (= #{{:dimension "channel" :value "madeup"}
             {:dimension "channel" :value "spectum"}}
           (set rejected)))))

(deftest filter-selections-passes-unconfigured-dimension-through
  (let [sels [{:dimension "freshness" :values ["fresh" "whatever"]}]
        {:keys [dimensions rejected]} (dim/filter-selections dim-config sels)]
    (is (= sels dimensions)
        "no vocabulary for 'freshness' → untouched")
    (is (= [] rejected))))

(deftest filter-selections-empty-dim-config-passes-everything
  (let [sels [{:dimension "channel" :values ["anything"]}]
        {:keys [dimensions rejected]} (dim/filter-selections {} sels)]
    (is (= sels dimensions))
    (is (= [] rejected))))

;; --- filter-dimension-map (directory /enrich/profile shape) ----------------

(deftest filter-dimension-map-drops-hallucinated-values
  (let [dims {:channel ["muse" "spectum"] :audience ["kids"]}
        {:keys [dimensions rejected]} (dim/filter-dimension-map dim-config dims)]
    (is (= {:audience ["kids"]} dimensions)
        "channel 'muse' and 'spectum' are both outside the vocabulary → channel dropped; kids kept")
    (is (= #{{:dimension :channel :value "muse"}
             {:dimension :channel :value "spectum"}}
           (set rejected)))))

(deftest filter-dimension-map-keeps-partial-valid-values
  (let [dims {:audience ["kids" "grownups" "teen"]}
        {:keys [dimensions rejected]} (dim/filter-dimension-map dim-config dims)]
    (is (= {:audience ["kids" "teen"]} dimensions))
    (is (= [{:dimension :audience :value "grownups"}] rejected))))

(deftest filter-dimension-map-passes-unconfigured-dimension-through
  (let [dims {:freshness ["fresh" "made-up"]}
        {:keys [dimensions rejected]} (dim/filter-dimension-map dim-config dims)]
    (is (= {:freshness ["fresh" "made-up"]} dimensions))
    (is (= [] rejected))))

(deftest log-rejected!-is-a-noop-on-empty
  (testing "does not throw on empty rejected list"
    (is (nil? (dim/log-rejected! [] {:id 1})))))
