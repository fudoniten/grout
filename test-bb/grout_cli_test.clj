(ns grout-cli-test
  "Babashka unit tests for `bin/grout-cli.bb`. Run with `bb test`.

   The script is loaded with `load-file`: it has no `ns` form, so its top-level
   `defn`s are interned into this namespace and referenced unqualified below. The
   script guards its `-main` invocation on `babashka.file`, so loading it here
   only defines vars — it does not execute the CLI."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]))

(load-file "bin/grout-cli.bb")

;; --- grouping-concept: --group vs parent-of-leaf default --------------------
;;
;; Regression: without --group, the default concept used to be the leaf
;; basename of --upload-dir. Pointing --upload-dir at a year subdir
;; (`.../Adam Neely Music/2024`) keyed on `2024` — unshareable across
;; years and colliding with any other creator's year folders. The default
;; is now the parent of the leaf, so sibling year folders share one
;; profile keyed on the creator name. The test in PR #11 explicitly
;; documented the leaf default ("back-compat"); the parent default
;; supersedes it (PR #14). See `bin/grout-cli.bb/grouping-concept`.

(deftest grouping-concept--group-wins-over-default
  (testing "an explicit --group keys the profile independent of the walked dir"
    (is (= "Adam Neely Music"
           (grouping-concept "Adam Neely Music" "/x/Adam Neely Music/2024")))
    (is (= "Adam Neely Music"
           (grouping-concept "Adam Neely Music" "/x/Adam Neely Music/2025")))
    (is (= "Adam Neely Music"
           (grouping-concept "Adam Neely Music" "/x/Some/Other/Path")))
    (is (= "Adam Neely Music"
           (grouping-concept "Adam Neely Music" "/x/Adam Neely Music/2024/")))
    ;; trailing slash is normalised away by fs/normalize
    ))

(deftest grouping-concept--defaults-to-parent-of-leaf
  (testing "without --group, the parent of the leaf is the concept"
    (is (= "Adam Neely Music"
           (grouping-concept nil "/x/Adam Neely Music/2024"))
        "year subdirs share a parent-directory tag by default")
    (is (= "Intelligence Squared"
           (grouping-concept nil "/pinchflat-media/Filler/Intelligence Squared/2019"))
        "the 2026-07-11 IQ2 case is the canonical motivation for this fix")
    (is (= "Adam Neely Music"
           (grouping-concept "" "/x/Adam Neely Music/2024"))
        "an empty --group falls through to the parent default, same as nil"))

  (testing "a single-component path falls back to the leaf when its parent is the filesystem root"
    (is (= "Filler" (grouping-concept nil "/Filler"))
        "a top-level dir whose parent is the filesystem root uses the leaf"))

  (testing "a relative bare name resolves against CWD; the parent is then the CWD itself"
    ;; `--upload-dir Filler` from /home/fudo means /home/fudo/Filler;
    ;; the parent is /home/fudo. That's a meaningful group label.
    (is (not= "Filler" (grouping-concept nil "Filler"))
        "the parent of a relative path is the CWD, which IS a meaningful group")))

(deftest grouping-concept--siblings-share-the-default
  (testing "two year-leaves under one creator share a parent-directory tag via the default"
    (let [tag-2024 (normalize-dir-name
                    (grouping-concept nil "/x/Adam Neely Music/2024"))
          tag-2025 (normalize-dir-name
                    (grouping-concept nil "/x/Adam Neely Music/2025"))]
      (is (= "adam-neely-music" tag-2024))
      (is (= tag-2024 tag-2025)
          "same parent => one shared parent-directory tag across different year leaves"))))

;; --- Bug 3: the directory tally reports truthfully ---------------------------

(deftest classify-result-is-truthful
  (testing "an already-stored, fully-tagged file is a no-op, never a failure"
    (is (= :existing (classify-result {:action :unchanged})))
    (is (= :existing (classify-result {:action :deduplicated}))))

  (testing "gaining >=1 tag on an existing file is a retag, not a failure"
    (is (= :retagged (classify-result {:action :retagged}))))

  (testing "newly stored bytes count as an upload"
    (is (= :uploaded (classify-result {:action :uploaded}))))

  (testing "only genuine errors count as failures"
    (is (= :failed (classify-result {:error "upload failed: Internal server error" :status 500})))
    (is (= :failed (classify-result {:action :retag-failed
                                     :error "failed to add tag(s): x (status 500)"})))))

;; --- Fingerprint ------------------------------------------------------------
;;
;; The CLI and the server must compute identical fingerprints, or every lookup
;; misses and falls back to a full hash. These vectors are shared with
;; test/grout/media/hash_test.clj (and were derived independently in Python).

(defn- with-tmp-dir
  "Run `f` with a freshly-created temp dir. Removes the dir on exit,
   swallowing any teardown error."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try (f tmp)
      (finally
        (try (fs/delete-tree tmp) (catch Exception _ nil))))))

