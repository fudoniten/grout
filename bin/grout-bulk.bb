#!/usr/bin/env bb
;; grout-bulk — resumable, trackable bulk uploader for Grout.
;;
;; Walks a content root, and for every directory that directly contains files
;; it shells out to `grout-cli --upload-dir <dir> --json`, capturing the
;; per-file JSON manifest and recording per-directory progress in a JSON state
;; file. A crash, kill, or pod eviction resumes without re-doing finished work:
;; a directory marked `done` is skipped entirely, so its files are never
;; re-hashed. Path is treated as identity — nothing re-reads bytes to decide
;; whether a directory is already uploaded; the state file is the source of
;; truth, and grout-cli's own by-hash dedup is the safety net if a partial
;; directory is re-run.
;;
;; Why per *directory* and not per *file*: grout-cli's unit of work is a
;; directory (`--upload-dir`, non-recursive), and it fires one shared
;; directory-level enrichment per call. We track at the same granularity we
;; dispatch. The per-file detail still lands on disk — grout-cli's `--json`
;; output is captured verbatim as a per-directory `.jsonl` manifest.
;;
;; This intentionally leans on grout-cli for everything file-level (hashing,
;; dedup, tagging, enrichment, retry). grout-bulk only orchestrates: discover
;; work, dispatch, record, resume.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[java.security MessageDigest]
        '[java.time OffsetDateTime]
        '[java.time.temporal ChronoUnit])

(def usage "
grout-bulk — resumable bulk uploader for Grout (drives grout-cli)

Usage:
  grout-bulk <command> [options] [-- <grout-cli args...>]

Commands:
  run       Upload every file-bearing directory under --root. Resumes from the
            state file: skips directories already 'done', re-runs ones left
            'in_progress' by a crash. New directories are picked up on re-scan.
  retry     Re-run only directories previously left 'failed' (or 'in_progress').
            Use after fixing whatever made them fail (e.g. a Grout outage).
  status    Print progress for --root (counts + per-directory breakdown). No
            work is done. Add --json for machine-readable output.
  reset     Delete the state file for --root (logs/manifests are kept, so a
            re-run still benefits from grout-cli's by-hash dedup). --all deletes
            every root's state file.

Options:
  -r, --root PATH        Content root to walk (recursively finds file-bearing
                         directories). Required for run/retry/status; and for
                         reset unless --all.
  -d, --state-dir PATH   Where to keep state + per-directory manifests/logs.
                         Default: $GROUT_BULK_HOME or ~/.grout-bulk. Put this on
                         a persistent volume — the container filesystem is not.
  -s, --server URL       Grout server base URL. Passed through to grout-cli.
                         If omitted, grout-cli reads $GROUT_URL from the env.
      --grout-cli PATH   grout-cli executable (default: grout-cli, found on PATH)
  -j, --jobs N           Parallel directories in flight (default 1, recommended
                         max 4: hashing is CPU-bound, upload saturates one link).
      --progress-log PATH  Also append live progress to PATH: one line per file
                         as it lands (current file + running M/N file & dir
                         counts) plus per-directory start/done lines. Normal
                         stdout is unchanged. Inside a pod, point this at
                         /proc/1/fd/1 so progress reaches the container's PID 1
                         stdout and shows up in `kubectl logs` — plain stdout
                         from a `kubectl exec` session never gets there.
                         Env: GROUT_BULK_PROGRESS_LOG. Best-effort: an
                         unwritable sink warns once and never aborts the run.
      --retry-failed     For `run`: also re-run 'failed' directories, not just
                         pending/in_progress ones.
      --dry-run          Print the plan (which directories would run) and exit.
                         Dispatches nothing and writes no state.
      --json             For `status`: emit the state as JSON instead of a table.
      --all              For `reset`: delete every root's state file.
  -q, --quiet            Only print per-directory results and the final summary.
  -h, --help             Show this help.

Anything after `--` is forwarded verbatim to every grout-cli invocation, e.g.
extra tags, --kind, --channel, --group, --threshold-pct. Note: grout-bulk
always passes --no-wait itself; you don't need to (and shouldn't) --wait.

  grout-bulk run -r '/pinchflat-media/Content/Adam Neely' -s http://grout:8080 \\
    -d /mnt/grout-bulk -j 4 -- --kind=filler --tag=music

Directory discovery: every directory at or below --root that directly holds at
least one regular file becomes one upload unit, keyed by its path relative to
--root. For the common pinchflat layout (Content/<Creator>/<Year>/<files>) that
means one unit per year folder, and grout-cli's default grouping keys the shared
enrichment profile on the creator (the parent of each year leaf).
")

;; --------------------------------------------------------------------------
;; Pure helpers (unit-tested in test-bb/grout_bulk_test.clj)
;; --------------------------------------------------------------------------

(defn now-iso [] (str (OffsetDateTime/now)))

(defn- sha256-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" (bit-and (int %) 0xff)))
         (apply str))))

