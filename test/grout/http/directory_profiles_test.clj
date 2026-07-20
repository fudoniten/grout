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
                  dworker/enrich-profile-one! (fn [_ _ _ _ _]
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

(deftest list-profiles-returns-catalog-with-live-counts
  (with-redefs [dp/list-profiles (fn [_]
                                   [{:status "ready" :tag_value "parent-directory:a"
                                     :concept_name "Aaa" :item_count_at_enrichment 3
                                     :dimensions {:channel ["muse"]} :tags ["x"]}
                                    {:status "pending" :tag_value "parent-directory:b"
                                     :concept_name "Bbb" :item_count_at_enrichment 0
                                     :dimensions nil :tags nil}])
                store/count-by-tag (fn [_ tag]
                                     (case tag
                                       "parent-directory:a" 5
                                       "parent-directory:b" 2))]
    (let [resp ((dirprof/list-profiles-handler deps) {})
          profiles (get-in resp [:body :profiles])]
      (is (= 200 (:status resp)))
      (is (= 2 (count profiles)))
      (is (= ["parent-directory:a" "parent-directory:b"] (map :tag profiles)))
      (is (= [5 2] (map :item-count profiles))
          "item-count is the live count, not item_count_at_enrichment")
      (is (= "Aaa" (:concept-name (first profiles)))))))

(deftest list-profiles-empty-catalog
  (with-redefs [dp/list-profiles (fn [_] [])]
    (let [resp ((dirprof/list-profiles-handler deps) {})]
      (is (= 200 (:status resp)))
      (is (= [] (get-in resp [:body :profiles]))))))

;; Regression: a profile row read from the DB has dimensions with string keys
;; (Cheshire parses jsonb objects into Clojure maps with string keys), but the
;; OpenAPI response schema declares [:map-of :keyword [:vector :string]]. If
;; the keys are not normalized at the boundary, the response-coercion layer
;; rejects the body with a 500 and a misleading "tags invalid type" log line
;; (Malli reports the first failing key; the real failure is dimensions).
;; The fix lives in `dp/->profile`; this test calls it through the stubbed
;; `get-profile-for-tag` to exercise the real conversion path.
;; See: live cluster bug, parent-directory:2019 row on 2026-07-12.
(deftest get-profile-coerces-dimension-keys-to-keywords
  (with-redefs [dp/get-profile-for-tag (fn [_ tag]
                                         (dp/->profile
                                          {:status "ready"
                                           :tag_value tag
                                           :item_count_at_enrichment 4
                                           :dimensions {"channel" ["IQ2 Debates"]
                                                        "audience" ["adult"]}
                                           :tags ["debates" "iq2"]}))
                store/count-by-tag     (fn [_ _] 4)]
    (let [resp ((dirprof/get-profile-handler deps)
                {:parameters {:path {:tag "parent-directory:x"}}})]
      (is (= 200 (:status resp)))
      (is (= ["IQ2 Debates"] (get-in resp [:body :dimensions :channel]))
          "dimensions keys must be keywords, not strings")
      (is (= ["adult"] (get-in resp [:body :dimensions :audience])))
      (is (not (contains? (:dimensions (:body resp)) "channel"))
          "no string keys should leak into the response body"))))

;; ---------------------------------------------------------------------------
;; Locked profiles: enrich-by-tag-handler must never call the LLM for one
;; unless force=true, but still needs to fan its existing values out to any
;; newly-tagged media once the group has grown.
;; ---------------------------------------------------------------------------

(deftest locked-and-not-grown-returns-cached-without-fanout
  (let [applied? (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 10)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10 :locked true
                                                    :dimensions {:channel ["toontown"]} :tags ["retro"]})
                  store/apply-directory-profile! (fn [& _] (reset! applied? true) 0)]
      (let [resp (post {:concept-name "C"})]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body :cached])))
        (is (true? (get-in resp [:body :locked])))
        (is (false? @applied?) "under threshold: no need to re-apply yet")))))

(deftest locked-and-grown-refans-out-without-calling-llm
  (let [applied (atom nil) touched (atom nil) llm-called? (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 20)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10 :locked true
                                                    :dimensions {:channel ["toontown" "infobytes"]}
                                                    :tags ["retro"]})
                  store/apply-directory-profile! (fn [_ tag tags stale channels]
                                                   (reset! applied {:tag tag :tags tags :stale stale
                                                                    :channels channels})
                                                   5)
                  dp/update-item-count!  (fn [_ tag cnt] (reset! touched {:tag tag :cnt cnt})
                                          {:status "ready" :tag_value tag :locked true
                                           :item_count_at_enrichment cnt})
                  dworker/enrich-profile-one! (fn [& _] (reset! llm-called? true) nil)]
      (let [resp (post {:concept-name "C"})]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body :cached])))
        (is (= "parent-directory:x" (:tag @applied)))
        (is (= ["toontown" "infobytes"] (:channels @applied)))
        (is (= {:tag "parent-directory:x" :cnt 20} @touched)
            "growth baseline is bumped so this doesn't re-run every request")
        (is (false? @llm-called?) "locked profile never calls the LLM without force")))))

