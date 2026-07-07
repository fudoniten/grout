(ns grout.http.routes-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [ring.mock.request :as mock]
            [grout.http.routes :as routes]
            [grout.media.enrich :as enrich]
            [grout.media.intake :as intake]
            [grout.media.store :as store]))

(def ^:private fake-db
  (reify Object
    (toString [_] "fake-datasource")))

(defn- handler []
  (routes/handler {:db fake-db :media {:ds fake-db :media-dir "/tmp"}}))

(defn- handler-with [media]
  (routes/handler {:db fake-db :media (merge {:ds fake-db} media)}))

(defn- call
  "Invoke a ring handler and decode a JSON-string :body back into a keywordized
   map, matching what mw/wrap-json-response actually hands to a real HTTP
   client (a String), rather than the pre-encoded map some tests used to see."
  [h req]
  (let [resp (h req)]
    (if (string? (:body resp))
      (update resp :body #(json/parse-string % true))
      resp)))

(def ^:private sample-id (java.util.UUID/randomUUID))

(def ^:private sample-row
  {:id sample-id
   :kind "bumper"
   :path "/data/media/grout/x.mp4"
   :name "Test Bumper"
   :description nil
   :channel "britannia"
   :tags ["daytime" "fun"]
   :duration_ms 65000
   :width 1920
   :height 1080
   :vcodec "h264"
   :acodec "aac"
   :source "tunarr-bumper"
   :source_url nil
   :content_hash "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
   :enriched false
   :created_at (java.time.Instant/now)
   :superseded_at nil})

(defn- json-req [method uri body]
  (-> (mock/request method uri)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

(def ^:private boundary "GroutTestBoundary")

(defn- multipart-part
  ([name value] (multipart-part name value nil nil))
  ([name value filename content-type]
   (str "--" boundary "\r\n"
        "Content-Disposition: form-data; name=\"" name "\""
        (when filename (str "; filename=\"" filename "\""))
        "\r\n"
        (when content-type (str "Content-Type: " content-type "\r\n"))
        "\r\n"
        value "\r\n")))

(defn- multipart-req
  "Build a multipart/form-data POST request. `fields` is a seq of
   [name value] or [name value filename content-type] (for the file part)."
  [uri fields]
  (let [body (str (apply str (map #(apply multipart-part %) fields))
                   "--" boundary "--\r\n")
        bytes (.getBytes ^String body "UTF-8")]
    (-> (mock/request :post uri)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/content-length (count bytes))
        (assoc :body (java.io.ByteArrayInputStream. bytes)))))

;; --- health / meta ---------------------------------------------------------

(deftest health-ok-when-db-ping-succeeds
  (with-redefs [store/query (constantly [])
                grout.db/check-connection (fn [_] {:ok true})]
    (let [response (call (handler) (mock/request :get "/health"))]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status])))
      (is (= "ok" (get-in response [:body :database]))))))

(deftest health-degraded-when-db-ping-fails
  (with-redefs [grout.db/check-connection (fn [_] {:ok false :error "timeout"})]
    (let [response (call (handler) (mock/request :get "/health"))]
      (is (= 503 (:status response)))
      (is (= "degraded" (get-in response [:body :status])))
      (is (= "error" (get-in response [:body :database]))))))

(deftest version-returns-metadata
  (let [response (call (handler) (mock/request :get "/api/version"))]
    (is (= 200 (:status response)))
    (is (contains? (:body response) :git-commit))))

(deftest not-found-handler
  (let [response (call (handler) (mock/request :get "/no-such-route"))]
    (is (= 404 (:status response)))))

(deftest openapi-spec-is-served
  (let [response (call (handler) (mock/request :get "/openapi.json"))
        body (:body response)]
    (is (= 200 (:status response)))
    (is (map? body) "spec must be a real body, not an empty/nil response")
    (is (= "3.1.0" (:openapi body)))
    (is (contains? (:paths body) (keyword "/grout/media/{id}"))
        "media routes must appear in the generated spec")))

