(ns grout.http.directory-profiles
  "HTTP handlers for directory-level (tag-group) enrichment.

   `POST /grout/enrich-by-tag/:tag` ensures a profile exists for the tag and,
   subject to a growth threshold, (re-)enriches it — asynchronously by default
   (the periodic worker picks it up) or inline when `wait=true`.

   `GET /grout/directory-profiles/:tag` reads a profile's current state.

   The endpoint is tag-agnostic: `:tag` is any tag value (in v1 always a
   `parent-directory:<x>` tag written by `grout-cli --upload-dir`)."
  (:require [grout.directory-profiles :as dp]
            [grout.enrichment.directory-worker :as dworker]
            [grout.media.store :as store]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private default-threshold-pct 20)
(def ^:private wait-timeout-ms 30000)

(defn- profile->body
  "Shape a profile row + live item count into the response body. `dimensions`
   and `tags` are always present (nil until the profile is `ready`)."
  [profile item-count {:keys [cached timed-out]}]
  (cond-> {:status                    (:status profile)
           :tag                       (:tag_value profile)
           :concept-name              (:concept_name profile)
           :dimensions                (:dimensions profile)
           :tags                      (:tags profile)
           :item_count                item-count
           :item_count_at_enrichment  (or (:item_count_at_enrichment profile) 0)}
    (some? cached)      (assoc :cached cached)
    (some? timed-out)   (assoc :timed_out timed-out)
    (some? (:error profile)) (assoc :error (:error profile))))

(defn enrich-by-tag-handler
  "POST /grout/enrich-by-tag/:tag. Body: {:concept_name (required) :wait? :force?
   :threshold_pct?}. Ensures the profile exists, then:
     * returns the cached `ready` profile when growth is under threshold and not
       forced;
     * otherwise flips it to `pending` (worker enriches on its next tick) and
       returns 202 — or, when `wait=true`, enriches inline (bounded by a 30s
       timeout) and returns the result."
  [{:keys [ds tunabrain sample-count]}]
  (fn [{{{:keys [tag]} :path body :body} :parameters}]
    (let [{:keys [wait force threshold_pct concept_name]} body
          threshold (or threshold_pct default-threshold-pct)
          sc        (or sample-count 5)]
      (if (str/blank? concept_name)
        {:status 400 :body {:error "concept_name is required"}}
        (let [current (store/count-by-tag ds tag)]
          ;; Count before creating the profile row so a tag with no media never
          ;; leaves an orphan `pending` profile behind.
          (if (zero? current)
            {:status 404 :body {:error "No media items carry this tag. Upload files first."}}
            (do
              (dp/ensure-profile! ds tag concept_name)
              (let [profile (dp/get-profile-for-tag ds tag)]
                (cond
                  ;; Fresh enough — no LLM call.
                  (and (not force)
                       (= "ready" (:status profile))
                       (not (dp/growth-exceeded? profile current threshold)))
                  {:status 200 :body (profile->body profile current {:cached true})}

                  ;; Enrich now, blocking, with a hard timeout so the CLI never
                  ;; hangs on a slow LLM; the inline future finishes regardless.
                  wait
                  (let [fut     (future (dworker/enrich-profile-one! ds tunabrain sc tag))
                        result  (deref fut wait-timeout-ms ::timeout)]
                    (if (= result ::timeout)
                      {:status 202 :body (profile->body (dp/get-profile-for-tag ds tag)
                                                        current {:cached false :timed-out true})}
                      {:status 200 :body (profile->body result (store/count-by-tag ds tag)
                                                        {:cached false})}))

                  ;; Fire-and-forget: hand off to the periodic worker.
                  :else
                  (let [pending (dp/mark-pending! ds tag)]
                    (log/info "Directory profile queued for enrichment" {:tag tag :items current})
                    {:status 202 :body (profile->body pending current {:cached false})}))))))))))

(defn get-profile-handler
  "GET /grout/directory-profiles/:tag — read a profile's current state."
  [{:keys [ds]}]
  (fn [{{{:keys [tag]} :path} :parameters}]
    (if-let [profile (dp/get-profile-for-tag ds tag)]
      {:status 200 :body (profile->body profile (store/count-by-tag ds tag)
                                        {:cached (= "ready" (:status profile))})}
      {:status 404 :body {:error "No profile exists for this tag"}})))
