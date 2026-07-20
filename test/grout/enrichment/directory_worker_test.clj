(ns grout.enrichment.directory-worker-test
  "Tests for the directory-enrichment worker orchestration. The DB layer
  (grout.directory-profiles, grout.media.store) and the Tunabrain client are
  stubbed with `with-redefs`, so these exercise the control flow — success,
  empty result, no samples, Tunabrain failure, and the sweep — without a
  database or network. The pure helpers (profile->tags, profile-channel) run
  for real."
  (:require [clojure.test :refer [deftest is]]
            [grout.directory-profiles :as dp]
            [grout.enrichment.directory-worker :as dworker]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]))

(deftest enrich-one-success-applies-profile-and-marks-ready
  (let [applied (atom nil)
        ready   (atom nil)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "Adam Neely Music"
                                                           :dimensions nil :tags nil :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4" "b.mp4"])
                  tb/request-enrich-profile!    (fn [_ concept _samples & _]
                                                  (is (= "Adam Neely Music" concept))
                                                  {:dimensions {:channel ["muse"]}
                                                   :tags ["jazz"] :warnings []})
                  store/apply-directory-profile! (fn [_ tag new-tags stale channels]
                                                   (reset! applied {:tag tag :new-tags new-tags
                                                                    :stale stale :channels channels})
                                                   3)
                  store/count-by-tag            (fn [_ _] 3)
                  dp/mark-ready!                (fn [_ tag dims tags cnt]
                                                  (reset! ready {:tag tag :dims dims :tags tags :cnt cnt})
                                                  {:status "ready"})]
      (let [res (dworker/enrich-profile-one! :ds :tb {} 5 "parent-directory:x")]
        (is (= "ready" (:status res)))
        (is (= "parent-directory:x" (:tag @applied)))
        (is (= ["muse"] (:channels @applied)))
        (is (= #{"channel:muse" "jazz"} (set (:new-tags @applied))))
        (is (= [] (:stale @applied)) "no prior profile => nothing stale")
        (is (= {:channel ["muse"]} (:dims @ready)))
        (is (= 3 (:cnt @ready)))))))

(deftest enrich-one-threads-profile-context-to-tunabrain
  (let [sent-context (atom ::not-called)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C"
                                                           :dimensions nil :tags nil :status "pending"
                                                           :context {:text "these are retro game ads"}})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [_ _ _ & {:keys [context]}]
                                                  (reset! sent-context context)
                                                  {:dimensions {:channel ["toontown"]} :tags ["retro"] :warnings []})
                  store/apply-directory-profile! (fn [& _] 1)
                  store/count-by-tag            (fn [_ _] 1)
                  dp/mark-ready!                (fn [& _] {:status "ready"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (= {:text "these are retro game ads"} @sent-context)))))

(deftest enrich-one-applies-multiple-channel-values
  ;; A manually-broadened profile (or, in principle, a model that returns
  ;; more than one channel value) fans out its FULL channel list, not just
  ;; the first -- profile-channels, not the legacy profile-channel.
  (let [applied (atom nil)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C"
                                                           :dimensions nil :tags nil :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [& _] {:dimensions {:channel ["toontown" "infobytes" "galaxy"]}
                                                           :tags ["retro"] :warnings []})
                  store/apply-directory-profile! (fn [_ _ _ _ channels] (reset! applied channels) 1)
                  store/count-by-tag            (fn [_ _] 1)
                  dp/mark-ready!                (fn [& _] {:status "ready"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (= ["toontown" "infobytes" "galaxy"] @applied)))))