;; --- media query -----------------------------------------------------------

(deftest query-returns-summaries
  (with-redefs [store/query (fn [_ _] [sample-row])]
    (let [resp (call (handler) (mock/request
                           :get "/grout/media?tags=fun,daytime&min_ms=1000&random=true&limit=5"))]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :count])))
      (let [item (first (get-in resp [:body :items]))]
        (is (= (str sample-id) (:id item)))
        (is (= 65000 (:duration-ms item)))
        (is (= (str "/grout/media/" sample-id "/stream") (:stream-url item)))
        (is (= ["daytime" "fun"] (:tags item)))))))

(deftest query-passes-parsed-params-to-store
  (let [captured (atom nil)]
    (with-redefs [store/query (fn [_ params] (reset! captured params) [])]
      (call (handler) (mock/request
                  :get "/grout/media?channel=britannia&tags=a,b&min_ms=100&max_ms=200&kind=bumper&random=true"))
      (is (= "britannia" (:channel @captured)))
      (is (= ["a" "b"] (:tags @captured)))
      (is (= 100 (:min-ms @captured)))
      (is (= 200 (:max-ms @captured)))
      (is (= "bumper" (:kind @captured)))
      (is (true? (:random @captured))))))

;; --- intake (multipart upload) -----------------------------------------------

(defn- upload-req
  ([] (upload-req {}))
  ([{:keys [kind tags channel source source-url name description bytes filename]
     :or {kind "bumper" bytes "media bytes" filename "clip.mp4"}}]
   (multipart-req "/grout/media"
                  (cond-> [["kind" kind]]
                    tags        (conj ["tags" tags])
                    channel     (conj ["channel" channel])
                    source      (conj ["source" source])
                    source-url  (conj ["source-url" source-url])
                    name        (conj ["name" name])
                    description (conj ["description" description])
                    true        (conj ["file" bytes filename "application/octet-stream"])))))

(deftest intake-creates-row-201
  (with-redefs [intake/intake! (fn [_ _] {:row sample-row :deduplicated false})]
    (let [resp (call (handler-with {:media-dir nil}) (upload-req {:tags "fun"}))]
      (is (= 201 (:status resp)))
      (is (= "bumper" (get-in resp [:body :kind]))))))

(deftest intake-dedup-returns-200
  (with-redefs [intake/intake! (fn [_ _] {:row sample-row :deduplicated true})]
    (let [resp (call (handler-with {:media-dir nil}) (upload-req {:tags "fun"}))]
      (is (= 200 (:status resp)) "existing item matched by hash"))))

(deftest intake-passes-parsed-fields-to-intake
  (let [captured (atom nil)]
    (with-redefs [intake/intake! (fn [_ req] (reset! captured req) {:row sample-row :deduplicated false})]
      ((handler-with {:media-dir nil})
       (upload-req {:tags "fun, kids" :channel "britannia" :name "Bump"}))
      (is (= "bumper" (:kind @captured)))
      (is (= ["fun" "kids"] (:tags @captured)))
      (is (= "britannia" (:channel @captured)))
      (is (= "Bump" (:name @captured)))
      (is (some? (:path @captured))))))

(deftest intake-no-file-is-400
  (let [resp (call (handler) (multipart-req "/grout/media" [["kind" "bumper"]]))]
    (is (= 400 (:status resp)))))

(deftest intake-missing-kind-is-400
  (let [resp (call (handler) (multipart-req "/grout/media"
                                       [["file" "bytes" "clip.mp4" "application/octet-stream"]]))]
    (is (= 400 (:status resp)))))

(deftest intake-invalid-kind-is-400
  (let [resp (call (handler) (upload-req {:kind "not-a-kind"}))]
    (is (= 400 (:status resp)))))

(deftest intake-failure-is-422
  (with-redefs [intake/intake! (fn [_ _] (throw (ex-info "ffprobe failed" {})))]
    (let [resp (call (handler-with {:media-dir nil}) (upload-req))]
      (is (= 422 (:status resp))))))