(defn- pattern-file
  "Create `name` in `dir` holding `n` bytes where byte i is (i mod 251)."
  [dir name n]
  (let [p (str (fs/file dir name))]
    (with-open [out (clojure.java.io/output-stream p)]
      (.write out (byte-array (map #(unchecked-byte (mod % 251)) (range n)))))
    p))

(deftest fingerprint-file--matches-server-vectors
  (with-tmp-dir
    (fn [dir]
      (is (= "fp1:976162828ad3ba7f1c0c6253d65b546ee2ea03a4fd3e8d9e84d61706bac0bce8"
             (fingerprint-file (pattern-file dir "small.bin" 3)))
          "small file: whole-file hash")
      (is (= "fp1:1dd735f6779c0b12387c59a61b31276173092c692e950f846cb482580837719b"
             (fingerprint-file (pattern-file dir "large.bin" 1000000)))
          "large file: three samples"))))

(deftest lookup-fingerprints--batches-and-maps-by-fingerprint
  (let [fps   (mapv #(str "fp1:" (format "%064x" %)) (range 1200))
        calls (atom [])]
    (with-redefs [http-post (fn [_url body _v]
                              (swap! calls conj (count (:fingerprints body)))
                              {:status 200
                               :json {:matches [{:id "x" :fingerprint (first (:fingerprints body))}]}})]
      (let [m (lookup-fingerprints "http://g" fps false)]
        (is (= [500 500 200] @calls) "one request per 500 fingerprints")
        (is (= #{(nth fps 0) (nth fps 500) (nth fps 1000)} (set (keys m)))
            "keys come back as the fingerprint strings")))))

(deftest lookup-fingerprints--old-server-means-no-matches
  (with-redefs [http-post (fn [_ _ _] {:status 404 :json {:error "Not found"}})]
    (is (= {} (lookup-fingerprints "http://g" ["fp1:aa"] false))
        "every file then falls back to the full-hash lookup")))

(deftest find-existing--fingerprint-hit-skips-full-hash
  (with-redefs [sha256-file (fn [_] (throw (ex-info "should not hash" {})))]
    (is (= {:existing {:status 200 :json {:id "x"}}}
           (find-existing "http://g" {} {"fp1:aa" {:id "x"}} "fp1:aa" "/f")))))

(deftest find-existing--hash-hit-attaches-fingerprint
  (let [attached (atom nil)]
    (with-redefs [sha256-file (fn [_] "h")
                  by-hash (fn [_ _ _] {:status 200 :json {:id "x" :fingerprint nil}})
                  attach-fingerprint! (fn [_ id fp _] (reset! attached [id fp]))]
      (is (= "h" (:hash (find-existing "http://g" {} {} "fp1:aa" "/f"))))
      (is (= ["x" "fp1:aa"] @attached)))
    (reset! attached nil)
    (with-redefs [sha256-file (fn [_] "h")
                  by-hash (fn [_ _ _] {:status 200 :json {:id "x" :fingerprint nil}})
                  attach-fingerprint! (fn [_ id fp _] (reset! attached [id fp]))]
      (find-existing "http://g" {:dry-run true} {} "fp1:aa" "/f")
      (is (nil? @attached) "a dry run changes nothing on the server"))))
