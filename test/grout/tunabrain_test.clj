(ns grout.tunabrain-test
  "Wire-shape tests for the Tunabrain client. Verifies the
  `/categorize`, `/tags`, and `/enrich/short-form` request/response
  shapes match the Tunabrain OpenAPI spec, and the error-handling
  semantics.

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

;; --- request-enrich-short-form! ---------------------------------------------
;;
;; Replaces the prior two-call pattern (request-categorization! +
;; request-tags!) with a single /enrich/short-form call. Tunabrain
;; orchestrates the categorize + tags sub-calls internally and adds a
;; describe step that produces a display title + description.

(deftest enrich-short-form-builds-correct-wire-shape
  (let [resp-body (json/generate-string
                    {:media    {:id "m1" :title "mystery-bumper"}
                     :describe {:id "m1"
                                :title "Mystery Bumper"
                                :description "A short intro bumper."}
                     :dimensions [{:dimension "audience" :values ["kids"]}]
                     :tags    ["short" "intro"]
                     :context {:summary "..." :source "wikipedia" :links []}
                     :cost_estimate {:estimated_cost_usd 0.001
                                     :llm_calls_used 3
                                     :estimated_tokens "~4500"
                                     :model "gpt-4o-mini"}
                     :warnings []})]
    (with-mock (mock-post {:status 200 :body resp-body})
      (let [row        {:id (java.util.UUID/randomUUID)
                        :name "mystery-bumper-5e0ff26a"
                        :description nil
                        :tags ["existing-tag" "filename:foo.mp4"]}
            dim-config {:audience {:description "A" :values ["kids"]}}
            result     (tb/request-enrich-short-form! cl row dim-config
                                                    (:tags row))
            body       (body-as-map)]
        (is (= (str endpoint "/enrich/short-form") (:url @*captured)))
        (is (= "application/json" (:content-type @*captured)))
        (is (= (str (:id row)) (get-in body [:media :id]))
            "row id is propagated as the media id")
        (is (= "mystery-bumper-5e0ff26a" (get-in body [:media :title]))
            "row name is propagated as the media title (caller's working title)")
        (is (= ["existing-tag" "filename:foo.mp4"] (:existing_tags body))
            "existing_tags forwarded verbatim")
        (is (= "A" (get-in body [:categories :audience :description])))
        (is (= ["kids"] (get-in body [:categories :audience :values])))
        (is (= [] (:channels body)))
        (is (nil? (:context body))
            "no context replay when caller didn't supply one")
        (is (= "mystery-bumper" (get-in result [:media :title])))
        (is (= "Mystery Bumper" (get-in result [:describe :title]))
            "describe carries the AI-refined title (not the echo)")
        (is (= "A short intro bumper." (get-in result [:describe :description])))
        (is (= 1 (count (:dimensions result))))
        (is (= ["short" "intro"] (:tags result)))
        (is (= "wikipedia" (get-in result [:context :source])))))))

(deftest enrich-short-form-forwards-replayed-context
  (let [ctx {:summary "corrected by human" :source "provided-summary" :links []}]
    (with-mock (mock-post {:status 200
                           :body (json/generate-string
                                   {:media    {:id "m1" :title "x"}
                                    :dimensions []
                                    :tags    []
                                    :context {:source "provided-summary"}
                                    :cost_estimate {:estimated_cost_usd 0
                                                    :llm_calls_used 1
                                                    :estimated_tokens "~1"}})})
      (tb/request-enrich-short-form!
        cl {:id (java.util.UUID/randomUUID) :name "x"}
        {:audience {:description "A" :values ["kids"]}}
        []
        :context ctx))
    (is (= ctx (get-in (body-as-map) [:context]))
        "stored context is replayed unchanged")))

(deftest enrich-short-form-throws-on-5xx
  (with-mock (mock-post {:status 500 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-enrich-short-form!
                   cl {:id (java.util.UUID/randomUUID) :name "x"}
                   {:audience {:description "A" :values ["kids"]}}
                   [])))))

(deftest enrich-short-form-throws-on-missing-required-response-fields
  (with-mock (mock-post {:status 200 :body "{}"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/request-enrich-short-form!
                   cl {:id (java.util.UUID/randomUUID) :name "x"}
                   {:audience {:description "A" :values ["kids"]}}
                   [])))))

(deftest enrich-short-form-throws-on-connection-refused
  (with-redefs [http/post (fn [_ _]
                            (throw (java.net.ConnectException. "refused")))]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"connection refused"
          (tb/request-enrich-short-form!
            cl {:id (java.util.UUID/randomUUID) :name "x"}
            {:audience {:description "A" :values ["kids"]}}
            [])))))

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

(deftest media->tunabrain-handles-missing-name
  (is (= {:id "abc-123" :title "<unnamed>"}
         (#'tb/media->tunabrain {:id "abc-123"}))))

;; --- endpoint sanitization --------------------------------------------------

(deftest create-strips-trailing-slashes
  (let [c (tb/create {:endpoint "http://tunabrain:8080///"})]
    (is (= "http://tunabrain:8080" (:endpoint c)))))

(deftest create-requires-endpoint
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":endpoint"
        (tb/create {}))))
