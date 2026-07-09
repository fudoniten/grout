(ns grout-cli-test
  "Babashka unit tests for `bin/grout-cli.bb`. Run with `bb test`.

   The script is loaded with `load-file`: it has no `ns` form, so its top-level
   `defn`s are interned into this namespace and referenced unqualified below. The
   script guards its `-main` invocation on `babashka.file`, so loading it here
   only defines vars — it does not execute the CLI."
  (:require [clojure.test :refer [deftest is testing]]))

(load-file "bin/grout-cli.bb")

;; --- Bug 1: --group keys the shared profile independent of the walked dir ----

(deftest group-shares-parent-directory-tag-across-subdirs
  (testing "two year-leaves under one creator share a parent-directory tag via --group"
    (let [tag-2024 (normalize-dir-name
                    (grouping-concept "Adam Neely Music" "/pinchflat-media/Content/Adam Neely Music/2024"))
          tag-2025 (normalize-dir-name
                    (grouping-concept "Adam Neely Music" "/pinchflat-media/Content/Adam Neely Music/2025"))]
      (is (= "adam-neely-music" tag-2024))
      (is (= tag-2024 tag-2025)
          "same --group => one shared parent-directory tag across different subdirs")))

  (testing "without --group the upload-dir leaf basename is used (back-compat)"
    (is (= "2024" (grouping-concept nil "/x/Adam Neely Music/2024")))
    (is (= "2025" (grouping-concept "" "/x/Adam Neely Music/2025"))
        "a blank --group falls back to the leaf")))

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
