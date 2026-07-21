(ns grout.http.directory-profiles
  "HTTP handlers for directory-level (tag-group) enrichment.

   `POST /grout/enrich-by-tag/:tag` ensures a profile exists for the tag and,
   subject to a growth threshold, (re-)enriches it — asynchronously by default
   (the periodic worker picks it up) or inline when `wait=true`. A `locked`
   profile (see `patch-handler`) is skipped for LLM re-enrichment unless
   `force=true`; a grown locked profile still gets its existing values
   fanned out to newly-tagged rows.

   `GET /grout/directory-profiles/:tag` reads a profile's current state.

   `PATCH /grout/directory-profiles/:tag` applies a manual operator
   correction — dimensions, tags, and/or grounding context — bypassing the
   LLM entirely.

   The endpoint is tag-agnostic: `:tag` is any tag value (in v1 always a
   `parent-directory:<x>` tag written by `grout-cli --upload-dir`)."
  (:require [grout.dimensions :as dim]
            [grout.directory-profiles :as dp]
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
           :context                   (:context profile)
           :locked                    (boolean (:locked profile))
           :item-count                item-count
           :item-count-at-enrichment  (or (:item_count_at_enrichment profile) 0)}
    (some? cached)      (assoc :cached cached)
    (some? timed-out)   (assoc :timed-out timed-out)
    (some? (:error profile)) (assoc :error (:error profile))))

