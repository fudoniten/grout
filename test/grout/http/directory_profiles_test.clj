(ns grout.http.directory-profiles-test
  "Tests for the directory-profile HTTP handlers. Handlers are invoked directly
  with a ring-style `:parameters` map; the DB layer, the growth check's inputs,
  and the inline-enrichment call are stubbed with `with-redefs`. Responses are
  plain Clojure maps here (JSON encoding is applied by middleware downstream)."
  (:require [clojure.test :refer [deftest is]]
            [grout.directory-profiles :as dp]
            [grout.enrichment.directory-worker :as dworker]
            [grout.http.directory-profiles :as dirprof]
            [grout.media.store :as store]))

(def ^:private deps {:ds :fake-ds :tunabrain :fake-client :sample-count 5})

(defn- post [body]
  ((dirprof/enrich-by-tag-handler deps)
   {:parameters {:path {:tag "parent-directory:x"} :body body}}))

(deftest missing-concept-name-is-400
  (is (= 400 (:status (post {:concept-name ""}))))
  (is (= 400 (:status (post {})))))

(deftest no-media-for-tag-is-404
  (with-redefs [dp/ensure-profile! (fn [& _] {:status "pending"})
                store/count-by-tag (fn [_ _] 0)]
    (is (= 404 (:status (post {:concept-name "C"}))))))

(deftest ready-and-not-grown-returns-cached
  (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                store/count-by-tag     (fn [_ _] 10)
                dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                  :item_count_at_enrichment 10
                                                  :dimensions {:channel ["muse"]} :tags ["jazz"]})]
    (let [resp (post {:concept-name "C"})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :cached])))
      (is (= {:channel ["muse"]} (get-in resp [:body :dimensions]))))))

(deftest growth-past-threshold-queues-pending
  (let [marked (atom false) enriched (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 20)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10})
                  dp/mark-pending!       (fn [_ _] (reset! marked true)
                                           {:status "pending" :tag_value "parent-directory:x"
                                            :item_count_at_enrichment 10})
                  dworker/enrich-profile-one! (fn [& _] (reset! enriched true) nil)]
      (let [resp (post {:concept-name "C"})]
        (is (= 202 (:status resp)))
        (is (= "pending" (get-in resp [:body :status])))
        (is (true? @marked) "the profile is flipped to pending for the worker")
        (is (false? @enriched) "async path does not enrich inline")))))

(deftest force-reenriches-even-when-not-grown
  (let [marked (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 10)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10})
                  dp/mark-pending!       (fn [_ _] (reset! marked true)
                                           {:status "pending" :tag_value "parent-directory:x"
                                            :item_count_at_enrichment 10})]
      (let [resp (post {:concept-name "C" :force true})]
        (is (= 202 (:status resp)))
        (is (true? @marked))))))

(deftest wait-enriches-inline
  (let [enriched (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 20)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10})
                  dworker/enrich-profile-one! (fn [_ _ _ _]
                                                (reset! enriched true)
                                                {:status "ready" :tag_value "parent-directory:x"
                                                 :dimensions {:channel ["muse"]} :tags ["x"]
                                                 :item_count_at_enrichment 20})]
      (let [resp (post {:concept-name "C" :wait true})]
        (is (= 200 (:status resp)))
        (is (true? @enriched) "wait=true enriches inline")
        (is (= "ready" (get-in resp [:body :status])))
        (is (false? (get-in resp [:body :cached])))))))

(deftest get-profile-returns-200-when-present
  (with-redefs [dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                  :item_count_at_enrichment 5 :dimensions {} :tags []})
                store/count-by-tag     (fn [_ _] 5)]
    (let [resp ((dirprof/get-profile-handler deps)
                {:parameters {:path {:tag "parent-directory:x"}}})]
      (is (= 200 (:status resp)))
      (is (= "ready" (get-in resp [:body :status]))))))

(deftest get-profile-returns-404-when-absent
  (with-redefs [dp/get-profile-for-tag (fn [_ _] nil)]
    (is (= 404 (:status ((dirprof/get-profile-handler deps)
                         {:parameters {:path {:tag "nope"}}}))))))
