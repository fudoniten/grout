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

;; --- Manifest cache: (path, size, mtime) -> sha256 --------------------------
;;
;; Resumable uploads. The CLI hashes every file (local I/O) before it can
;; look the hash up in Grout, so for 200k items the resume case — "the
;; last run uploaded 50k items and then crashed" — is the dominant cost.
;; The manifest makes resumes free: a re-run reads (path, size, mtime)
;; triples, and only re-hashes files where size or mtime changed.
;;
;; The tests below exercise the cache primitives end-to-end against real
;; files in a temp directory. They are designed to be deterministic (no
;; mock layer) because the file-hash helper is small enough that
;; integration tests are clearer than unit tests.

(defn- with-tmp-dir
  "Run `f` with a freshly-created temp dir bound to `tmp`. Removes the
   dir on exit, swallowing any teardown error."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try (f tmp)
      (finally
        (try (fs/delete-tree tmp) (catch Exception _ nil))))))

(defn- tmp-file
  "Create a file in `dir` named `name` with `n` random bytes, returning
   the path. The name is provided by the caller so two tmp-files in
   the same test don't collide on `fixture.bin`."
  [dir name n]
  (let [p (str (fs/file dir name))
        _  (with-open [out (clojure.java.io/output-stream p)]
             (.write out (byte-array n)))]
    p))

(deftest manifest-key--absolutizes-and-stat-s
  (testing "the cache key is (abs-path, size, mtime-seconds)"
    (with-tmp-dir
      (fn [dir]
        (let [p (tmp-file dir "a.bin" 42)
              [abs sz mt] (manifest-key p)]
          (is (= abs (str (fs/absolutize p))) "absolutize the path")
          (is (= 42 sz) "size in bytes")
          (is (and (integer? mt) (pos? mt)) "mtime in seconds since epoch"))))))

(deftest manifest-key--tolerates-trailing-slash
  (testing "trailing-slash and no-slash variants map to the same key"
    (with-tmp-dir
      (fn [dir]
        (let [p (tmp-file dir "a.bin" 100)]
          (is (= (manifest-key p) (manifest-key (str p "/")))))))))

(deftest load-manifest!--empty-when-missing
  (testing "a non-existent path returns an empty cache without erroring"
    (with-tmp-dir
      (fn [dir]
        (let [mp (str (fs/file dir "nope.jsonl"))
              cache (load-manifest! mp)]
          (is (empty? @cache)))))))

(deftest load-manifest!--round-trips-valid-rows
  (testing "rows written by flush-manifest! are read back identically"
    (with-tmp-dir
      (fn [dir]
        (let [p1 (tmp-file dir "a.bin" 16)
              p2 (tmp-file dir "b.bin" 64)
              mp (str (fs/file dir "manifest.jsonl"))
              cache (load-manifest! mp)]
          (file-hash cache p1)
          (file-hash cache p2)
          (is (flush-manifest! cache mp p1))
          (is (flush-manifest! cache mp p2))
          (let [reloaded (load-manifest! mp)]
            (is (= 2 (count @reloaded))
                "both rows come back from disk")
            (is (= (file-hash (atom {}) p1) (get @reloaded (manifest-key p1)))
                "row 1 hash matches a fresh hash")
            (is (= (file-hash (atom {}) p2) (get @reloaded (manifest-key p2)))
                "row 2 hash matches a fresh hash")))))))

(deftest load-manifest!--skips-malformed-lines
  (testing "garbage lines are skipped, valid lines survive"
    (with-tmp-dir
      (fn [dir]
        (let [p  (tmp-file dir "a.bin" 32)
              mp (str (fs/file dir "m.jsonl"))
              ;; Write one valid row and one garbage row.
              [abs sz mt] (manifest-key p)
              sha (file-hash (atom {}) p)]
          (spit mp (str "not json\n"
                        (json/generate-string {:path abs :size sz :mtime mt :hash sha})
                        "\n"
                        "{also not json}\n"))
          (let [cache (load-manifest! mp)]
            (is (= 1 (count @cache))
                "the one valid row came back; garbage was skipped")
            (is (= sha (get @cache (manifest-key p))))))))))

(deftest file-hash--cache-hit-skips-rehash
  (testing "a (path, size, mtime) cache hit returns the cached value without re-hashing"
    (with-tmp-dir
      (fn [dir]
        (let [p  (tmp-file dir "a.bin" 1024)
              k  (manifest-key p)
              cache (atom {k "deadbeefcafebabe0123456789abcdef0123456789abcdef0123456789abcdef"})]
          (is (= "deadbeefcafebabe0123456789abcdef0123456789abcdef0123456789abcdef"
                 (file-hash cache p))
              "the cached hash is returned without re-reading the file"))))))

(deftest file-hash--cache-miss-hashes-and-remembers
  (testing "a miss computes the hash, then returns it (and remembers it)"
    (with-tmp-dir
      (fn [dir]
        (let [p     (tmp-file dir "a.bin" 1024)
              cache (atom {})
              h1    (file-hash cache p)
              h2    (file-hash cache p)]
          (is (= 64 (count h1)) "sha256 is 64 hex chars")
          (is (re-matches #"[0-9a-f]{64}" h1))
          (is (= h1 h2) "second call returns the same value")
          (is (= h1 (get @cache (manifest-key p)))
              "the cache remembers the hash for the rest of the run"))))))

(deftest flush-manifest!--no-op-when-path-blank
  (testing "no manifest path => no write, no error"
    (let [cache (atom {})]
      (is (nil? (flush-manifest! cache nil "/some/file"))
          "returns nil on no-op; doesn't throw"))))

(deftest flush-manifest!--survives-unwritable-target
  (testing "a path the process can't write to is a warning, not a failure"
    (let [cache (atom {})]
      ;; /proc/1/cmdline is a real read-only file, not a directory.
      (is (nil? (flush-manifest! cache "/proc/1/cmdline" "/some/file"))
          "returns nil rather than throwing"))))

(deftest manifest-roundtrip--size-change-invalidates
  (testing "changing a file's size forces a re-hash on the next read"
    (with-tmp-dir
      (fn [dir]
        (let [p  (tmp-file dir "a.bin" 100)
              mp (str (fs/file dir "m.jsonl"))
              cache-1 (load-manifest! mp)]
          (file-hash cache-1 p) ; populate the cache so flush-manifest! has a row to write
          (is (flush-manifest! cache-1 mp p))
          (let [sha-100 (get @cache-1 (manifest-key p))]
            (is (= sha-100 (get @(load-manifest! mp) (manifest-key p)))
                "100-byte file is in the cache")
            (Thread/sleep 1100)
            ;; Overwrite with a different size. mtime also advances, so
            ;; the cache key (path, size, mtime) changes too.
            (tmp-file dir "a.bin" 200)
            (let [cache-2 (load-manifest! mp)]
              (is (nil? (get @cache-2 (manifest-key p)))
                  "the changed file is a cache miss (checked before hashing)")
              (let [sha-200 (file-hash cache-2 p)]
                (is (not= sha-100 sha-200)
                    "the new hash is the new content's, not the cached one")))))))))