(defn enrich-by-tag-handler
  "POST /grout/enrich-by-tag/:tag. Body: {:concept-name (required) :wait? :force?
   :threshold-pct?}. Ensures the profile exists, then:
     * when `locked` and not forced: never calls the LLM. Re-applies the
       existing (unchanged) dimensions/tags/channels to any newly-tagged rows
       once growth exceeds the threshold (bumping the growth baseline so this
       doesn't re-run on every request), otherwise just returns the cached
       state;
     * returns the cached `ready` profile when growth is under threshold and not
       forced;
     * otherwise flips it to `pending` (worker enriches on its next tick) and
       returns 202 — or, when `wait=true`, enriches inline (bounded by a 30s
       timeout) and returns the result. Either path clears `locked` once a
       fresh LLM result actually lands (see `grout.directory-profiles/mark-ready!`),
       so `force=true` doubles as the un-lock mechanism."
  [{:keys [ds tunabrain dim-config sample-count]}]
  (fn [{{{:keys [tag]} :path body :body} :parameters}]
    (let [{:keys [wait force threshold-pct concept-name]} body
          threshold (or threshold-pct default-threshold-pct)
          sc        (or sample-count 5)]
      (if (str/blank? concept-name)
        {:status 400 :body {:error "concept-name is required"}}
        (let [current (store/count-by-tag ds tag)]
          ;; Count before creating the profile row so a tag with no media never
          ;; leaves an orphan `pending` profile behind.
          (if (zero? current)
            {:status 404 :body {:error "No media items carry this tag. Upload files first."}}
            (do
              (dp/ensure-profile! ds tag concept-name)
              (let [profile (dp/get-profile-for-tag ds tag)]
                (cond
                  ;; Locked (manual override) and not forced: never call the
                  ;; LLM. If the group has grown past the threshold, re-apply
                  ;; the existing dimensions/tags/channels to any newly-tagged
                  ;; rows (COALESCE fan-out — new rows only, never clobbers an
                  ;; already-corrected one) and bump the growth baseline;
                  ;; otherwise just return the cached state.
                  (and (not force) (= "ready" (:status profile)) (:locked profile))
                  (let [profile' (if (dp/growth-exceeded? profile current threshold)
                                   (do (store/apply-directory-profile!
                                        ds tag
                                        (dp/profile->tags (:dimensions profile) (:tags profile))
                                        []
                                        (dp/profile-channels (:dimensions profile)))
                                       (dp/update-item-count! ds tag current))
                                   profile)]
                    {:status 200 :body (profile->body profile' current {:cached true})})

                  ;; Fresh enough — no LLM call.
                  (and (not force)
                       (= "ready" (:status profile))
                       (not (dp/growth-exceeded? profile current threshold)))
                  {:status 200 :body (profile->body profile current {:cached true})}

                  ;; Enrich now, blocking, with a hard timeout so the CLI never
                  ;; hangs on a slow LLM; the inline future finishes regardless.
                  wait
                  (let [fut     (future (dworker/enrich-profile-one! ds tunabrain dim-config sc tag))
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

(defn- context-patch->stored
  "Normalize a PATCH body's `:context` (a DirectoryProfileManualContext map,
   or nil to clear) before persisting. An empty map (`{}` — no `:text`, no
   `:links`) is treated the same as nil: an operator PATCHing `{:context {}}`
   plainly means \"no context\", not \"context set to nothing in particular\"."
  [context]
  (when (and context
             (or (not (str/blank? (str (:text context))))
                 (seq (:links context))))
    context))

(defn- invalid-dimensions-error
  "400 body describing which submitted dimension values aren't in the Tunarr
   Scheduler vocabulary, per `grout.dimensions/filter-dimension-map`'s
   `:rejected` list. `:rejected` is included structured (not just in the
   message) so a UI can highlight the specific offending value(s)."
  [rejected]
  {:error (str "Unknown dimension value(s), not in the Tunarr Scheduler "
              "vocabulary: "
              (str/join ", " (map (fn [{:keys [dimension value]}]
                                    (str (name dimension) ":" value))
                                  rejected)))
   :rejected rejected})

(defn patch-handler
  "PATCH /grout/directory-profiles/:tag. Body: any of `:dimensions`, `:tags`,
   `:context`, `:locked` (see DirectoryProfilePatch; at least one required).

   `:dimensions` (when present) is validated against the same Tunarr
   Scheduler-derived vocabulary the LLM path is checked against (see
   `grout.dimensions/filter-dimension-map`) — a manual edit is exactly where a
   fat-fingered channel/audience slug (`tootnown` for `toontown`) would
   otherwise silently create a value nothing schedules against, with no
   error to notice it by. Unlike the LLM path (which drops invalid values and
   proceeds), a manual edit REJECTS the whole request (400) on any invalid
   value — the operator should see and fix the typo, not have it silently
   dropped from what they thought they saved. A dimension with no configured
   vocabulary (e.g. Tunarr Scheduler was unreachable at startup) passes
   through unvalidated, same as the LLM path.

   Otherwise, applies the operator's manual edit via `dp/set-manual!` and,
   when `:dimensions` and/or `:tags` were provided, immediately fans the new
   values out to every child media row via
   `store/force-set-channels-by-tag!` — an UNCONDITIONAL overwrite (unlike
   the LLM-driven fan-out's COALESCE/never-clobber semantics), because the
   whole point of a manual edit is correcting an already-wrong assignment,
   not just filling blanks. This takes effect immediately rather than
   waiting for the next growth-triggered sweep."
  [{:keys [ds dim-config]}]
  (fn [{{{:keys [tag]} :path body :body} :parameters}]
    (let [patch (cond-> (select-keys body [:dimensions :tags :context :locked])
                  (contains? body :context) (update :context context-patch->stored))
          rejected (when (contains? patch :dimensions)
                     (:rejected (dim/filter-dimension-map dim-config (:dimensions patch))))]
      (cond
        (empty? patch)
        {:status 400 :body {:error "No mutable fields provided"}}

        (seq rejected)
        {:status 400 :body (invalid-dimensions-error rejected)}

        :else
        (if-let [old-profile (dp/get-profile-for-tag ds tag)]
          (let [updated (dp/set-manual! ds tag patch)]
            (when (or (contains? patch :dimensions) (contains? patch :tags))
              (let [old-tags   (dp/profile->tags (:dimensions old-profile) (:tags old-profile))
                    new-tags   (dp/profile->tags (:dimensions updated) (:tags updated))
                    stale-tags (vec (remove (set new-tags) old-tags))
                    channels   (dp/profile-channels (:dimensions updated))]
                (store/force-set-channels-by-tag! ds tag new-tags stale-tags channels)))
            {:status 200 :body (profile->body updated (store/count-by-tag ds tag) {:cached false})})
          {:status 404 :body {:error "No profile exists for this tag"}})))))

(defn get-profile-handler
  "GET /grout/directory-profiles/:tag — read a profile's current state."
  [{:keys [ds]}]
  (fn [{{{:keys [tag]} :path} :parameters}]
    (if-let [profile (dp/get-profile-for-tag ds tag)]
      {:status 200 :body (profile->body profile (store/count-by-tag ds tag)
                                        {:cached (= "ready" (:status profile))})}
      {:status 404 :body {:error "No profile exists for this tag"}})))

(defn list-profiles-handler
  "GET /grout/directory-profiles — the collection catalog: every profile with
   its live item count and enrichment status, ordered by concept name. Backs a
   'Collections' browse view (one entry per parent-directory tag group)."
  [{:keys [ds]}]
  (fn [_]
    {:status 200
     :body {:profiles (mapv (fn [p]
                              (profile->body p (store/count-by-tag ds (:tag_value p))
                                             {:cached (= "ready" (:status p))}))
                            (dp/list-profiles ds))}}))
