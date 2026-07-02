(ns grout.http.schemas
  "Malli schemas for request/response coercion and OpenAPI generation.")

(def APIError
  [:map [:error :string]])

(def Health
  [:map
   [:status [:enum "ok" "degraded"]]
   [:database [:enum "ok" "error"]]
   [:version {:optional true} [:maybe :string]]])

(def Version
  [:map
   [:git-commit {:optional true} [:maybe :string]]
   [:git-timestamp {:optional true} [:maybe :string]]
   [:version {:optional true} [:maybe :string]]])