(deftest intake-cleans-up-tempfile
  (let [captured-path (atom nil)]
    (with-redefs [intake/intake! (fn [_ req]
                                   (reset! captured-path (:path req))
                                   {:row sample-row :deduplicated false})]
      (call (handler-with {:media-dir nil}) (upload-req))
      (is (some? @captured-path))
      (is (not (.exists (io/file @captured-path))) "temp upload file removed after intake"))))

;; --- by-hash ---------------------------------------------------------------

(deftest by-hash-found
  (with-redefs [store/find-by-hash (fn [_ _] sample-row)]
    (let [resp (call (handler) (mock/request :get "/grout/by-hash/deadbeef"))]
      (is (= 200 (:status resp)))
      (is (= "bumper" (get-in resp [:body :kind]))))))

(deftest by-hash-not-found
  (with-redefs [store/find-by-hash (fn [_ _] nil)]
    (let [resp (call (handler) (mock/request :get "/grout/by-hash/deadbeef"))]
      (is (= 404 (:status resp))))))

;; --- fetch one -------------------------------------------------------------

(deftest get-one-found
  (with-redefs [store/find-by-id (fn [_ _ & _] sample-row)]
    (let [resp (call (handler) (mock/request :get (str "/grout/media/" sample-id)))]
      (is (= 200 (:status resp)))
      (is (= "bumper" (get-in resp [:body :kind])))
      (is (= false (get-in resp [:body :enriched]))))))

(deftest get-one-not-found
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (let [resp (call (handler) (mock/request :get (str "/grout/media/" sample-id)))]
      (is (= 404 (:status resp))))))

(deftest get-one-invalid-uuid-is-400
  ;; A non-UUID id in the path is a client error, not a 500. Health/monitoring
  ;; probes (hermes-probe) discover /grout/media/{id} from the OpenAPI spec and
  ;; hit it with a placeholder; the request-coercion failure must surface as a
  ;; clean 400 rather than a 500 with the whole request dumped at ERROR.
  (doseq [bad ["abc" "123" "not-a-uuid"]]
    (let [resp (call (handler) (mock/request :get (str "/grout/media/" bad)))]
      (is (= 400 (:status resp)) (str "GET /grout/media/" bad " should be 400"))
      (is (string? (get-in resp [:body :error]))))))

(deftest delete-invalid-uuid-is-400
  (let [resp (call (handler) (mock/request :delete "/grout/media/not-a-uuid"))]
    (is (= 400 (:status resp)))))

;; --- patch -----------------------------------------------------------------

(deftest patch-updates-metadata
  (with-redefs [store/update-metadata! (fn [_ _ _] (assoc sample-row :name "Renamed"))]
    (let [resp (call (handler) (json-req :patch (str "/grout/media/" sample-id) {:name "Renamed"}))]
      (is (= 200 (:status resp)))
      (is (= "Renamed" (get-in resp [:body :name]))))))

(deftest patch-empty-body-is-400
  (let [resp (call (handler) (json-req :patch (str "/grout/media/" sample-id) {}))]
    (is (= 400 (:status resp)))))

(deftest patch-not-found
  (with-redefs [store/update-metadata! (fn [_ _ _] nil)]
    (let [resp (call (handler) (json-req :patch (str "/grout/media/" sample-id) {:name "X"}))]
      (is (= 404 (:status resp))))))

;; --- delete ----------------------------------------------------------------

(deftest delete-soft
  (with-redefs [store/soft-delete! (fn [_ _] sample-row)]
    (let [resp (call (handler) (mock/request :delete (str "/grout/media/" sample-id)))]
      (is (= 200 (:status resp)))
      (is (false? (get-in resp [:body :hard]))))))