(deftest enrich-one-drops-hallucinated-channel-before-fanout
  ;; The model invents a channel outside the configured vocabulary. It must
  ;; not become a tag or set the row's channel column.
  (let [applied (atom nil)
        ready   (atom nil)
        dim-config {:channel {:description "c" :values ["muse"]}}]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C"
                                                           :dimensions nil :tags nil :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [& _] {:dimensions {:channel ["muse" "madeup"]}
                                                           :tags ["jazz"] :warnings []})
                  store/apply-directory-profile! (fn [_ tag new-tags stale channels]
                                                   (reset! applied {:tag tag :new-tags new-tags
                                                                    :stale stale :channels channels})
                                                   1)
                  store/count-by-tag            (fn [_ _] 1)
                  dp/mark-ready!                (fn [_ _ dims tags _]
                                                  (reset! ready {:dims dims :tags tags})
                                                  {:status "ready"})]
      (dworker/enrich-profile-one! :ds :tb dim-config 5 "t")
      (is (= ["muse"] (:channels @applied)) "channels set only from the valid value")
      (is (= #{"channel:muse" "jazz"} (set (:new-tags @applied)))
          "hallucinated 'channel:madeup' never becomes a tag")
      (is (= {:channel ["muse"]} (:dims @ready))
          "persisted profile stores only the valid dimension value"))))

(deftest enrich-one-computes-stale-tags-on-reenrich
  ;; Old profile had audience:kids + cartoon; new profile drops them. Those
  ;; must be marked stale so apply-directory-profile! removes them.
  (let [applied (atom nil)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C"
                                                           :dimensions {:audience ["kids"]}
                                                           :tags ["cartoon"] :status "ready"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [& _] {:dimensions {:channel ["muse"]}
                                                           :tags ["music"] :warnings []})
                  store/apply-directory-profile! (fn [_ _ new-tags stale _]
                                                   (reset! applied {:new-tags new-tags :stale stale})
                                                   1)
                  store/count-by-tag            (fn [_ _] 1)
                  dp/mark-ready!                (fn [& _] {:status "ready"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (= #{"channel:muse" "music"} (set (:new-tags @applied))))
      (is (= #{"audience:kids" "cartoon"} (set (:stale @applied)))))))

(deftest enrich-one-marks-failed-when-no-samples
  (let [failed (atom nil) applied? (atom false)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C" :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] [])
                  store/apply-directory-profile! (fn [& _] (reset! applied? true) 0)
                  dp/mark-failed!               (fn [_ tag msg] (reset! failed {:tag tag :msg msg})
                                                  {:status "failed"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (false? @applied?) "no fan-out when there are no filenames to ground on")
      (is (= "t" (:tag @failed)))
      (is (re-find #"no sample filenames" (:msg @failed))))))

(deftest enrich-one-marks-failed-when-model-returns-nothing
  (let [failed (atom nil) applied? (atom false)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C" :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [& _] {:dimensions {} :tags [] :warnings []})
                  store/apply-directory-profile! (fn [& _] (reset! applied? true) 0)
                  dp/mark-failed!               (fn [_ _ msg] (reset! failed msg) {:status "failed"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (false? @applied?))
      (is (re-find #"no dimensions or tags" @failed)))))

(deftest enrich-one-marks-failed-on-tunabrain-error
  (let [failed (atom nil)]
    (with-redefs [dp/get-profile-for-tag        (fn [_ _] {:concept_name "C" :status "pending"})
                  store/sample-filenames-by-tag (fn [_ _ _] ["a.mp4"])
                  tb/request-enrich-profile!    (fn [& _] (throw (ex-info "boom" {})))
                  dp/mark-failed!               (fn [_ _ msg] (reset! failed msg) {:status "failed"})]
      (dworker/enrich-profile-one! :ds :tb {} 5 "t")
      (is (= "boom" @failed)))))

(deftest enrich-one-nil-when-no-profile
  (with-redefs [dp/get-profile-for-tag (fn [_ _] nil)]
    (is (nil? (dworker/enrich-profile-one! :ds :tb {} 5 "missing")))))

(deftest run-once-processes-pending-and-retry-ready
  (let [processed (atom [])]
    (with-redefs [dp/pending-profiles               (fn [_ _] [{:tag_value "t1"}])
                  dp/failed-profiles-ready-for-retry (fn [_ _] [{:tag_value "t2"}])
                  dworker/enrich-profile-one!        (fn [_ _ _ _ tag] (swap! processed conj tag)
                                                       {:status "ready"})]
      (is (= 2 (dworker/run-once! :ds :tb {} 5 10)))
      (is (= ["t1" "t2"] @processed)))))