(defn slugify
  "Collapse a string into a filesystem-safe slug: lowercase, every run of
   non-alphanumerics becomes a single underscore, no leading/trailing ones.
   Empty input (or all-punctuation) yields \"root\"."
  [s]
  (let [slug (-> (str s)
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+|_+$" ""))]
    (if (str/blank? slug) "root" slug)))

(defn root-slug
  "Stable, human-readable, collision-resistant filename stem for a root path.
   A slug of the absolute path plus a short hash of it, so two roots that slug
   identically (differing only in punctuation) still get distinct state files."
  [abs-root]
  (str (slugify abs-root) "-" (subs (sha256-hex (str abs-root)) 0 8)))

(defn unit-key
  "The key a discovered directory is tracked under: its path relative to the
   root, using forward slashes. The root itself keys as \".\"."
  [abs-root abs-dir]
  (let [rel (str (fs/relativize abs-root abs-dir))]
    (if (str/blank? rel) "." rel)))

(defn classify-line
  "Bucket one parsed grout-cli --json per-file result, mirroring
   grout-cli's own classify-result. Returns :uploaded / :retagged /
   :existing / :failed, or nil for a line that isn't a per-file result
   (e.g. the trailing enrich-by-tag object)."
  [m]
  (cond
    (not (contains? m :file)) nil
    (:error m)                :failed
    (= "uploaded" (:action m)) :uploaded
    (= "retagged" (:action m)) :retagged
    :else                      :existing))

(defn tally-lines
  "Tally a seq of parsed grout-cli --json objects into per-directory counts.
   Derived from the per-file lines (not the summary object) so a directory
   that crashed before enrichment still tallies what actually happened."
  [objs]
  (reduce (fn [acc m]
            (if-let [b (classify-line m)]
              (-> acc (update b inc) (update :files inc))
              acc))
          {:uploaded 0 :retagged 0 :existing 0 :failed 0 :files 0}
          objs))

(defn parse-json-lines
  "Parse a grout-cli --json stdout blob into a vector of objects, skipping any
   blank or non-JSON lines defensively (grout-cli should only emit JSON on
   stdout in --json mode, but we never want a stray line to abort a run)."
  [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)
       (keep (fn [line]
               (try (json/parse-string line true)
                    (catch Exception _ nil))))
       vec))

(defn merge-units
  "Merge freshly discovered directories into the existing units map. Known
   directories keep their recorded status/counts; newly seen ones are added as
   {:status \"pending\"}. Directories no longer present on disk are retained
   (a path may just be temporarily unmounted) — never silently dropped."
  [existing discovered]
  (reduce (fn [acc [k dir]]
            (if (contains? acc k)
              acc
              (assoc acc k {:dir dir :status "pending"})))
          (or existing {})
          discovered))

(defn selectable?
  "Should a unit with `status` be processed by `command`?
     run   -> pending + in_progress (crash recovery), plus failed if retry-failed?
     retry -> failed + in_progress
   `done` is never re-selected; the user must `reset` to force a re-run."
  [command retry-failed? status]
  (case command
    "run"   (or (contains? #{"pending" "in_progress"} status)
                (and retry-failed? (= "failed" status)))
    "retry" (contains? #{"failed" "in_progress"} status)
    false))

(defn pending-keys
  "Ordered seq of unit keys to process for `command`, sorted for stable,
   resumable ordering."
  [units command retry-failed?]
  (->> units
       (filter (fn [[_ v]] (selectable? command retry-failed? (:status v))))
       (map key)
       sort))

(defn human-duration
  "Compact elapsed-time string between two ISO-8601 timestamps, e.g. '14m',
   '2h03m', '9s'. Returns \"\" when either endpoint is missing/unparseable."
  [start end]
  (try
    (if (and start end)
      (let [secs (.between ChronoUnit/SECONDS
                           (OffsetDateTime/parse start)
                           (OffsetDateTime/parse end))
            secs (max 0 secs)
            h (quot secs 3600)
            m (quot (mod secs 3600) 60)
            s (mod secs 60)]
        (cond
          (pos? h) (format "%dh%02dm" h m)
          (pos? m) (format "%dm%02ds" m s)
          :else    (format "%ds" s)))
      "")
    (catch Exception _ "")))

;; --------------------------------------------------------------------------
;; Progress logging (opt-in, best-effort)
;; --------------------------------------------------------------------------

(defn make-progress-logger
  "Return a progress-logging fn for `sink` — a path such as /proc/1/fd/1 (so
   lines land in the container's stdout and show up in `kubectl logs`), a plain
   file, or a fifo. When `sink` is nil/blank, returns a no-op: progress logging
   is opt-in and off by default.

   Why this exists: run interactively via `kubectl exec`, grout-bulk's normal
   stdout goes to the exec session, never to the container's PID 1 stdout — so
   `kubectl logs` shows nothing. Pointing --progress-log at /proc/1/fd/1
   mirrors progress onto PID 1's stdout, which the kubelet captures.

   Each call appends one timestamped, newline-terminated line
   (`<iso> [grout-bulk] <msg>`). Writes are serialized behind a lock (safe
   across the parallel worker threads) and best-effort: an unwritable sink
   never aborts a bulk run — it warns once to stderr, then stays silent.
   Mirrors the manifest writer's 'a logging problem must not lose work' stance."
  [sink]
  (if (str/blank? sink)
    (fn [_] nil)
    (let [lock   (Object.)
          warned (atom false)]
      (fn [msg]
        (locking lock
          (try
            (spit sink (str (now-iso) " [grout-bulk] " msg "\n") :append true)
            (catch Exception e
              (when (compare-and-set! warned false true)
                (binding [*out* *err*]
                  (println (str "warning: progress-log " sink " is unwritable ("
                                (or (ex-message e) (str e))
                                "); continuing without progress logging")))))))))))

;; --------------------------------------------------------------------------
;; Filesystem discovery
;; --------------------------------------------------------------------------

(defn discover-units
  "Every directory at or below `root` that directly contains at least one
   regular file, as a sorted vector of [unit-key absolute-dir-string]. This is
   the set of grout-cli --upload-dir invocations for the root."
  [root]
  (let [abs-root (str (fs/absolutize (fs/canonicalize root {:nofollow-links true})))]
    (->> (cons abs-root (map str (fs/glob abs-root "**" {:hidden true})))
         (filter #(fs/directory? %))
         (filter (fn [dir]
                   (some fs/regular-file? (fs/list-dir dir))))
         (map (fn [dir]
                (let [abs (str (fs/absolutize dir))]
                  [(unit-key abs-root abs) abs])))
         (sort-by first)
         vec)))

;; --------------------------------------------------------------------------
;; State file I/O (atomic)
;; --------------------------------------------------------------------------

(defn state-paths
  "Resolve the on-disk locations for a root under a state dir."
  [state-dir abs-root]
  (let [slug (root-slug abs-root)]
    {:slug     slug
     :state    (str (fs/path state-dir "roots" (str slug ".json")))
     :logs-dir (str (fs/path state-dir "logs" slug))}))

;; On disk, :units is a vector of records (each carrying its own :key), NOT a
;; JSON object keyed by the unit key. Unit keys are arbitrary relative paths
;; (e.g. "Adam Neely/2017"); as JSON object keys they'd be keywordized on read
;; and a "/" would be mangled into a namespaced keyword, silently corrupting the
;; key. Keeping them as record *values* lets us keywordize the (safe) record
;; field names freely. In memory we still use a string-keyed map for convenient
;; update-in during a run; these two adapters bridge the two representations.

(defn units->vec [units]
  (->> units (map (fn [[k v]] (assoc v :key k))) (sort-by :key) vec))

(defn vec->units [v]
  (into {} (map (fn [m] [(:key m) (dissoc m :key)]) v)))

(defn load-state [state-file]
  (when (fs/exists? state-file)
    (-> (json/parse-string (slurp state-file) true)
        (update :units vec->units))))

(defn write-state!
  "Atomically persist `state` to `state-file`: write a sibling .tmp then
   rename over the target (rename is atomic within a filesystem). Callers must
   serialize concurrent writers themselves; see the worker loop's lock."
  [state-file state]
  (fs/create-dirs (fs/parent state-file))
  (let [tmp  (str state-file ".tmp." (System/nanoTime))
        disk (update state :units units->vec)]
    (spit tmp (json/generate-string disk {:pretty true}))
    (fs/move tmp state-file {:replace-existing true :atomic-move true})))

;; --------------------------------------------------------------------------
;; Dispatch
;; --------------------------------------------------------------------------

(defn run-one!
  "Invoke grout-cli for a single directory, capturing its --json stdout to a
   per-directory .jsonl manifest and stderr to a .log. Returns the updated unit
   record (never throws — a subprocess failure becomes a :status \"failed\"
   record with the error captured)."
  [{:keys [grout-cli server logs-dir cli-extra on-file]} unit-key* dir]
  (let [manifest (str (fs/path logs-dir (str (slugify unit-key*) ".jsonl")))
        errlog   (str (fs/path logs-dir (str (slugify unit-key*) ".log")))
        started  (now-iso)
        ;; --no-wait: tell grout-cli not to make the *server* block on the LLM
        ;; for the per-directory enrichment. The server queues the enrichment for
        ;; its periodic worker and returns 202 at once, so grout-cli's trigger
        ;; call is fast and it exits promptly. Without this, each --upload-dir
        ;; call blocks for ~5-10 minutes on the LLM enrichment response, and a
        ;; 200-file run becomes ~10 hours of wall clock just waiting. The
        ;; Grout-side enrichment worker still runs the LLM call to completion.
        base     (cond-> [grout-cli "--upload-dir" dir "--json" "--no-wait"]
                   server (conj "--server" server))
        argv     (into base cli-extra)]
    (fs/create-dirs logs-dir)
    (try
      ;; Stream grout-cli's --json stdout line-by-line instead of capturing the
      ;; whole blob at once, so `on-file` can surface each file live (for
      ;; --progress-log) as grout-cli reports it — otherwise per-file progress
      ;; would only appear after the entire directory finished. We still rebuild
      ;; the exact stdout for the on-disk manifest and collect the parsed
      ;; objects for the tally, so nothing downstream changes.
      (let [proc  (p/process argv {:out :stream :err :string})
            sb    (StringBuilder.)
            objs  (with-open [rdr (io/reader (:out proc))]
                    (reduce (fn [acc raw]
                              (.append sb raw)
                              (.append sb "\n")
                              (let [line (str/trim raw)]
                                (if (str/blank? line)
                                  acc
                                  (if-let [m (try (json/parse-string line true)
                                                  (catch Exception _ nil))]
                                    (do (when on-file (on-file unit-key* m))
                                        (conj acc m))
                                    acc))))
                            []
                            (line-seq rdr)))
            {:keys [exit err]} @proc
            out    (.toString sb)
            tally  (tally-lines objs)
            enrich (some #(when (#{"enrich-by-tag" "dry-run-plan"} (:action %)) %) objs)]
        (spit manifest (or out ""))
        (spit errlog (or err ""))
        (merge {:dir dir
                :status (if (zero? exit) "done" "failed")
                :started_at started
                :finished_at (now-iso)
                :exit_code exit
                :profile_status (:profile-status enrich)
                :manifest manifest
                :log errlog
                :error (when-not (zero? exit)
                         (str "grout-cli exited " exit
                              (when (seq err)
                                (str ": " (last (remove str/blank? (str/split-lines err)))))))}
               tally))
      (catch Exception e
        (spit errlog (str (ex-message e) "\n"))
        {:dir dir :status "failed" :started_at started :finished_at (now-iso)
         :exit_code nil :error (str "failed to launch grout-cli: " (ex-message e))
         :manifest manifest :log errlog
         :uploaded 0 :retagged 0 :existing 0 :failed 0 :files 0}))))

;; --------------------------------------------------------------------------
;; Reporting
;; --------------------------------------------------------------------------

(defn summarize
  "Roll unit records up into per-status and per-count totals."
  [units]
  (let [vs (vals units)]
    {:total     (count vs)
     :done      (count (filter #(= "done" (:status %)) vs))
     :failed    (count (filter #(= "failed" (:status %)) vs))
     :pending   (count (filter #(= "pending" (:status %)) vs))
     :in_progress (count (filter #(= "in_progress" (:status %)) vs))
     :uploaded  (reduce + 0 (keep :uploaded vs))
     :retagged  (reduce + 0 (keep :retagged vs))
     :existing  (reduce + 0 (keep :existing vs))
     :files     (reduce + 0 (keep :files vs))}))

(defn print-status-table [root state]
  (let [units (:units state)
        s     (summarize units)]
    (println (format "%s — %s"
                     root (if (:updated_at state)
                            (str "last updated " (:updated_at state))
                            "no runs yet")))
    (println (format "  done: %d / %d   in_progress: %d   pending: %d   failed: %d"
                     (:done s) (:total s) (:in_progress s) (:pending s) (:failed s)))
    (println (format "  files: %d uploaded, %d retagged, %d existing"
                     (:uploaded s) (:retagged s) (:existing s)))
    (when (seq units)
      (println)
      (println "  directory                                     status       files            time")
      (doseq [k (sort (keys units))]
        (let [{:keys [status uploaded existing retagged failed files
                      started_at finished_at exit_code]} (get units k)
              counts (if files
                       (format "%3d (%du %dr %de%s)" files
                               (or uploaded 0) (or retagged 0) (or existing 0)
                               (if (and failed (pos? failed)) (str " " failed "F") ""))
                       "")
              dur    (human-duration started_at finished_at)
              extra  (if (and (= "failed" status) exit_code) (str "exit " exit_code) "")]
          (println (format "  %-44s %-12s %-16s %5s  %s"
                           (if (> (count k) 44) (str (subs k 0 41) "...") k)
                           status counts dur extra)))))
    (let [fails (->> units (filter #(= "failed" (:status (val %)))) (map key) sort)]
      (when (seq fails)
        (println)
        (println (format "  %d failed — see logs under %s ; `grout-bulk retry` to re-run"
                         (count fails) (:logs_dir state)))))))

;; --------------------------------------------------------------------------
;; Commands
;; --------------------------------------------------------------------------

(defn- require-root [opts]
  (let [root (:root opts)]
    (when (str/blank? root)
      (binding [*out* *err*] (println "error: --root is required"))
      (System/exit 2))
    (when-not (fs/directory? root)
      (binding [*out* *err*] (println (str "error: not a directory: " root)))
      (System/exit 2))
    (str (fs/absolutize (fs/canonicalize root {:nofollow-links true})))))

(defn cmd-run [command opts cli-extra]
  (let [abs-root  (require-root opts)
        state-dir (:state-dir opts)
        {:keys [state logs-dir]} (state-paths state-dir abs-root)
        retry?    (:retry-failed opts)
        quiet?    (:quiet opts)
        jobs      (max 1 (or (:jobs opts) 1))
        discovered (discover-units abs-root)
        prior     (load-state state)
        units0    (merge-units (:units prior) discovered)
        to-run    (pending-keys units0 command retry?)]
    (when (> jobs 4)
      (binding [*out* *err*]
        (println (format "warning: --jobs %d exceeds the recommended max of 4" jobs))))
    (when (empty? discovered)
      (binding [*out* *err*] (println (str "error: no file-bearing directories under " abs-root)))
      (System/exit 2))
    (if (:dry-run opts)
      (do
        (println (format "plan for %s (%d directories, %d to %s):"
                        abs-root (count units0) (count to-run) command))
        (doseq [k to-run] (println "  " k "  <-" (:dir (get units0 k))))
        (when (empty? to-run) (println "  (nothing to do — all directories are done)")))
      (let [;; In-memory source of truth; a monitor serializes disk writes and
            ;; the in_progress claim so parallel workers never clobber the file.
            state-atom (atom (merge (or prior {})
                                    {:root abs-root
                                     :root_slug (root-slug abs-root)
                                     :state_file state
                                     :logs_dir logs-dir
                                     :server (:server opts)
                                     :grout_cli (:grout-cli opts)
                                     :cli_args (vec cli-extra)
                                     :jobs jobs
                                     :created_at (or (:created_at prior) (now-iso))
                                     :units units0}))
            lock       (Object.)
            persist!   (fn [] (write-state! state (swap! state-atom assoc :updated_at (now-iso))))
            claim!     (fn [k] (locking lock
                                 (swap! state-atom assoc-in [:units k :status] "in_progress")
                                 (swap! state-atom assoc-in [:units k :started_at] (now-iso))
                                 (persist!)))
            record!    (fn [k rec] (locking lock
                                     (swap! state-atom assoc-in [:units k] rec)
                                     (persist!)))
            queue      (atom to-run)
            next!      (fn [] (locking lock (let [k (first @queue)]
                                              (when k (swap! queue rest)) k)))
            ;; Opt-in progress logging (see make-progress-logger). --progress-log
            ;; wins over $GROUT_BULK_PROGRESS_LOG; both absent => a no-op logger,
            ;; so nothing changes unless a sink is requested. `counter` tracks
            ;; files/dirs finished across all workers for the "M/N" heartbeat;
            ;; `files-total` is its denominator — regular files under just the
            ;; directories we're about to run (matches grout-cli's own per-dir set).
            progress-sink (or (not-empty (str (:progress-log opts)))
                              (not-empty (System/getenv "GROUT_BULK_PROGRESS_LOG")))
            progress!   (make-progress-logger progress-sink)
            files-total (reduce (fn [n k]
                                  (+ n (try (count (filter fs/regular-file?
                                                           (fs/list-dir (:dir (get units0 k)))))
                                            (catch Exception _ 0))))
                                0 to-run)
            counter     (atom {:files 0 :dirs 0})
            rel-file    (fn [abs] (try (str (fs/relativize abs-root abs))
                                       (catch Exception _ (str (fs/file-name abs)))))
            ;; Called per streamed grout-cli --json line; skips the trailing
            ;; enrich summary (classify-line -> nil) and emits one progress line
            ;; per actual file as it lands.
            on-file    (fn [_k m]
                         (when-let [bucket (classify-line m)]
                           (let [c    (swap! counter update :files inc)
                                 verb (case bucket
                                        :uploaded "uploaded"
                                        :retagged "retagged"
                                        :existing "exists"
                                        :failed   "FAILED")]
                             (progress! (format "%-8s %s  (%d/%d files, %d/%d dirs)"
                                                verb (rel-file (:file m))
                                                (:files c) files-total
                                                (:dirs c) (count to-run))))))
            ctx        {:grout-cli (:grout-cli opts) :server (:server opts)
                        :logs-dir logs-dir :cli-extra cli-extra :on-file on-file}
            worker     (fn [] (loop []
                                (when-let [k (next!)]
                                  (let [dir (:dir (get-in @state-atom [:units k]))]
                                    (claim! k)
                                    (progress! (format "start %s" k))
                                    (when-not quiet?
                                      (locking lock (println (format "-> %s" k))))
                                    (let [rec (run-one! ctx k dir)
                                          c   (swap! counter update :dirs inc)]
                                      (record! k rec)
                                      (progress! (format "done  %s  %du %dr %de%s  (%d/%d dirs, %d/%d files)"
                                                         k (:uploaded rec 0) (:retagged rec 0)
                                                         (:existing rec 0)
                                                         (if (pos? (:failed rec 0))
                                                           (str " " (:failed rec) "F") "")
                                                         (:dirs c) (count to-run)
                                                         (:files c) files-total))
                                      (locking lock
                                        (println (format "   %s  %s  (%du %dr %de%s)"
                                                        (:status rec) k
                                                        (:uploaded rec 0) (:retagged rec 0)
                                                        (:existing rec 0)
                                                        (if (pos? (:failed rec 0))
                                                          (str " " (:failed rec) "F") ""))))))
                                  (recur))))]
        (persist!) ; write the freshly-merged unit set before any work starts
        (when-not quiet?
          (println (format "%s: %d directories under root, %d to %s (jobs=%d)"
                          abs-root (count units0) (count to-run) command jobs)))
        (progress! (format "run %s: %s — %d dir(s), %d file(s) to process (jobs=%d)"
                          command abs-root (count to-run) files-total jobs))
        (if (empty? to-run)
          (println "nothing to do — all directories are done")
          ;; Explicit threads (not futures): a joined Thread is dead when we
          ;; return, so bb/the JVM exits promptly. The agent pool behind
          ;; `future` keeps non-daemon threads alive after the work is done and
          ;; would stall the CLI's exit.
          (let [threads (mapv (fn [_] (doto (Thread. ^Runnable worker) (.start)))
                              (range jobs))]
            (doseq [^Thread t threads] (.join t))))
        (let [s (summarize (:units @state-atom))]
          (progress! (format "run complete: %d/%d dirs done, %d failed, %d pending — files: %d uploaded, %d retagged, %d existing"
                            (:done s) (:total s) (:failed s) (:pending s)
                            (:uploaded s) (:retagged s) (:existing s)))
          (println)
          (println (format "done: %d/%d directories complete, %d failed, %d pending"
                          (:done s) (:total s) (:failed s) (:pending s)))
          (println (format "files: %d uploaded, %d retagged, %d existing"
                          (:uploaded s) (:retagged s) (:existing s)))
          (when (pos? (:failed s))
            (binding [*out* *err*]
              (println (format "%d directories failed — see %s ; `grout-bulk retry -r ...` to re-run"
                              (:failed s) logs-dir)))
            (System/exit 1)))))))

(defn cmd-status [opts]
  (let [abs-root  (require-root opts)
        {:keys [state logs-dir]} (state-paths (:state-dir opts) abs-root)
        prior     (load-state state)]
    (if-not prior
      (do (binding [*out* *err*]
            (println (format "no state for %s (nothing run yet under %s)" abs-root (:state-dir opts))))
          (System/exit 1))
      (if (:json opts)
        (println (json/generate-string (-> prior
                                            (assoc :summary (summarize (:units prior)))
                                            (update :units units->vec))
                                        {:pretty true}))
        (print-status-table abs-root (assoc prior :logs_dir logs-dir))))))

(defn cmd-reset [opts]
  (let [state-dir (:state-dir opts)
        roots-dir (str (fs/path state-dir "roots"))]
    (cond
      (:all opts)
      (let [files (when (fs/exists? roots-dir)
                    (filter #(str/ends-with? (str %) ".json") (fs/list-dir roots-dir)))]
        (doseq [f files] (fs/delete-if-exists f))
        (println (format "reset: deleted %d state file(s) under %s (logs kept)"
                        (count files) roots-dir)))

      (str/blank? (:root opts))
      (do (binding [*out* *err*]
            (println "error: reset needs --root PATH (or --all)"))
          (System/exit 2))

      :else
      (let [abs-root (str (fs/absolutize (fs/canonicalize (:root opts) {:nofollow-links true})))
            {:keys [state]} (state-paths state-dir abs-root)]
        (if (fs/delete-if-exists state)
          (println (format "reset: deleted %s (logs kept; re-run to rebuild)" state))
          (println (format "reset: no state file for %s" abs-root)))))))

;; --------------------------------------------------------------------------
;; Entry point
;; --------------------------------------------------------------------------

(def cli-spec
  {:root         {:alias :r}
   :state-dir    {:alias :d}
   :server       {:alias :s}
   :grout-cli    {:default "grout-cli"}
   :progress-log {}
   :jobs         {:alias :j :coerce :long :default 1}
   :retry-failed {:coerce :boolean}
   :dry-run      {:coerce :boolean}
   :json         {:coerce :boolean}
   :all          {:coerce :boolean}
   :quiet        {:alias :q :coerce :boolean}
   :help         {:alias :h :coerce :boolean}})

(defn default-state-dir []
  (or (not-empty (str (System/getenv "GROUT_BULK_HOME")))
      (str (fs/path (System/getProperty "user.home") ".grout-bulk"))))

(defn -main [argv]
  ;; Split off a trailing `-- <grout-cli args>` block before option parsing so
  ;; babashka.cli never sees the forwarded flags.
  (let [[mine cli-extra] (if-let [i (some (fn [[i a]] (when (= "--" a) i))
                                          (map-indexed vector argv))]
                           [(take i argv) (vec (drop (inc i) argv))]
                           [argv []])
        command (first mine)
        {:keys [opts]} (cli/parse-args (rest mine) {:spec cli-spec})
        opts    (update opts :state-dir #(or % (default-state-dir)))]
    (cond
      (or (:help opts) (nil? command) (#{"help" "--help" "-h"} command))
      (println usage)

      (#{"run" "retry"} command) (cmd-run command opts cli-extra)
      (= "status" command)       (cmd-status opts)
      (= "reset" command)        (cmd-reset opts)

      :else
      (do (binding [*out* *err*] (println (str "error: unknown command: " command)))
          (println usage)
          (System/exit 2)))))

;; Only run when executed as a script, not when loaded/required by a test.
(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
