(ns grout-bulk-test
  "Babashka unit tests for `bin/grout-bulk.bb`. Run with `bb test`.

   Like grout-cli-test, the script is loaded with `load-file`: it has no `ns`
   form, so its top-level `defn`s are interned into this namespace and referenced
   unqualified. The script guards its `-main` on `babashka.file`, so loading it
   here only defines vars — it does not execute the CLI or dispatch grout-cli."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(load-file "bin/grout-bulk.bb")

;; --- slugify / root-slug: filesystem-safe, stable, collision-resistant ------

(deftest slugify-basics
  (testing "lowercases, collapses punctuation runs to single underscores, trims"
    (is (= "adam_neely_music" (slugify "Adam Neely Music")))
    (is (= "80s_90s" (slugify "80s & 90s")))
    (is (= "tom_scott_extra" (slugify "Tom Scott (extra)")))
    (is (= "pinchflat_media_content_adam_neely"
           (slugify "/pinchflat-media/Content/Adam Neely"))))
  (testing "all-punctuation or empty input falls back to a usable stem"
    (is (= "root" (slugify "/")))
    (is (= "root" (slugify "")))))

(deftest root-slug-is-stable-and-distinguishes-punctuation-variants
  (testing "same path always yields the same stem"
    (is (= (root-slug "/x/Adam Neely") (root-slug "/x/Adam Neely"))))
  (testing "paths that slug identically still get distinct stems via the hash"
    ;; '/x/a b' and '/x/a-b' both slugify to 'x_a_b'; the hash suffix keeps
    ;; their state files from colliding.
    (is (not= (root-slug "/x/a b") (root-slug "/x/a-b")))
    (is (= "x_a_b" (subs (root-slug "/x/a b") 0 5)))))

;; --- unit-key: path relative to root, root itself is "." --------------------

(deftest unit-key-is-relative-to-root
  (is (= "2017" (unit-key "/media/Adam Neely" "/media/Adam Neely/2017")))
  (is (= "Adam Neely/2017" (unit-key "/media" "/media/Adam Neely/2017")))
  (is (= "." (unit-key "/media/Adam Neely" "/media/Adam Neely"))))

;; --- classify-line / tally-lines: mirror grout-cli's truthful tally ---------

(deftest classify-line-mirrors-grout-cli
  (testing "per-file result lines bucket by action / error"
    (is (= :uploaded (classify-line {:file "a.mp4" :action "uploaded"})))
    (is (= :retagged (classify-line {:file "a.mp4" :action "retagged"})))
    (is (= :existing (classify-line {:file "a.mp4" :action "unchanged"})))
    (is (= :existing (classify-line {:file "a.mp4" :action "deduplicated"})))
    (is (= :failed   (classify-line {:file "a.mp4" :error "upload failed: 500"}))))
  (testing "the trailing enrich-by-tag summary object is not a per-file line"
    (is (nil? (classify-line {:action "enrich-by-tag" :tag "parent-directory:x"
                              :uploaded 1 :existing 2})))))

(deftest tally-lines-counts-per-file-not-the-summary
  (let [objs [{:file "a" :action "uploaded"}
              {:file "b" :action "uploaded"}
              {:file "c" :action "unchanged"}
              {:file "d" :action "retagged"}
              {:file "e" :error "boom"}
              {:action "enrich-by-tag" :uploaded 99 :existing 99}]] ; must be ignored
    (is (= {:uploaded 2 :retagged 1 :existing 1 :failed 1 :files 5}
           (tally-lines objs)))))

(deftest parse-json-lines-is-defensive
  (testing "blank and non-JSON lines are skipped, valid JSON is parsed"
    (is (= [{:file "a" :action "uploaded"}]
           (parse-json-lines "\n{\"file\":\"a\",\"action\":\"uploaded\"}\nnot json\n")))
    (is (= [] (parse-json-lines nil)))
    (is (= [] (parse-json-lines "")))))

;; --- merge-units: resume without losing prior status ------------------------

(deftest merge-units-preserves-known-status-and-adds-new
  (let [existing {"2017" {:dir "/m/2017" :status "done" :uploaded 5}
                  "2018" {:dir "/m/2018" :status "failed"}}
        discovered [["2017" "/m/2017"] ["2018" "/m/2018"] ["2019" "/m/2019"]]
        merged (merge-units existing discovered)]
    (is (= "done" (get-in merged ["2017" :status])) "known done unit untouched")
    (is (= 5 (get-in merged ["2017" :uploaded])) "its counts are preserved")
    (is (= "failed" (get-in merged ["2018" :status])) "known failed unit untouched")
    (is (= "pending" (get-in merged ["2019" :status])) "newly discovered unit is pending"))
  (testing "a unit that vanished from disk is retained, not dropped"
    (let [existing {"gone" {:dir "/m/gone" :status "done"}}
          merged (merge-units existing [["here" "/m/here"]])]
      (is (contains? merged "gone"))
      (is (contains? merged "here")))))

;; --- units<->vec: JSON round-trip must not mangle "/"-bearing unit keys -----

(deftest units-vec-round-trip-preserves-slash-keys
  (let [units {"Adam Neely/2017" {:dir "/m/a/2017" :status "done" :uploaded 3}
               "Adam Neely/2018" {:dir "/m/a/2018" :status "pending"}}
        ;; Simulate a full disk round-trip through cheshire with keywordized
        ;; field names — the failure mode that keyed JSON objects would hit.
        v      (units->vec units)
        thawed (vec->units (json/parse-string (json/generate-string v) true))]
    (is (= units thawed)
        "slash-bearing keys survive serialize + keywordized parse via the vector form")
    (is (= "done" (get-in thawed ["Adam Neely/2017" :status])))))

;; --- selectable? / pending-keys: which units each command runs --------------

(deftest selectable-run-vs-retry
  (testing "run takes pending + in_progress (crash recovery), skips done + failed"
    (is (true?  (selectable? "run" false "pending")))
    (is (true?  (selectable? "run" false "in_progress")))
    (is (false? (selectable? "run" false "done")))
    (is (false? (selectable? "run" false "failed"))))
  (testing "run --retry-failed also takes failed"
    (is (true?  (selectable? "run" true "failed"))))
  (testing "retry takes failed + in_progress, never done or pending"
    (is (true?  (selectable? "retry" false "failed")))
    (is (true?  (selectable? "retry" false "in_progress")))
    (is (false? (selectable? "retry" false "done")))
    (is (false? (selectable? "retry" false "pending")))))

(deftest pending-keys-is-sorted-and-filtered
  (let [units {"2019" {:status "failed"}
               "2017" {:status "done"}
               "2018" {:status "pending"}
               "2020" {:status "in_progress"}}]
    (is (= ["2018" "2020"] (pending-keys units "run" false)))
    (is (= ["2018" "2019" "2020"] (pending-keys units "run" true)))
    (is (= ["2019" "2020"] (pending-keys units "retry" false)))))

;; --- discover-units: real filesystem walk -----------------------------------

(deftest discover-units-finds-file-bearing-dirs-recursively
  (let [root (str (fs/create-temp-dir))]
    (try
      ;; Layout: <root>/Adam Neely/{2017,2018}/*.mp4, plus an empty dir.
      (fs/create-dirs (fs/path root "Adam Neely" "2017"))
      (fs/create-dirs (fs/path root "Adam Neely" "2018"))
      (fs/create-dirs (fs/path root "Adam Neely" "empty"))
      (spit (str (fs/path root "Adam Neely" "2017" "a.mp4")) "x")
      (spit (str (fs/path root "Adam Neely" "2017" "b.mp4")) "y")
      (spit (str (fs/path root "Adam Neely" "2018" "c.mp4")) "z")
      (let [units (discover-units root)
            keys* (map first units)]
        (is (= ["Adam Neely/2017" "Adam Neely/2018"] keys*)
            "only directories that directly hold files become units; empty and pure-parent dirs are skipped")
        (is (every? fs/absolute? (map (comp fs/path second) units))
            "unit paths are absolute"))
      (finally (fs/delete-tree root)))))

(deftest discover-units-treats-a-flat-root-as-a-single-unit
  (let [root (str (fs/create-temp-dir))]
    (try
      (spit (str (fs/path root "only.mp4")) "x")
      (is (= ["."] (map first (discover-units root)))
          "files directly under root make the root itself the one unit, keyed '.'")
      (finally (fs/delete-tree root)))))

;; --- make-progress-logger: opt-in, best-effort progress sink ----------------

(deftest make-progress-logger-is-a-noop-without-a-sink
  (testing "nil/blank sink yields a no-op that swallows every call"
    (is (nil? ((make-progress-logger nil) "anything")))
    (is (nil? ((make-progress-logger "") "anything")))
    (is (nil? ((make-progress-logger "   ") "anything")))))

(deftest make-progress-logger-appends-timestamped-tagged-lines
  (let [f   (str (fs/create-temp-file))
        log (make-progress-logger f)]
    (try
      (log "start Adam Neely/2017")
      (log "uploaded a.mp4  (1/3 files, 0/1 dirs)")
      (let [lines (str/split-lines (slurp f))]
        (is (= 2 (count lines)) "one line appended per call")
        (is (every? #(str/includes? % "[grout-bulk] ") lines)
            "every line carries the grout-bulk tag")
        (is (str/ends-with? (first lines) "start Adam Neely/2017"))
        (is (str/ends-with? (second lines) "uploaded a.mp4  (1/3 files, 0/1 dirs)"))
        ;; A leading ISO-8601 timestamp (starts with a 4-digit year + '-').
        (is (re-find #"^\d{4}-\d{2}-\d{2}T" (first lines))
            "line is prefixed with an ISO-8601 timestamp"))
      (finally (fs/delete-if-exists f)))))

(deftest make-progress-logger-never-throws-on-an-unwritable-sink
  (testing "a sink under a nonexistent directory warns once, doesn't throw or abort"
    (let [log (make-progress-logger "/no/such/dir/really/progress.log")]
      (is (nil? (log "first")) "unwritable sink is swallowed")
      (is (nil? (log "second")) "and stays swallowed on subsequent calls"))))

;; --- human-duration: compact elapsed formatting -----------------------------

(deftest human-duration-formats
  (is (= "" (human-duration nil "2026-07-12T19:00:00-07:00")))
  (is (= "" (human-duration "bad" "also-bad")))
  (is (= "9s" (human-duration "2026-07-12T19:00:00-07:00" "2026-07-12T19:00:09-07:00")))
  (is (= "5m00s" (human-duration "2026-07-12T19:00:00-07:00" "2026-07-12T19:05:00-07:00")))
  (is (= "2h03m" (human-duration "2026-07-12T19:00:00-07:00" "2026-07-12T21:03:00-07:00"))))
