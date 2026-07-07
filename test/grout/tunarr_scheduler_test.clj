(ns grout.tunarr_scheduler_test
  "Tests for the Tunarr Scheduler dimensions-catalog client."
  (:require [clojure.test :refer [deftest is]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [grout.tunarr_scheduler :as ts]))

(def ^:private endpoint "http://tunarr-scheduler.example.com")
(def ^:private cl (ts/create {:endpoint endpoint}))

(def ^:private dim-descriptions
  {:audience "Audience segments"
   :channel  "Tunarr Scheduler channels"})

(defn- fake-get
  "A `http/get` mock that returns canned responses keyed by URL suffix
  and throws on unknown URLs. Replaces the URL-string dispatch in the
  older `case` form to avoid deep indentation in test bodies."
  [responses]
  (fn [url _]
    (or (get responses url)
        (throw (ex-info (str "unexpected url: " url) {})))))

;; --- fetch-dimensions! ------------------------------------------------------

(deftest fetch-dimensions-builds-categories-from-list-and-values
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}
                                  {:value "teen" :usage-count 5}]})
        channel-body  (json/generate-string
                        {:values [{:value "goldenreels" :usage-count 50}
                                  {:value "britannia"  :usage-count 30}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 2}
                                      {:name "channel"  :value-count 2}]})
        responses     {(str endpoint "/api/dimensions")              {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values") {:status 200 :body audience-body}
                       (str endpoint "/api/dimensions/channel/values")  {:status 200 :body channel-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-dimensions! cl dim-descriptions)]
        (is (= 2 (count result)))
        (is (= "Audience segments" (get-in result [:audience :description])))
        (is (= ["kids" "teen"] (get-in result [:audience :values])))
        (is (= "Tunarr Scheduler channels" (get-in result [:channel :description])))
        (is (= ["britannia" "goldenreels"] (get-in result [:channel :values]))
            "values are sorted alphabetically")))))

(deftest fetch-dimensions-falls-back-to-generic-description-for-unlisted-dim
  (let [mood-body   (json/generate-string
                       {:values [{:value "happy" :usage-count 1}
                                 {:value "sad"   :usage-count 1}]})
        catalog-body (json/generate-string
                       {:dimensions [{:name "mood" :value-count 2}]})
        responses    {(str endpoint "/api/dimensions")           {:status 200 :body catalog-body}
                      (str endpoint "/api/dimensions/mood/values") {:status 200 :body mood-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-dimensions! cl dim-descriptions)]
        (is (= "Dimension 'mood' from Tunarr Scheduler"
               (get-in result [:mood :description]))
            "unlisted dimension gets the generic fallback description")
        (is (= ["happy" "sad"] (get-in result [:mood :values])))))))

(deftest fetch-dimensions-skips-dimension-on-individual-fetch-failure
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 2}
                                      {:name "broken"   :value-count 2}]})
        responses     {(str endpoint "/api/dimensions")                 {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values")  {:status 200 :body audience-body}
                       (str endpoint "/api/dimensions/broken/values")    {:status 500 :body "boom"}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-dimensions! cl dim-descriptions)]
        (is (= 1 (count result)) "only the working dimension is in the result")
        (is (contains? result :audience))
        (is (not (contains? result :broken)))))))

;; Regression: the pre-fix `fetch-dimensions!` had `(name name)` where
;; `{:keys [name]}` shadowed `clojure.core/name`, so the inner call
;; crashed with a ClassCastException. The exception was swallowed by
;; the per-dimension try/catch, so every dimension was silently
;; skipped — the catalog always came back as `{}`. This test would
;; have failed under that bug (count is 2, not 0) and passes under
;; the fix.
(deftest fetch-dimensions-does-not-shadow-clojure-core-name
  ;; Same body as fetch-dimensions-builds-categories-from-list-and-values
  ;; but with the assertion phrased to specifically catch a swallowed
  ;; exception: we check that BOTH dimensions are in the result, not
  ;; just that the result is non-empty. Under the shadowing bug, every
  ;; dimension would be silently skipped and the result would be {}.
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}]})
        channel-body  (json/generate-string
                        {:values [{:value "goldenreels" :usage-count 50}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 1}
                                      {:name "channel"  :value-count 1}]})
        responses     {(str endpoint "/api/dimensions")              {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values") {:status 200 :body audience-body}
                       (str endpoint "/api/dimensions/channel/values")  {:status 200 :body channel-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-dimensions! cl dim-descriptions)]
        (is (= 2 (count result))
            "both dimensions must be present (the pre-fix shadowing bug silently dropped them all)")
        (is (every? #(contains? result %) [:audience :channel])
            "each dimension name must be a key in the result map")))))

(deftest fetch-dimensions-throws-on-404
  (with-redefs [http/get (constantly {:status 404 :body "not found"})]
    (is (thrown? clojure.lang.ExceptionInfo (ts/fetch-dimensions! cl dim-descriptions)))))

(deftest fetch-dimensions-throws-on-connection-refused
  (with-redefs [http/get (fn [_ _]
                          (throw (java.net.ConnectException. "refused")))]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"connection refused"
          (ts/fetch-dimensions! cl dim-descriptions)))))

;; --- retry -----------------------------------------------------------------

(deftest retry-succeeds-on-second-attempt
  (let [attempts (atom 0)]
    (with-redefs [ts/fetch-dimensions! (fn [_ _]
                                          (swap! attempts inc)
                                          (if (= 1 @attempts)
                                            (throw (java.net.ConnectException. "refused"))
                                            {:audience {:description "A" :values ["kids"]}}))]
      (let [result (ts/fetch-dimensions-with-retry!
                     (ts/create {:endpoint endpoint}) dim-descriptions 3)]
        (is (= 2 @attempts))
        (is (contains? result "audience"))))))

;; --- create -----------------------------------------------------------------

(deftest create-strips-trailing-slashes
  (let [c (ts/create {:endpoint "http://tunarr-scheduler:5545///"})]
    (is (= "http://tunarr-scheduler:5545" (:endpoint c)))))

(deftest create-requires-endpoint
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":endpoint"
        (ts/create {}))))
