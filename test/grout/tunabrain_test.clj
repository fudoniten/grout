(ns grout.tunabrain-test
  "Wire-shape tests for the new Tunabrain client. Verifies the
  `/categorize` and `/tags` request/response shapes match the
  Tunabrain OpenAPI spec, and the error-handling semantics.

  Per the design at
  /opt/data/home/docs/grout-tunabrain-enrichment-requirements.md,
  the client does NOT call /v1/chat/completions. The old test used a
  fake OpenAI response shape; this rewrite tests the actual contract."
  (:require [clojure.test :refer [deftest is]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [grout.tunabrain :as tb]))

(def ^:private endpoint "http://tunabrain.example.com")
(def ^:private cl (tb/create {:endpoint endpoint}))

;; The mock http/post records each call in `*captured*` so the test
;; body can read the request body. Tests run in random order, so each
;; test must reset `*captured` to nil at start (the `with-mock` macro
;; does this).
(def ^:private *captured (atom nil))

(defn- mock-post
  "A `http/post` replacement that records the call to `*captured` and
  returns a fake response. `respond-with` is the response map."
  [respond-with]
  (fn [url opts]
    (let [body (:body opts)
          captured {:url url
                    :content-type (get-in opts [:headers "Content-Type"])
                    :accept (get-in opts [:accept])
                    :body-string (when (string? body) body)}]
      (reset! *captured captured)
      respond-with)))

(defn- body-as-map []
  (when-let [s (:body-string @*captured)]
    (json/parse-string s true)))

(defmacro ^:private with-mock
  "Wrap the test body in a `with-redefs` that replaces `http/post` with
  `mock`, and reset `*captured` to nil so each test starts clean."
  [mock & body]
  `(let [_# (reset! *captured nil)]
     (with-redefs [http/post ~mock]
       ~@body)))

;; --- request-categorization! ------------------------------------------------

(deftest categorize-builds-correct-wire-shape
  (let [cat-resp-body (json/generate-string
                        {:dimensions [{:dimension "audience"
                                      :values    ["kids"]}
                                     {:dimension "channel"
                                      :values    ["goldenreels"]}]
                         :mappings   []
                         :context    {:summary "..."
                                      :source  "wikipedia"
                                      :links   []}})
        fake-resp {:status 200 :body cat-resp-body}]
    (with-mock (mock-post fake-resp)
      (let [row        {:id (java.util.UUID/randomUUID)
                        :name "Test Bumper"}
            dim-config {:audience {:description "Audience"
                                   :values      ["kids" "family"]}
                        :channel  {:description "Channel"
                                   :values      ["goldenreels" "britannia"]}}
            result     (tb/request-categorization! cl row dim-config)
            body       (body-as-map)]
        (is (= (str endpoint "/categorize") (:url @*captured)))
        (is (= "application/json" (:content-type @*captured)))
        (is (= :json (:accept @*captured)))
        (is (= (str (:id row)) (get-in body [:media :id])))
        (is (= "Test Bumper" (get-in body [:media :title])))
        (is (= (str (:id row)) (get-in body [:media :id])))
        (is (= "Test Bumper" (get-in body [:media :title])))
        (is (= "Audience" (get-in body [:categories :audience :description])))
        (is (= ["kids" "family"] (get-in body [:categories :audience :values])))
        (is (= ["goldenreels" "britannia"] (get-in body [:categories :channel :values])))
        (is (= [] (get-in body [:channels]))
            ":channels is a request field; always sent as [] when no channels provided")
        (is (= 2 (count (:dimensions result))))
        (is (= "wikipedia" (get-in result [:context :source])))))))

(deftest categorize-includes-replayed-context
  (let [ctx {:summary "corrected summary" :source "provided-summary" :links []}]
    (with-mock (mock-post {:status 200
                           :body (json/generate-string
                                   {:dimensions [] :mappings []})})
      (tb/request-categorization!
        cl {:id (java.util.UUID/randomUUID) :name "x"}
        {:audience {:description "A" :values ["kids"]}}
        :context ctx))
    (is (= ctx (get-in (body-as-map) [:context]))
        "context round-trips unchanged")))

(deftest categorize-throws-on-5xx
  (with-mock (mock-post {:status 500 :body "{\"error\":\"upstream\"}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-categorization!
                   cl {:id (java.util.UUID/randomUUID) :name "x"}
                   {:audience {:description "A" :values ["kids"]}})))))

(deftest categorize-throws-on-422
  (with-mock (mock-post {:status 422 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-categorization!
                   cl {:id (java.util.UUID/randomUUID) :name "x"}
                   {:audience {:description "A" :values []}})))))

(deftest categorize-throws-on-missing-dimensions
  (with-mock (mock-post {:status 200 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-categorization!
                   cl {:id (java.util.UUID/randomUUID) :name "x"}
                   {:audience {:description "A" :values ["kids"]}})))))

(deftest categorize-throws-on-connection-refused
  (with-redefs [http/post (fn [_ _]
                            (throw (java.net.ConnectException. "refused")))]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"connection refused"
          (tb/request-categorization!
            cl {:id (java.util.UUID/randomUUID) :name "x"}
            {:audience {:description "A" :values ["kids"]}})))))

;; --- request-tags! ----------------------------------------------------------

(deftest tags-builds-correct-wire-shape
  (let [tag-resp-body (json/generate-string
                        {:tags    ["calm" "short"]
                         :context {:summary "..."
                                   :source  "provided-text"
                                   :links   []}})]
    (with-mock (mock-post {:status 200 :body tag-resp-body})
      (let [row    {:id (java.util.UUID/randomUUID) :name "Bumper" :tags ["existing-tag"]}
            result (tb/request-tags! cl row ["existing-tag" "filename:foo.mp4"])
            body   (body-as-map)]
        (is (= (str endpoint "/tags") (:url @*captured)))
        (is (= (str (:id row)) (get-in body [:media :id])))
        (is (= "Bumper" (get-in body [:media :title])))
        (is (= ["existing-tag" "filename:foo.mp4"] (:existing_tags body)))
        (is (= ["calm" "short"] (:tags result)))
        (is (= "provided-text" (get-in result [:context :source])))))))

(deftest tags-includes-replayed-context
  (let [ctx {:summary "corrected" :source "provided-summary" :links []}]
    (with-mock (mock-post {:status 200
                           :body (json/generate-string {:tags []})})
      (tb/request-tags! cl {:id (java.util.UUID/randomUUID) :name "x"}
                       ["existing"] :context ctx))
    (is (= ctx (get-in (body-as-map) [:context])))))

(deftest tags-throws-on-5xx
  (with-mock (mock-post {:status 500 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-tags! cl {:id (java.util.UUID/randomUUID) :name "x"} [])))))

(deftest tags-throws-on-missing-tags
  (with-mock (mock-post {:status 200 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-tags! cl {:id (java.util.UUID/randomUUID) :name "x"} [])))))

;; --- helpers ----------------------------------------------------------------

(deftest dimension-selections->tag-prefix
  (is (= ["audience:kids" "audience:family" "channel:goldenreels"]
         (tb/dimension-selections->tag-prefix
           [{:dimension "audience" :values ["kids" "family"]}
            {:dimension "channel"  :values ["goldenreels"]}]))))

(deftest build-dimension-config-roundtrips-shape
  (is (= {"audience" {:description "A" :values ["kids" "family"]}
          "channel"  {:description "C" :values ["goldenreels"]}}
         (tb/build-dimension-config
           {:audience {:description "A" :values ["kids" "family"]}
            :channel  {:description "C" :values ["goldenreels"]}}))))

(deftest media->tunabrain-uses-id-and-title
  (is (= {:id "abc-123" :title "Test"}
         (#'tb/media->tunabrain {:id "abc-123" :name "Test"}))))

(deftest media->tunabrain-prefers-name-over-path
  (is (= {:id "abc-123" :title "Human Name"}
         (#'tb/media->tunabrain {:id "abc-123" :name "Human Name"
                                 :path "/media/some_file.mp4"}))))

(deftest media->tunabrain-derives-title-from-path-when-no-name
  (is (= {:id "abc-123" :title "keeping motivated 2025"}
         (#'tb/media->tunabrain
           {:id "abc-123" :path "/media/keeping_motivated.2025.mp4"}))))

(deftest media->tunabrain-falls-back-to-unknown
  (is (= {:id "abc-123" :title "Unknown"}
         (#'tb/media->tunabrain {:id "abc-123"})))
  (is (= {:id "abc-123" :title "Unknown"}
         (#'tb/media->tunabrain {:id "abc-123" :name "" :path "  "}))))

;; --- endpoint sanitization --------------------------------------------------

(deftest create-strips-trailing-slashes
  (let [c (tb/create {:endpoint "http://tunabrain:8080///"})]
    (is (= "http://tunabrain:8080" (:endpoint c)))))

(deftest create-requires-endpoint
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":endpoint"
        (tb/create {}))))