(deftest delete-soft-not-found
  (with-redefs [store/soft-delete! (fn [_ _] nil)]
    (let [resp (call (handler) (mock/request :delete (str "/grout/media/" sample-id)))]
      (is (= 404 (:status resp))))))

(deftest delete-hard-unlinks
  (with-redefs [store/hard-delete! (fn [_ _] sample-row)]
    (let [resp (call (handler) (mock/request :delete (str "/grout/media/" sample-id "?hard=true")))]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :hard]))))))

;; --- tags ------------------------------------------------------------------

(deftest get-tags-returns-list
  (with-redefs [store/find-by-id (fn [_ _ & _] sample-row)]
    (let [resp (call (handler) (mock/request :get (str "/grout/media/" sample-id "/tags")))]
      (is (= 200 (:status resp)))
      (is (= ["daytime" "fun"] (get-in resp [:body :tags]))))))

(deftest add-tag-returns-201
  (with-redefs [store/add-tag! (fn [_ _ tag] (update sample-row :tags conj tag))]
    (let [resp (call (handler) (json-req :post (str "/grout/media/" sample-id "/tags") {:tag "kids"}))]
      (is (= 201 (:status resp)))
      (is (some #{"kids"} (get-in resp [:body :tags]))))))

;; --- enrich ----------------------------------------------------------------

(deftest enrich-endpoint-200
  (with-redefs [enrich/enrich-one! (fn [_ _ _ _] sample-row)]
    (let [resp (call (handler) (mock/request :post (str "/grout/media/" sample-id "/enrich")))]
      (is (= 200 (:status resp)))
      (is (= "bumper" (get-in resp [:body :kind]))))))

;; Regression: enrich-handler must pass the TunabrainClient (from
;; :tunabrain on the media map) and the dim-config (from :dim-config on
;; the media map) to enrich-one! — not the whole media map as either arg.
;;
;; The pre-fix code destructured `{:keys [ds tunabrain]}` and then read
;; `(:dim-config tunabrain)` — but `tunabrain` was the TunabrainClient
;; record (only :endpoint / :http-opts), so :dim-config was always nil.
;; This test asserts the wiring is correct: the dim-config arg must be
;; the map from the :grout/media component, not nil.
(deftest enrich-endpoint-forwards-dim-config-from-media-map
  (let [captured (atom nil)
        fake-client (reify Object (toString [_] "fake-tunabrain-client"))
        ;; The media map has :tunabrain (the client) and :dim-config
        ;; (the catalog). It also has :ds, the rest are ignored.
        media-map {:ds fake-db
                   :tunabrain fake-client
                   :dim-config {:audience {:description "A" :values ["kids"]}}}]
    (with-redefs [enrich/enrich-one! (fn [ds client dim-config id]
                                       (reset! captured {:ds ds :client client
                                                         :dim-config dim-config :id id})
                                       sample-row)]
      (call (handler-with media-map)
            (mock/request :post (str "/grout/media/" sample-id "/enrich"))))
    (is (identical? fake-db (:ds @captured)))
    (is (identical? fake-client (:client @captured))
        "the 2nd arg to enrich-one! must be the TunabrainClient (not the whole media map)")
    (is (= {:audience {:description "A" :values ["kids"]}} (:dim-config @captured))
        "the 3rd arg to enrich-one! must be the dim-config from the media map, not nil")))

(deftest enrich-endpoint-404-when-missing
  (with-redefs [enrich/enrich-one! (fn [_ _ _ _] nil)
                store/find-by-id (fn [_ _ & _] nil)]
    (let [resp (call (handler) (mock/request :post (str "/grout/media/" sample-id "/enrich")))]
      (is (= 404 (:status resp))))))

(deftest enrich-endpoint-502-when-enrichment-fails
  (with-redefs [enrich/enrich-one! (fn [_ _ _ _] nil)
                store/find-by-id (fn [_ _ & _] sample-row)]
    (let [resp (call (handler) (mock/request :post (str "/grout/media/" sample-id "/enrich")))]
      (is (= 502 (:status resp))))))
