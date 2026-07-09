(ns grout.http.routes
  "HTTP routes with OpenAPI documentation and Malli validation."
  (:require [reitit.ring :as ring]
            [reitit.openapi :as openapi]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [grout.db :as db]
            [grout.http.directory-profiles :as dirprof]
            [grout.http.media :as media]
            [grout.http.middleware :as mw]
            [grout.http.schemas :as s]
            [grout.http.stream :as stream]))

(defn health-handler
  "Construct the health handler, closing over the datasource."
  [db]
  (fn [_]
    (let [db-check (db/check-connection db)
          healthy? (:ok db-check)]
      {:status (if healthy? 200 503)
       :body {:status (if healthy? "ok" "degraded")
              :database (if healthy? "ok" "error")
              :version (or (System/getenv "VERSION")
                           (System/getenv "VERSION_TAG")
                           "dev")}})))

(defn version-handler [_]
  {:status 200
   :body {:git-commit (System/getenv "GIT_COMMIT")
          :git-timestamp (System/getenv "GIT_TIMESTAMP")
          :version (or (System/getenv "VERSION")
                       (System/getenv "VERSION_TAG"))}})

(defn routes [{:keys [db media]}]
  [""
   ["/openapi.json"
    {:get {:no-doc true
           :openapi {:info {:title "Grout API"
                            :version "0.1.0"
                            :description "Grout filler media store REST API"}}
           :handler (openapi/create-openapi-handler)}}]

   ["/health"
    {:get {:tags ["health"]
           :summary "Health/readiness check"
           :responses {200 {:body s/Health}
                       503 {:body s/Health}}
           :handler (health-handler db)}}]

   ["/api/version"
    {:get {:tags ["meta"]
           :summary "Build and version information"
           :responses {200 {:body s/Version}}
           :handler version-handler}}]

   ["/grout/media"
    {:get {:tags ["media"]
           :summary "Query filler media by channel, tags, duration and kind"
           :parameters {:query s/MediaQueryParams}
           :responses {200 {:body s/MediaQueryResult}}
           :handler (media/query-handler media)}
     :post {:tags ["media"]
            :summary "Upload media (multipart/form-data): hash + probe + normalize + insert. Dedups by content hash: 201 when newly stored, 200 when an existing item was matched/retagged/revived."
            :description "multipart/form-data fields: `file` (required — the media bytes), `kind` (required: bumper|filler|program), `channel`, `tags` (comma-separated), `source`, `source-url`, `name`, `description`. No shared filesystem is required between caller and server; the upload is streamed to the server and hashed/normalized there."
            :middleware [wrap-multipart-params]
            :responses {200 {:body s/Media}
                        201 {:body s/Media}
                        400 {:body s/APIError}
                        422 {:body s/APIError}}
            :handler (media/intake-handler media)}}]

   ["/grout/by-hash/:hash"
    {:get {:tags ["media"]
           :summary "Look up an item by content hash (SHA-256 of the source bytes)"
           :parameters {:path s/HashPath}
           :responses {200 {:body s/Media}
                       404 {:body s/APIError}}
           :handler (media/get-by-hash-handler media)}}]

   ["/grout/media/:id"
    {:get {:tags ["media"]
           :summary "Fetch one media item"
           :parameters {:path s/IdPath}
           :responses {200 {:body s/Media}
                       404 {:body s/APIError}}
           :handler (media/get-one-handler media)}
     :patch {:tags ["media"]
             :summary "Mutate name/description/tags/channel"
             :parameters {:path s/IdPath
                          :body s/MediaPatch}
             :responses {200 {:body s/Media}
                         400 {:body s/APIError}
                         404 {:body s/APIError}}
             :handler (media/patch-handler media)}
     :delete {:tags ["media"]
              :summary "Soft-delete (supersede); hard-delete + unlink with ?hard=true"
              :parameters {:path s/IdPath
                           :query s/DeleteQuery}
              :responses {200 {:body s/DeleteResult}
                          404 {:body s/APIError}}
              :handler (media/delete-handler media)}}]

   ["/grout/media/:id/enrich"
    {:post {:tags ["media"]
            :summary "Trigger AI metadata enrichment via Tunabrain"
            :parameters {:path s/IdPath}
            :responses {200 {:body s/Media}
                        404 {:body s/APIError}
                        502 {:body s/APIError}}
            :handler (media/enrich-handler media)}}]

   ["/grout/media/:id/stream"
    {:get {:tags ["media"]
           :summary "Byte-range HTTP streaming fallback (supports Range -> 206)"
           :parameters {:path s/IdPath}
           :responses {404 {:body s/APIError}
                       416 {:body s/APIError}}
           :handler (stream/stream-handler media)}}]

   ["/grout/enrich-by-tag/:tag"
    {:post {:tags ["directory-profiles"]
            :summary "Derive/refresh a shared profile for all media carrying a tag"
            :description "Directory-level enrichment: one LLM call derives dimensions + tags for the whole group and fans them out to every media item carrying `:tag` (typically `parent-directory:<x>`). Async by default (a worker enriches on its next tick); `wait=true` enriches inline. Re-enriches only when the item count has grown past `threshold-pct` (default 20), unless `force=true`."
            :parameters {:path s/TagPath
                         :body s/EnrichByTagRequest}
            :responses {200 {:body s/DirectoryProfile}
                        202 {:body s/DirectoryProfile}
                        400 {:body s/APIError}
                        404 {:body s/APIError}}
            :handler (dirprof/enrich-by-tag-handler media)}}]

   ["/grout/directory-profiles/:tag"
    {:get {:tags ["directory-profiles"]
           :summary "Read a directory/tag-group profile"
           :parameters {:path s/TagPath}
           :responses {200 {:body s/DirectoryProfile}
                       404 {:body s/APIError}}
           :handler (dirprof/get-profile-handler media)}}]

   ["/grout/media/:id/tags"
    {:get {:tags ["media"]
           :summary "List tags for a media item"
           :parameters {:path s/IdPath}
           :responses {200 {:body s/TagList}
                       404 {:body s/APIError}}
           :handler (media/get-tags-handler media)}
     :post {:tags ["media"]
            :summary "Add a tag to a media item"
            :parameters {:path s/IdPath
                         :body s/TagAddRequest}
            :responses {201 {:body s/TagList}
                        404 {:body s/APIError}}
            :handler (media/add-tag-handler media)}}]])

(defn handler
  "Create the ring handler with OpenAPI support."
  [{:keys [db media] :as deps}]
  (let [router (ring/router
                (routes deps)
                {:conflicts nil
                 :data {:muuntaja mw/muuntaja
                        :coercion malli-coercion/coercion
                        :middleware [parameters/parameters-middleware
                                     muuntaja-mw/format-negotiate-middleware
                                     muuntaja-mw/format-request-middleware
                                     mw/exception-middleware
                                     rrc/coerce-request-middleware
                                     rrc/coerce-response-middleware]}})

        fallback (ring/routes
                  (swagger-ui/create-swagger-ui-handler
                   {:path "/swagger-ui"
                    :url "/openapi.json"})
                  (ring/create-default-handler
                   {:not-found (fn [_] {:status 404 :body {:error "Not found"}})
                    :method-not-allowed (fn [_] {:status 405 :body {:error "Method not allowed"}})}))

        dispatch (ring/ring-handler router fallback)]
    (-> dispatch
        mw/wrap-json-response
        mw/wrap-request-logging
        mw/wrap-error-handler)))
