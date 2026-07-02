(ns grout.http.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [grout.http.routes :as routes]))

(def ^:private fake-db
  (reify Object
    (toString [_] "fake-datasource")))

(defn- handler []
  (routes/handler {:db fake-db :media {}}))

(deftest health-ok-when-db-ping-succeeds
  (with-redefs [grout.db/check-connection (fn [_] {:ok true})]
    (let [response ((handler) (mock/request :get "/health"))]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status])))
      (is (= "ok" (get-in response [:body :database]))))))

(deftest health-degraded-when-db-ping-fails
  (with-redefs [grout.db/check-connection (fn [_] {:ok false :error "timeout"})]
    (let [response ((handler) (mock/request :get "/health"))]
      (is (= 503 (:status response)))
      (is (= "degraded" (get-in response [:body :status])))
      (is (= "error" (get-in response [:body :database]))))))

(deftest version-returns-metadata
  (let [response ((handler) (mock/request :get "/api/version"))]
    (is (= 200 (:status response)))
    (is (contains? (:body response) :git-commit))))

(deftest not-found-handler
  (let [response ((handler) (mock/request :get "/no-such-route"))]
    (is (= 404 (:status response)))))
