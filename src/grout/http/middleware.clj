(ns grout.http.middleware
  "HTTP middleware for JSON handling, exception handling, and logging."
  (:require [muuntaja.core :as m]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
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
  "Catches exceptions from handlers and returns structured error responses."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (log/error e "Handler exception" data)
          {:status (or (:status data) 500)
           :body {:error (or (ex-message e) "request failed")}}))
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
  "Ensures response body is JSON encoded if it's a map or collection."
  [handler]
  (fn [request]
    (let [response (handler request)
          body (:body response)]
      (if (or (map? body) (vector? body))
        (-> response
            (update :body #(json/parse-string
                            (json/generate-string % {:key-fn csk/->kebab-case-string})
                            csk/->kebab-case-keyword))
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
