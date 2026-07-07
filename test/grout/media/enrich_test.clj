(ns grout.media.enrich-test
  "Tests for the enrichment orchestrator. The orchestrator:
    1. Reads the row
    2. Calls Tunabrain /enrich/short-form (with replayed context)
       — this is a single call that internally orchestrates describe +
       categorize + tags in Tunabrain
    3. Merges results and persists with enriched=true

  The `set-enriched!` store fn is mocked; the real DB is not touched.
  The `request-enrich-short-form!` tunabrain fn is mocked; the real
  HTTP client is not touched."
  (:require [clojure.test :refer [deftest is]]
            [grout.media.enrich :as enrich]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]))

;; --- merge-enrichment -------------------------------------------------------
;;
;; merge-enrichment now takes a single Tunabrain response (the result
;; of /enrich/short-form) and the existing row. The prior two-call
;; shape (cat-resp + tag-resp) is removed.

(deftest merge-enrichment-unions-tags-with-dimension-prefix
  (let [row    {:id (java.util.UUID/randomUUID)
                :name "Keep"
                :tags ["existing" "filename:foo.mp4"]}
        resp   {:dimensions [{:dimension "audience" :values ["kids" "family"]}
                             {:dimension "channel"  :values ["goldenreels"]}]
                :tags ["new-ai-tag" "kids"]
                :context {:summary "s2" :source "wikipedia" :links ["..."]}
                :describe nil
                :media nil
                :cost_estimate nil
                :warnings []}
        m      (enrich/merge-enrichment row resp)]
    (is (= ["existing" "filename:foo.mp4"
            "audience:kids" "audience:family" "channel:goldenreels"
            "new-ai-tag" "kids"]
           (:tags m))
        "tags union: existing + dimension-as-tag prefix + ai tags, all preserved in order, deduped")
    (is (= "wikipedia" (:enrichment-grounding-source m))
        "uses the response context source for grounding source")
    (is (= {:summary "s2" :source "wikipedia" :links ["..."]} (:enrichment-context m))
        "uses the response context verbatim for replay")))

(deftest merge-enrichment-drops-blank-tags
  (let [m (enrich/merge-enrichment
            {:tags ["  " "" "good"]}
            {:dimensions [] :tags ["" "  " "ai"] :context nil})]
    (is (= ["good" "ai"] (:tags m)))))

(deftest merge-enrichment-defaults-grounding-source-to-none
  (let [m (enrich/merge-enrichment
            {:tags []}
            {:dimensions [] :tags ["ai"] :context nil})]
    (is (= "none" (:enrichment-grounding-source m)))))

;; --- title/description merge behavior --------------------------------------
;;
;; The Tunabrain /enrich/short-form response carries the AI
;; refined title and description in a separate describe field
;; (not on media, which is the request echo). Grout's merge step
;; writes these into the row's name and description columns
;; only when the row's existing values are empty. The
;; never-clobber-a-human-set-value rule applies to both fields.

(deftest merge-enrichment-fills-name-from-describe-when-row-name-is-empty
  (let [row  {:name nil :description nil :tags []}
        resp {:describe {:id "x" :title "Mystery Bumper" :description "A short intro."}
              :dimensions [] :tags [] :context nil}]
    (is (= "Mystery Bumper" (:name (enrich/merge-enrichment row resp)))
        "AI fills :name when the row had no name")))

(deftest merge-enrichment-fills-description-from-describe-when-row-description-is-empty
  (let [row  {:name nil :description nil :tags []}
        resp {:describe {:id "x" :title "x" :description "A short intro."}
              :dimensions [] :tags [] :context nil}]
    (is (= "A short intro." (:description (enrich/merge-enrichment row resp)))
        "AI fills :description when the row had no description")))

(deftest merge-enrichment-preserves-existing-name-when-describe-suggests-new-one
  (let [row  {:name "Human Title" :description nil :tags []}
        resp {:describe {:id "x" :title "AI Suggests Something Else" :description nil}
              :dimensions [] :tags [] :context nil}
        m (enrich/merge-enrichment row resp)]
    (is (not (contains? m :name))
        "merge payload omits :name entirely when the row already has one — set-enriched! will not clobber the human-set value")))

(deftest merge-enrichment-preserves-existing-description-when-describe-suggests-new-one
  (let [row  {:name nil :description "Human-curated description." :tags []}
        resp {:describe {:id "x" :title "x" :description "The AI attempt at a description."}
              :dimensions [] :tags [] :context nil}
        m (enrich/merge-enrichment row resp)]
    (is (not (contains? m :description))
        "merge payload omits :description entirely when the row already has one — set-enriched! will not clobber the human-set value")))

(deftest merge-enrichment-leaves-name-untouched-when-describe-is-null
  (let [row  {:name "Keep" :description "Keep" :tags []}
        resp {:describe nil
              :dimensions [] :tags [] :context nil}]
    (let [m (enrich/merge-enrichment row resp)]
      (is (nil? (:name m))
          "no name key in the merge payload when describe failed (caller preserves existing)")
      (is (nil? (:description m))
          "no description key in the merge payload when describe failed (caller preserves existing)"))))

(deftest merge-enrichment-omits-empty-describe-title-and-description
  (let [row  {:name nil :description nil :tags []}
        resp {:describe {:id "x" :title "" :description "   "}
              :dimensions [] :tags [] :context nil}
        m (enrich/merge-enrichment row resp)]
    (is (nil? (:name m)) "blank describe-title is treated as missing")
    (is (nil? (:description m)) "blank describe-description is treated as missing")))