(deftest force-true-bypasses-lock-and-calls-llm
  (let [marked (atom false)]
    (with-redefs [dp/ensure-profile!     (fn [& _] nil)
                  store/count-by-tag     (fn [_ _] 10)
                  dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :item_count_at_enrichment 10 :locked true})
                  dp/mark-pending!       (fn [_ _] (reset! marked true)
                                           {:status "pending" :tag_value "parent-directory:x"
                                            :item_count_at_enrichment 10})]
      (let [resp (post {:concept-name "C" :force true})]
        (is (= 202 (:status resp)))
        (is (true? @marked) "force=true re-enriches even though the profile is locked")))))

;; ---------------------------------------------------------------------------
;; patch-handler: manual dimension/tag/context overrides
;; ---------------------------------------------------------------------------

(defn- patch [body]
  ((dirprof/patch-handler deps)
   {:parameters {:path {:tag "parent-directory:x"} :body body}}))

(deftest patch-empty-body-is-400
  (is (= 400 (:status (patch {})))))

(deftest patch-404-when-no-profile
  (with-redefs [dp/get-profile-for-tag (fn [_ _] nil)]
    (is (= 404 (:status (patch {:dimensions {:channel ["toontown"]}}))))))

(deftest patch-dimensions-locks-and-force-fans-out
  ;; Old profile was wrongly on goldenreels; the operator reassigns it to
  ;; three channels at once. The fan-out must (a) force-overwrite every
  ;; child row's channel(s), not just fill blanks, (b) apply the FULL new
  ;; channel list, and (c) mark the old channel:goldenreels tag stale so it
  ;; gets removed from already-tagged rows, while the surviving free-form
  ;; tag ("retro") is not treated as stale.
  (let [forced (atom nil)]
    (with-redefs [dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :dimensions {:channel ["goldenreels"]} :tags ["retro"]})
                  dp/set-manual!         (fn [_ tag patch]
                                          (is (= {:channel ["toontown" "infobytes" "galaxy"]} (:dimensions patch)))
                                          {:status "ready" :tag_value tag :locked true
                                           :dimensions {:channel ["toontown" "infobytes" "galaxy"]}
                                           :tags ["retro"]})
                  store/force-set-channels-by-tag! (fn [_ tag tags stale channels]
                                                     (reset! forced {:tag tag :tags tags :stale stale
                                                                     :channels channels})
                                                     7)
                  store/count-by-tag     (fn [_ _] 7)]
      (let [resp (patch {:dimensions {:channel ["toontown" "infobytes" "galaxy"]}})]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body :locked])))
        (is (= "parent-directory:x" (:tag @forced)))
        (is (= ["toontown" "infobytes" "galaxy"] (:channels @forced))
            "the FULL channel list is force-fanned-out, not just the first value")
        (is (= #{"channel:toontown" "channel:infobytes" "channel:galaxy" "retro"}
               (set (:tags @forced))))
        (is (= ["channel:goldenreels"] (:stale @forced))
            "the old channel:goldenreels tag is stale; retro survives in both old and new")))))

(deftest patch-context-only-does-not-lock-or-fanout
  (let [forced? (atom false)]
    (with-redefs [dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                    :dimensions {:channel ["toontown"]} :tags ["retro"]
                                                    :locked false})
                  dp/set-manual!         (fn [_ tag patch]
                                          (is (= {:text "these are retro game ads"}
                                                 (:context patch)))
                                          {:status "ready" :tag_value tag :locked false
                                           :dimensions {:channel ["toontown"]} :tags ["retro"]
                                           :context {:text "these are retro game ads"}})
                  store/force-set-channels-by-tag! (fn [& _] (reset! forced? true) 0)
                  store/count-by-tag     (fn [_ _] 3)]
      (let [resp (patch {:context {:text "these are retro game ads"}})]
        (is (= 200 (:status resp)))
        (is (false? (get-in resp [:body :locked])))
        (is (= {:text "these are retro game ads"} (get-in resp [:body :context])))
        (is (false? @forced?) "context-only edits don't touch child media")))))

(deftest patch-empty-context-object-clears-to-nil
  (with-redefs [dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"})
                dp/set-manual!         (fn [_ tag patch]
                                        (is (nil? (:context patch)))
                                        {:status "ready" :tag_value tag})
                store/count-by-tag     (fn [_ _] 0)]
    (let [resp (patch {:context {}})]
      (is (= 200 (:status resp))))))

(deftest patch-explicit-locked-false-unlocks-without-touching-values
  (with-redefs [dp/get-profile-for-tag (fn [_ _] {:status "ready" :tag_value "parent-directory:x"
                                                  :dimensions {:channel ["toontown"]} :tags ["retro"]
                                                  :locked true})
                dp/set-manual!         (fn [_ tag patch]
                                        (is (= {:locked false} patch))
                                        {:status "ready" :tag_value tag :locked false
                                         :dimensions {:channel ["toontown"]} :tags ["retro"]})
                store/count-by-tag     (fn [_ _] 3)]
    (let [resp (patch {:locked false})]
      (is (= 200 (:status resp)))
      (is (false? (get-in resp [:body :locked]))))))
