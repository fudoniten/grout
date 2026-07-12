(ns grout-cli-test
  "Babashka unit tests for `bin/grout-cli.bb`. Run with `bb test`.

   The script is loaded with `load-file`: it has no `ns` form, so its top-level
   `defn`s are interned into this namespace and referenced unqualified below. The
   script guards its `-main` invocation on `babashka.file`, so loading it here
   only defines vars — it does not execute the CLI."
  (:require [clojure.test :refer [deftest is testing]]))

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