;; --- enrich-one! ------------------------------------------------------------

(deftest enrich-one-calls-enrich-short-form-and-persists
  (let [row     (atom {:id (java.util.UUID/randomUUID)
                       :name nil
                       :description nil
                       :tags ["existing" "filename:foo.mp4"]
                       :enrichment_context nil})
        saved   (atom nil)
        enrich-calls (atom 0)]
    (with-redefs [store/find-by-id   (fn [_ _ & _] @row)
                  store/set-enriched! (fn [_ _ data] (reset! saved data)
                                        (swap! row assoc :enriched true) @row)
                  tb/request-enrich-short-form!
                  (fn [_ r dim existing & _]
                    (swap! enrich-calls inc)
                    (is (= ["existing" "filename:foo.mp4"] existing))
                    (is (= (:id @row) (:id r)))
                    {:dimensions [{:dimension "audience" :values ["kids"]}]
                     :tags ["ai-tag"]
                     :context {:summary "s" :source "provided-text" :links []}
                     :describe {:id "x" :title "Refined Title" :description "A short clip."}
                     :media {:id "x" :title "Refined Title"}
                     :cost_estimate {:estimated_cost_usd 0.001
                                     :llm_calls_used 1
                                     :estimated_tokens "~1"
                                     :model "gpt-4o-mini"}
                     :warnings []})]
      (let [result (enrich/enrich-one! nil nil
                                      {:audience {:description "A" :values ["kids"]}}
                                      (:id @row))]
        (is (some? result))
        (is (= 1 @enrich-calls))
        (is (= ["existing" "filename:foo.mp4" "audience:kids" "ai-tag"] (:tags @saved)))
        (is (= "Refined Title" (:name @saved))
            "the AI-refined name is written to the row when the row had no name")
        (is (= "A short clip." (:description @saved))
            "the AI-refined description is written to the row when the row had no description")
        (is (= "provided-text" (:enrichment-grounding-source @saved)))))))

(deftest enrich-one-replays-stored-context
  (let [stored-ctx {:summary "corrected by human" :source "provided-summary" :links []}
        captured   (atom nil)
        row        {:id (java.util.UUID/randomUUID)
                    :name "x"
                    :description nil
                    :tags []
                    :enrichment_context stored-ctx}]
    (with-redefs [store/find-by-id   (fn [_ _ & _] row)
                  store/set-enriched! (fn [_ _ _] row)
                  tb/request-enrich-short-form!
                  (fn [_ _ _ _ & {:keys [context]}]
                    (reset! captured context)
                    {:dimensions [] :tags []
                     :context {:source "provided-summary" :summary "s" :links []}
                     :describe nil
                     :media nil
                     :cost_estimate nil
                     :warnings []})]
      (enrich/enrich-one! nil nil {:audience {:description "A" :values ["kids"]}}
                          (:id row))
      (is (= stored-ctx @captured) "stored context is replayed to /enrich/short-form"))))

(deftest enrich-one-preserves-human-name-when-present
  (let [row     (atom {:id (java.util.UUID/randomUUID)
                       :name "Human Title"
                       :description nil
                       :tags []
                       :enrichment_context nil})
        saved   (atom nil)]
    (with-redefs [store/find-by-id   (fn [_ _ & _] @row)
                  store/set-enriched! (fn [_ _ data] (reset! saved data)
                                        (swap! row assoc :enriched true) @row)
                  tb/request-enrich-short-form!
                  (fn [_ _ _ _ & _]
                    {:dimensions []
                     :tags ["ai-tag"]
                     :context {:summary "s" :source "wikipedia" :links []}
                     :describe {:id "x" :title "AI Wants to Rename" :description nil}
                     :media nil
                     :cost_estimate nil
                     :warnings []})]
      (enrich/enrich-one! nil nil
                          {:audience {:description "A" :values ["kids"]}}
                          (:id @row))
      (is (nil? (:name @saved))
          "when the row had a human-set name, the merge payload omits :name entirely so set-enriched! does not clobber it"))))

(deftest enrich-one-returns-nil-when-no-ai-contribution
  (let [row {:id (java.util.UUID/randomUUID)
             :name "x" :description nil :tags []}]
    (with-redefs [store/find-by-id   (fn [_ _ & _] row)
                  tb/request-enrich-short-form!
                  (fn [_ _ _ _ & _]
                    {:dimensions []
                     :tags []
                     :context nil
                     :describe nil
                     :media nil
                     :cost_estimate nil
                     :warnings []})]
      (is (nil? (enrich/enrich-one! nil nil
                                    {:audience {:description "A" :values ["kids"]}}
                                    (:id row)))
          "no tags, no dimensions, no describe → row stays enriched=false so a later sweep can retry"))))

(deftest enrich-one-returns-nil-when-tunabrain-throws
  (let [row {:id (java.util.UUID/randomUUID)
             :name "x" :description nil :tags []}]
    (with-redefs [store/find-by-id (fn [_ _ & _] row)
                  tb/request-enrich-short-form!
                  (fn [& _]
                    (throw (ex-info "boom" {})))]
      (is (nil? (enrich/enrich-one! nil nil
                                    {:audience {:description "A" :values ["kids"]}}
                                    (:id row)))
          "tunabrain errors are caught; row stays enriched=false"))))

(deftest enrich-one-returns-nil-when-row-missing
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (is (nil? (enrich/enrich-one! nil nil
                                  {:audience {:description "A" :values ["kids"]}}
                                  (java.util.UUID/randomUUID))))))
