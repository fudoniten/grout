(ns grout.http.routes
  "HTTP routes with OpenAPI documentation and Malli validation."
  (:require [reitit.ring :as ring]
            [reitit.openapi :as openapi]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [grout.db :as db]
            [grout.http.middleware :as mw]
            [grout.http.schemas :as s]))

(defn health-handler [{:keys [db]}]
  (let [db-check (db/check-connection db)
        healthy? (:ok db-check)]
    {:status (if healthy? 200 503)
     :body {:status (if healthy? "ok" "degraded")
            :database (if healthy? "ok" "error")
            :version (or (System/getenv "VERSION")
                         (System/getenv "VERSION_TAG")
                         "dev")}}))

(defn version-handler [_]
  {:status 200
   :body {:git-commit (System/getenv "GIT_COMMIT")
          :git-timestamp (System/getenv "GIT_TIMESTAMP")
          :version (or (System/getenv "VERSION")
                       (System/getenv "VERSION_TAG"))}})

(defn routes []
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
           :handler health-handler}}]

   ["/api/version"
    {:get {:tags ["meta"]
           :summary "Build and version information"
           :responses {200 {:body s/Version}}
           :handler version-handler}}]])

(defn handler
  "Create the ring handler with OpenAPI support."
  [{:keys [db]}]
  (let [router (ring/router
                (routes)
                {:data {:muuntaja mw/muuntaja
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
