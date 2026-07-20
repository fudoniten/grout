(ns grout.tunarr-scheduler-test
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

;; --- fetch-value-descriptions! ----------------------------------------------

(deftest fetch-value-descriptions-returns-per-dimension-value-description-map
  (let [descriptions-body (json/generate-string
                            {:dimensions
                             {:channel  {:description ""
                                         :values [{:value "toontown"  :description "Animated content..."}
                                                  {:value "infobytes" :description "Science & technology"}
                                                  {:value "spotlight" :description "All movies, all the time."}]}
                              :audience {:description "Who this is for"
                                         :values [{:value "kids" :description "Appropriate for young children"}]}}})
        responses         {(str endpoint "/api/dimensions/descriptions")
                           {:status 200 :body descriptions-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-value-descriptions! cl)]
        (is (= #{:channel :audience} (set (keys result))))
        (is (= "Animated content..." (get-in result [:channel "toontown"])))
        (is (= "Science & technology" (get-in result [:channel "infobytes"])))
        (is (= "All movies, all the time." (get-in result [:channel "spotlight"])))
        (is (= "Appropriate for young children" (get-in result [:audience "kids"])))))))

(deftest fetch-value-descriptions-keeps-empty-descriptions-not-drops-them
  (let [descriptions-body (json/generate-string
                            {:dimensions
                             {:channel {:description ""
                                        :values [{:value "toontown"  :description "Animated"}
                                                 {:value "blank-one" :description ""}]}}})
        responses         {(str endpoint "/api/dimensions/descriptions")
                           {:status 200 :body descriptions-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-value-descriptions! cl)]
        (is (= 2 (count (:channel result))) "blank-description values stay in the controlled vocabulary")
        (is (= "" (get-in result [:channel "blank-one"])))))))

(deftest fetch-value-descriptions-returns-empty-map-on-404
  (with-redefs [http/get (constantly {:status 404 :body "no such endpoint"})]
    (let [result (ts/fetch-value-descriptions! cl)]
      (is (= {} result) "404 → empty map so callers fall back to the static dimension descriptions"))))

(deftest fetch-value-descriptions-returns-empty-map-on-empty-payload
  (with-redefs [http/get (constantly {:status 200 :body (json/generate-string {:dimensions {}})})]
    (let [result (ts/fetch-value-descriptions! cl)]
      (is (= {} result) "empty dimensions map → empty map, not a crash"))))

(deftest fetch-value-descriptions-propagates-non-404-http-errors
  (with-redefs [http/get (constantly {:status 503 :body "unavailable"})]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"503"
          (ts/fetch-value-descriptions! cl))
        "non-404 HTTP failures should propagate so callers can retry")))

;; --- fetch-dimensions! with value-descriptions ------------------------------

(deftest fetch-dimensions-embeds-value-descriptions-in-channel-category
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}]})
        channel-body  (json/generate-string
                        {:values [{:value "goldenreels" :usage-count 50}
                                  {:value "toontown"    :usage-count 30}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 1}
                                      {:name "channel"  :value-count 2}]})
        responses     {(str endpoint "/api/dimensions")                {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values") {:status 200 :body audience-body}
                       (str endpoint "/api/dimensions/channel/values")  {:status 200 :body channel-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result    (ts/fetch-dimensions! cl dim-descriptions
                                            {:channel {"toontown" "Animated content"
                                                       "goldenreels" "Classic films"}})
            channel-desc (get-in result [:channel :description])]
        (is (clojure.string/includes? channel-desc "toontown: Animated content")
            "channel description embeds each value with its TS description")
        (is (clojure.string/includes? channel-desc "goldenreels: Classic films"))
        (is (= ["goldenreels" "toontown"] (get-in result [:channel :values]))
            "values list is unchanged regardless of descriptions")))))

(deftest fetch-dimensions-embeds-value-descriptions-for-non-channel-dimension
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}
                                  {:value "teen" :usage-count 5}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 2}]})
        responses     {(str endpoint "/api/dimensions")                {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values") {:status 200 :body audience-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result        (ts/fetch-dimensions! cl dim-descriptions
                                                {:audience {"kids" "Appropriate for young children"}})
            audience-desc  (get-in result [:audience :description])]
        (is (clojure.string/includes? audience-desc "kids: Appropriate for young children")
            "non-channel dimensions get the same per-value prompt treatment as channel")
        (is (clojure.string/includes? audience-desc "teen: ")
            "values with no description are still listed, with a blank trailing description")))))

(deftest fetch-dimensions-falls-back-when-value-descriptions-map-empty
  (let [channel-body (json/generate-string
                        {:values [{:value "goldenreels" :usage-count 50}]})
        catalog-body (json/generate-string
                       {:dimensions [{:name "channel" :value-count 1}]})
        responses    {(str endpoint "/api/dimensions")               {:status 200 :body catalog-body}
                      (str endpoint "/api/dimensions/channel/values") {:status 200 :body channel-body}}]
    (with-redefs [http/get (fake-get responses)]
      ;; Note: no /descriptions URL in the responses map → 404 from get-json
      ;; → fetch-value-descriptions! returns {} → channel description falls
      ;; back to the static :channel entry in dim-descriptions.
      (let [result (ts/fetch-dimensions! cl dim-descriptions {})]
        (is (= "Tunarr Scheduler channels" (get-in result [:channel :description]))
            "empty value-descriptions map → static :channel description unchanged")))))

(deftest fetch-dimensions-dimension-is-unaffected-by-other-dimensions-value-descriptions
  (let [audience-body (json/generate-string
                        {:values [{:value "kids" :usage-count 10}]})
        catalog-body  (json/generate-string
                        {:dimensions [{:name "audience" :value-count 1}]})
        responses     {(str endpoint "/api/dimensions")                {:status 200 :body catalog-body}
                       (str endpoint "/api/dimensions/audience/values") {:status 200 :body audience-body}}]
    (with-redefs [http/get (fake-get responses)]
      (let [result (ts/fetch-dimensions! cl dim-descriptions
                                          {:channel {"toontown" "Animated content"}})]
        (is (= "Audience segments" (get-in result [:audience :description]))
            "a dimension's value-descriptions only affect that dimension's own entry")))))

;; --- retry -----------------------------------------------------------------

(deftest retry-succeeds-on-second-attempt
  (let [attempts (atom 0)]
    (with-redefs [ts/fetch-dimensions! (fn [& _]
                                          (swap! attempts inc)
                                          (if (= 1 @attempts)
                                            (throw (java.net.ConnectException. "refused"))
                                            {:audience {:description "A" :values ["kids"]}}))]
      (let [result (ts/fetch-dimensions-with-retry!
                     (ts/create {:endpoint endpoint}) dim-descriptions 3)]
        (is (= 2 @attempts))
        (is (contains? result :audience))))))

;; --- create -----------------------------------------------------------------

(deftest create-strips-trailing-slashes
  (let [c (ts/create {:endpoint "http://tunarr-scheduler:5545///"})]
    (is (= "http://tunarr-scheduler:5545" (:endpoint c)))))

(deftest create-requires-endpoint
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":endpoint"
        (ts/create {}))))
