(ns grout.http.middleware
  "HTTP middleware for JSON handling, exception handling, and logging."
  (:require [muuntaja.core :as m]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
            [malli.error :as me]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [com.fasterxml.jackson.core JsonGenerator]))

(defn- java-instant-encoder [^Instant v ^JsonGenerator gen]
  (.writeString gen (.toString v)))

(def muuntaja
  "JSON encoding/decoding configuration using kebab-case keyword keys."
  (m/create
   (-> m/default-options
       (assoc-in [:formats "application/json" :decoder-opts]
                 {:decode-key-fn csk/->kebab-case-keyword})
       (assoc-in [:formats "application/json" :encoder-opts]
                 {:encode-key-fn csk/->kebab-case-string
                  :encoders {Instant java-instant-encoder}}))))

(defn exception-middleware
  "Catches exceptions from handlers and coercion, returning structured error
   responses.

   Reitit request-coercion failures (a bad UUID in the path, a malformed query
   param, an invalid JSON body) are *client* errors: they become 400s with a
   humanized message and are logged at INFO. Previously they fell through to the
   generic branch, which had no :status to read and so returned 500 while
   dumping the entire request + router map at ERROR — turning a routine bad
   probe (e.g. GET /grout/media/abc) into log noise and a misleading server
   error. Response-coercion failures mean the server produced a body that
   violates its own schema, so those stay 500s but are still logged compactly."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (case (:type data)
            :reitit.coercion/request-coercion
            (let [humanized (me/humanize data)]
              (log/info "Request coercion failed"
                        {:uri (:uri request) :in (:in data) :errors humanized})
              {:status 400
               :body {:error (str "Invalid request parameters: " (pr-str humanized))}})

            :reitit.coercion/response-coercion
            (do
              (log/error "Response coercion failed"
                         {:uri (:uri request) :in (:in data) :errors (me/humanize data)})
              {:status 500
               :body {:error "Internal server error"}})

            (do
              (log/error e "Handler exception" data)
              {:status (or (:status data) 500)
               :body {:error (or (ex-message e) "request failed")}}))))
      (catch Exception e
        (log/error e "Unexpected exception")
        {:status 500
         :body {:error "Internal server error"}}))))

(defn wrap-request-logging
  "Logs incoming requests for debugging and monitoring."
  [handler]
  (fn [request]
    (log/debug "HTTP request"
               {:method (:request-method request)
                :uri (:uri request)
                :query (:query-string request)})
    (handler request)))

(defn wrap-json-response
  "Ensures response body is JSON encoded if it's a map or collection.
   Ring requires :body to be a String, File, InputStream or ISeq of Strings —
   a raw Clojure map is silently dropped by the Jetty adapter (empty body,
   Content-Length: 0), so the encoded result must replace :body as a string,
   not just be decoded back into a map."
  [handler]
  (fn [request]
    (let [response (handler request)
          body (:body response)]
      (if (or (map? body) (vector? body))
        (-> response
            (assoc :body (json/generate-string body {:key-fn csk/->kebab-case-string}))
            (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))
        response))))

(defn wrap-error-handler
  "Top-level error boundary for unhandled exceptions."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/error e "Unhandled exception in handler")
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body {:error "Internal server error"}}))))
