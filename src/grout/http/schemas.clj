(ns grout.http.schemas
  "Malli schemas for request/response coercion and OpenAPI generation.

   These schemas drive both runtime coercion and the generated OpenAPI
   `components/schemas`, so every entity map carries a :title/:description and
   every field a :description (Tunarr Scheduler convention).

   Response bodies use kebab-case keys to match the service-wide JSON
   convention (see grout.http.middleware). Query parameters use the snake_case
   names from the GROUT.md query example (min_ms, max_ms).")

(def Kind
  [:enum {:description "Media kind discriminator."}
   "bumper" "filler" "program"])

(def APIError
  [:map {:title "APIError" :description "Error envelope."}
   [:error [:string {:description "Human-readable error message."}]]])

(def Health
  [:map {:title "Health" :description "Health/readiness status."}
   [:status [:enum {:description "Overall service status."} "ok" "degraded"]]
   [:database [:enum {:description "Database connectivity."} "ok" "error"]]
   [:version {:optional true} [:maybe {:description "Running build version."} :string]]])

(def Version
  [:map {:title "Version" :description "Build and version information."}
   [:git-commit {:optional true} [:maybe {:description "Git commit SHA."} :string]]
   [:git-timestamp {:optional true} [:maybe {:description "Build timestamp."} :string]]
   [:version {:optional true} [:maybe {:description "Version tag."} :string]]])

;; --- Media -----------------------------------------------------------------

(def Media
  [:map {:title "Media"
         :description "Full media item. Returned by intake, fetch, patch, by-hash and enrich."}
   [:id [:uuid {:description "Stable item id."}]]
   [:kind Kind]
   [:path [:string {:description "Absolute path on the shared mount; stream this directly when co-mounted."}]]
   [:name {:optional true} [:maybe {:description "Human/AI title."} :string]]
   [:description {:optional true} [:maybe {:description "Short description."} :string]]
   [:channel {:optional true} [:maybe {:description "Owning channel, or null for generic filler usable on any channel."} :string]]
   [:tags [:vector {:description "Freeform tags used for retrieval."} :string]]
   [:duration-ms [:int {:description "Duration in milliseconds."}]]
   [:width {:optional true} [:maybe {:description "Video width in pixels."} :int]]
   [:height {:optional true} [:maybe {:description "Video height in pixels."} :int]]
   [:vcodec {:optional true} [:maybe {:description "Video codec (e.g. h264)."} :string]]
   [:acodec {:optional true} [:maybe {:description "Audio codec (e.g. aac)."} :string]]
   [:source {:optional true} [:maybe {:description "Provenance (e.g. tunarr-bumper, youtube, upload)."} :string]]
   [:source-url {:optional true} [:maybe {:description "Origin URL for orphan content."} :string]]
   [:enriched [:boolean {:description "Whether the AI metadata pass has completed."}]]
   [:content-hash {:optional true} [:maybe {:description "SHA-256 of the original source bytes (dedup key)."} :string]]
   [:stream-url [:string {:description "HTTP byte-range streaming fallback path."}]]
   [:created-at {:optional true} [:maybe {:description "Creation timestamp (ISO-8601)."} :string]]
   [:superseded-at {:optional true} [:maybe {:description "Soft-delete timestamp, or null if live."} :string]]])

(def MediaSummary
  [:map {:title "MediaSummary"
         :description "Compact media item returned by the query endpoint. Includes `path` for by-path streaming and `stream-url` for the HTTP fallback."}
   [:id [:uuid {:description "Stable item id."}]]
   [:name {:optional true} [:maybe {:description "Human/AI title."} :string]]
   [:duration-ms [:int {:description "Duration in milliseconds."}]]
   [:path [:string {:description "Absolute path on the shared mount."}]]
   [:stream-url [:string {:description "HTTP byte-range streaming fallback path."}]]
   [:vcodec {:optional true} [:maybe {:description "Video codec."} :string]]
   [:acodec {:optional true} [:maybe {:description "Audio codec."} :string]]
   [:tags [:vector {:description "Freeform tags."} :string]]])

(def MediaQueryResult
  [:map {:title "MediaQueryResult" :description "Result of a media query."}
   [:count [:int {:description "Number of items returned."}]]
   [:items [:vector {:description "Matching media items."} MediaSummary]]])

(def MediaQueryParams
  [:map {:title "MediaQueryParams" :description "Query-string parameters for GET /grout/media."}
   [:channel {:optional true} [:string {:description "Channel; matches this channel OR generic (null-channel) items."}]]
   [:tags {:optional true} [:string {:description "Comma-separated tags; AND semantics (all required)."}]]
   [:min_ms {:optional true} [:int {:description "Minimum duration in ms (inclusive)."}]]
   [:max_ms {:optional true} [:int {:description "Maximum duration in ms (inclusive)."}]]
   [:kind {:optional true} [:string {:description "Filter by kind (bumper|filler|program)."}]]
   [:limit {:optional true} [:int {:min 1 :description "Max items to return (default 10)."}]]
   [:random {:optional true} [:boolean {:description "When true, return a random sample."}]]])

;; POST /grout/media is a multipart/form-data upload (see grout.http.routes),
;; not a JSON body, so its fields aren't Malli-coerced like the others; the
;; expected fields are documented on the route's :description instead.

(def MediaPatch
  [:map {:title "MediaPatch"
         :description "Mutable metadata for PATCH /grout/media/:id. Provided fields overwrite; tags replaces the array."}
   [:name {:optional true} [:maybe {:description "New title."} :string]]
   [:description {:optional true} [:maybe {:description "New description."} :string]]
   [:channel {:optional true} [:maybe {:description "New channel (null to make generic)."} :string]]
   [:tags {:optional true} [:vector {:description "Replacement tag list."} :string]]])

(def DeleteResult
  [:map {:title "DeleteResult" :description "Outcome of a delete."}
   [:deleted [:boolean {:description "Whether the item was deleted."}]]
   [:hard [:boolean {:description "True for hard-delete + file unlink; false for soft-delete."}]]])

(def TagList
  [:map {:title "TagList" :description "Tags for a media item."}
   [:tags [:vector {:description "Current tags."} :string]]])

(def TagAddRequest
  [:map {:title "TagAddRequest" :description "Body for POST /grout/media/:id/tags."}
   [:tag [:string {:description "Tag to add (idempotent)."}]]])

;; --- Directory / tag-group enrichment --------------------------------------

(def EnrichByTagRequest
  [:map {:title "EnrichByTagRequest"
         :description "Body for POST /grout/enrich-by-tag/:tag."}
   [:concept_name [:string {:description "Human-readable group name fed to the LLM (e.g. 'Adam Neely Music')."}]]
   [:wait {:optional true} [:boolean {:description "When true, enrich inline and block (30s cap); default false (worker enriches asynchronously)."}]]
   [:force {:optional true} [:boolean {:description "When true, always re-enrich regardless of the growth threshold."}]]
   [:threshold_pct {:optional true} [:int {:min 0 :description "Re-enrich only when the item count has grown more than this percent since last enrichment (default 20)."}]]])

(def DirectoryProfile
  [:map {:title "DirectoryProfile"
         :description "State of a directory/tag-group profile."}
   [:status [:enum {:description "Profile status."} "pending" "ready" "failed"]]
   [:tag [:string {:description "The tag value this profile is keyed on."}]]
   [:concept-name {:optional true} [:maybe {:description "Human-readable group name."} :string]]
   [:dimensions {:optional true} [:maybe {:description "Derived dimension selections (dimension -> values); null until ready."}
                                  [:map-of :keyword [:vector :string]]]]
   [:tags {:optional true} [:maybe {:description "Derived free-form tags; null until ready."} [:vector :string]]]
   [:item_count [:int {:description "Current live item count carrying this tag."}]]
   [:item_count_at_enrichment [:int {:description "Item count recorded at the last enrichment."}]]
   [:cached {:optional true} [:boolean {:description "True when returned without a new enrichment."}]]
   [:timed_out {:optional true} [:boolean {:description "True when a wait=true request hit the inline timeout; the worker will finish it."}]]
   [:error {:optional true} [:maybe {:description "Last failure message when status=failed."} :string]]])

(def TagPath
  [:map [:tag [:string {:description "Tag value, e.g. 'parent-directory:adam-neely-music'."}]]])

;; --- Path / query parameter maps -------------------------------------------

(def IdPath
  [:map [:id [:uuid {:description "Media item id."}]]])

(def HashPath
  [:map [:hash [:string {:description "SHA-256 hex of the source bytes."}]]])

(def DeleteQuery
  [:map [:hard {:optional true} [:boolean {:description "When true, hard-delete and unlink the file."}]]])
