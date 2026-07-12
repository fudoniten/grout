#!/usr/bin/env bb
;; grout-cli — upload/tag filler media on a Grout server.
;;
;; No filesystem is shared with the server: this CLI hashes the file locally
;; with the same algorithm the server uses for its content-hash dedup key
;; (SHA-256 of the raw bytes, matching sha256sum) and looks it up via
;; GET /grout/by-hash/:hash. If the file is already stored, only the tags are
;; added (idempotent); otherwise the file's bytes are uploaded as a
;; multipart/form-data POST to /grout/media, which hashes/probes/normalizes
;; and stores it server-side.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(import '[java.security MessageDigest])

(def usage "
grout-cli — upload/tag filler media on a Grout server

Usage:
  grout-cli [options] <file> [<file> ...]
  grout-cli --upload-dir DIR [options]

Options:
  -s, --server=URL       Grout server base URL (or set GROUT_URL)
      --tag=TAG          Add a tag (repeatable: --tag=a --tag=b)
      --tags=A,B,C       Add a comma-separated list of tags
      --kind=KIND        bumper | filler | program (default: filler)
      --channel=NAME     Owning channel; omit for generic/any-channel filler
      --source=NAME      Provenance label (default: upload)
      --source-url=URL   Origin URL for orphan/web content
      --name=STR         Title (only sensible for a single file)
      --description=STR  Description (only sensible for a single file)
      --no-filename-tag  Don't auto-add the default filename:<name> tag
      --dry-run          Hash and look up each file; don't upload or tag
      --json             Emit one JSON result object per line
  -v, --verbose          Print request details to stderr
  -h, --help             Show this help

Directory-upload mode (--upload-dir):
      --upload-dir=DIR   Upload every file in DIR (non-recursive), tagging each
                         with parent-directory:<normalized> + content-type:<kind>,
                         then trigger one shared directory-level enrichment.
      --group=NAME       Grouping name the shared profile is keyed on, instead of
                         the upload dir's parent directory. Point --upload-dir at
                         a year/subdir and pass --group='Adam Neely Music' so every
                         subdir under a creator shares one parent-directory:adam-neely-music
                         profile. Defaults to the parent of --upload-dir's leaf.
      --wait             Block on the enrichment call (default: fire-and-forget)
      --threshold-pct=N  Re-enrich only if the item count grew >N% (default 20)

By default every file also gets a `filename:<basename>` tag, so the original
filename is always searchable even after enrichment renames it.

Examples:
  grout-cli --tags=daytime,fun bumper1.mp4
  GROUT_URL=http://grout:8080 grout-cli --tag=kids --kind=filler *.mp4
  grout-cli --upload-dir '/pinchflat-media/Content/Adam Neely Music/'
")

(def cli-spec
  {:server        {:alias :s}
   :tag           {:collect []}
   :tags          {}
   :kind          {:default "filler"}
   :channel       {}
   :source        {:default "upload"}
   :source-url    {}
   :name          {}
   :description   {}
   :upload-dir    {}
   :group         {}
   :wait          {:coerce :boolean}
   :threshold-pct {}
   :no-filename-tag {:coerce :boolean}
   :dry-run       {:coerce :boolean}
   :json          {:coerce :boolean}
   :verbose       {:alias :v :coerce :boolean}
   :help          {:alias :h :coerce :boolean}})

(defn- parse-argv
  "Manually split argv into option tokens (anything starting with - or --,
   consuming an attached =value or the following token as its value) and
   positional file arguments. Using a hand-rolled pass instead of
   babashka.cli's collecting parser because :collect greedily swallows
   following positional args when options and files are interleaved."
  [argv]
  (loop [args argv, opt-tokens [], files []]
    (if-let [a (first args)]
      (cond
        (str/starts-with? a "--")
        (if (str/includes? a "=")
          (recur (rest args) (conj opt-tokens a) files)
          (let [flag (subs a 2)
                boolean-flag? (contains? #{"no-filename-tag" "dry-run" "json" "verbose" "help" "wait"} flag)]
            (if boolean-flag?
              (recur (rest args) (conj opt-tokens a) files)
              (recur (rest (rest args)) (conj opt-tokens a (second args)) files))))

        (and (str/starts-with? a "-") (not= a "-") (> (count a) 1) (not (Character/isDigit (.charAt ^String a 1))))
        (let [flag (subs a 1)
              boolean-flag? (contains? #{"v" "h"} flag)]
          (if boolean-flag?
            (recur (rest args) (conj opt-tokens a) files)
            (recur (rest (rest args)) (conj opt-tokens a (second args)) files)))

        :else
        (recur (rest args) opt-tokens (conj files a)))
      {:opt-tokens opt-tokens :files files})))

(defn- sha256-file
  "Lowercase hex SHA-256 of `path`'s bytes. Matches grout.media.hash/sha256-file
   (and plain sha256sum) so dedup lookups agree with the server."
  [path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (java.io.FileInputStream. (fs/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest md)))))

(defn- split-tags [s]
  (when (some? s)
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?))))

(defn normalize-dir-name
  "Normalize a directory name into the `parent-directory:` tag suffix (B1,
   aggressive): lowercase, collapse every run of non-alphanumerics to a single
   hyphen, strip leading/trailing hyphens. E.g. 'Adam Neely Music' ->
   'adam-neely-music', '80s & 90s' -> '80s-90s', 'Tom Scott (extra)' ->
   'tom-scott-extra'. The original name is preserved as the profile concept."
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn grouping-concept
  "The concept name the shared directory profile is keyed on.

  `--group` wins when given, so that sibling subdirectories under one creator
  (e.g. year folders) share a single profile even when their leaves differ.

  Without `--group`, the default is the **parent** of `--upload-dir`'s leaf
  — not the leaf itself. This matches the common case of pointing
  `--upload-dir` at a year subdir (e.g. `.../Adam Neely Music/2024`) where
  the leaf is a year that would otherwise be unshareable across years and
  collide with any other creator's year folders. The parent
  (`Adam Neely Music`) is the meaningful group label; the leaf
  (`2024`) is just a batch boundary.

  Edge cases: a path with no meaningful parent (e.g. `/Filler` — the parent
  is the filesystem root) falls back to the leaf. Passing an explicit
  `--group=''` is treated the same as omitting `--group`."
  [group dir]
  (or (some-> group str not-empty)
      (let [abs    (fs/absolutize (fs/normalize dir))
            leaf   (str (fs/file-name abs))
            parent-dir  (str (fs/parent abs))
            parent-leaf (str (fs/file-name (fs/parent abs)))]
        (if (and (not= parent-dir "/")
                 (not= parent-leaf leaf))
          parent-leaf
          leaf))))

(defn- base-url [opts]
  (let [server (or (:server opts) (System/getenv "GROUT_URL"))]
    (when (str/blank? server)
      (binding [*out* *err*]
        (println "error: no Grout server given (pass --server, -s, or set GROUT_URL)"))
      (System/exit 2))
    (str/replace server #"/+$" "")))

(defn- json-headers [] {"Content-Type" "application/json" "Accept" "application/json"})

(defn- http-get [url verbose?]
  (when verbose? (binding [*out* *err*] (println "GET" url)))
  (let [resp (http/get url {:headers {"Accept" "application/json"} :throw false})]
    (assoc resp :json (when (seq (:body resp)) (json/parse-string (:body resp) true)))))

(defn- http-post [url body verbose?]
  (when verbose? (binding [*out* *err*] (println "POST" url (json/generate-string body))))
  (let [resp (http/post url {:headers (json-headers)
                             :body (json/generate-string body)
                             :throw false})]
    (assoc resp :json (when (seq (:body resp)) (json/parse-string (:body resp) true)))))

(defn- http-post-multipart [url file-path parts verbose?]
  (when verbose? (binding [*out* *err*] (println "POST (multipart)" url parts)))
  (let [file-parts (map (fn [[k v]] {:name (name k) :content v}) parts)
        resp (http/post url
                        {:multipart (conj (vec file-parts)
                                          {:name "file"
                                           :content (fs/file file-path)
                                           :filename (str (fs/file-name file-path))})
                         :headers {"Accept" "application/json"}
                         :throw false})]
    (assoc resp :json (when (seq (:body resp)) (json/parse-string (:body resp) true)))))

(defn- by-hash [server hash verbose?]
  (http-get (str server "/grout/by-hash/" hash) verbose?))

(defn- add-tag! [server id tag verbose?]
  (http-post (str server "/grout/media/" id "/tags") {:tag tag} verbose?))

(defn- upload! [server file-path parts verbose?]
  (http-post-multipart (str server "/grout/media") file-path parts verbose?))

(defn- result! [json? m]
  (if json?
    (println (json/generate-string m))
    (let [{:keys [file status id action tags error]} m]
      (if error
        (println (str file ": ERROR " error))
        (println (str file ": " (name action)
                      " id=" id
                      " status=" status
                      " tags=" (str/join "," tags)))))))

(defn- process-file! [server opts tags file]
  (if-not (fs/exists? file)
    {:file file :error "file not found"}
    (let [filename-tag (str "filename:" (fs/file-name file))
          all-tags (vec (distinct (cond-> tags
                                     (not (:no-filename-tag opts)) (conj filename-tag))))
          hash (sha256-file file)
          verbose? (:verbose opts)
          existing (by-hash server hash verbose?)]
      (cond
        (= 200 (:status existing))
        (let [id (:id (:json existing))]
          (if (:dry-run opts)
            {:file file :action :would-retag :id id :status 200 :tags all-tags :hash hash}
            ;; Already stored: add only the missing tags. `add-tag!` is
            ;; idempotent server-side, so a re-run with the tags already present
            ;; is a clean no-op (:unchanged), never a failure. Tag-add errors are
            ;; reported as their own :retag-failed action, distinct from an upload
            ;; failure, and don't abort the remaining tags for the file.
            (let [current (set (:tags (:json existing)))
                  to-add  (remove current all-tags)
                  failed  (vec (keep (fn [tag]
                                       (let [resp (add-tag! server id tag verbose?)]
                                         (when-not (#{200 201} (:status resp))
                                           {:tag tag :status (:status resp)})))
                                     to-add))]
              (cond
                (seq failed)
                {:file file :action :retag-failed :id id :status 200 :hash hash
                 :error (str "failed to add tag(s): "
                             (str/join ", " (map #(str (:tag %) " (status " (:status %) ")")
                                                 failed)))}

                (seq to-add)
                {:file file :action :retagged :id id :status 200
                 :tags (vec (distinct (concat current all-tags))) :hash hash}

                :else
                {:file file :action :unchanged :id id :status 200
                 :tags (vec current) :hash hash}))))

        (= 404 (:status existing))
        (if (:dry-run opts)
          {:file file :action :would-upload :status 404 :tags all-tags :hash hash}
          (let [parts (cond-> {:kind (:kind opts)
                               :tags (str/join "," all-tags)
                               :source (:source opts)}
                        (:channel opts)     (assoc :channel (:channel opts))
                        (:source-url opts)  (assoc :source-url (:source-url opts))
                        (:name opts)        (assoc :name (:name opts))
                        (:description opts) (assoc :description (:description opts)))
                resp (upload! server file parts verbose?)]
            (if (#{200 201} (:status resp))
              {:file file :action (if (= 201 (:status resp)) :uploaded :deduplicated)
               :id (:id (:json resp)) :status (:status resp)
               :tags (:tags (:json resp)) :hash hash}
              {:file file :error (str "upload failed: " (or (:error (:json resp)) (:body resp)))
               :status (:status resp)})))

        :else
        {:file file :error (str "unexpected by-hash status " (:status existing))}))))

(defn- enrich-by-tag! [server tag concept-name opts]
  (let [body (cond-> {:concept-name concept-name}
               (:wait opts)          (assoc :wait true)
               (:threshold-pct opts) (assoc :threshold-pct (parse-long (str (:threshold-pct opts)))))]
    (http-post (str server "/grout/enrich-by-tag/" tag) body (:verbose opts))))

(defn classify-result
  "Bucket a `process-file!` result for the --upload-dir tally. Truthful
   reporting: a file is only `:failed` when it actually errored (an upload 5xx,
   a not-found, a tag-add failure) — never merely because it was already stored.
     :failed   — the file errored
     :uploaded — bytes newly stored (201)
     :retagged — an already-stored item gained >=1 tag
     :existing — already stored and already fully tagged (a no-op), or a
                 server-side dedup match on upload"
  [result]
  (cond
    (:error result)                :failed
    (= :uploaded (:action result)) :uploaded
    (= :retagged (:action result)) :retagged
    :else                          :existing))

(defn- upload-dir!
  "Directory-upload mode: tag every file in the directory with
   parent-directory:<normalized> + content-type:<kind>, upload the new ones,
   then trigger one shared directory-level enrichment for the group."
  [opts]
  (let [server    (base-url opts)
        dir       (:upload-dir opts)]
    (when-not (fs/directory? dir)
      (binding [*out* *err*] (println (str "error: not a directory: " dir)))
      (System/exit 2))
    (let [concept    (grouping-concept (:group opts) dir)
          norm       (normalize-dir-name concept)
          pd-tag     (str "parent-directory:" norm)
          ct-tag     (str "content-type:" (:kind opts))
          base-tags  (vec (distinct (concat [pd-tag ct-tag]
                                            (:tag opts []) (split-tags (:tags opts)))))
          files      (->> (fs/list-dir dir) (filter fs/regular-file?) (map str) sort)
          json?      (:json opts)
          tally      (atom {:uploaded 0 :retagged 0 :existing 0 :failed 0})]
      (when (empty? files)
        (binding [*out* *err*] (println (str "error: no files in " dir)))
        (System/exit 2))
      (doseq [file files]
        (let [result (try (process-file! server opts base-tags file)
                          (catch Exception e {:file file :error (or (ex-message e) (str e))}))]
          (swap! tally update (classify-result result) inc)
          (result! json? result)))
      ;; Fire the shared enrichment for the whole directory (unless a dry run).
      (let [{:keys [uploaded retagged existing failed]} @tally]
        (if (:dry-run opts)
          (if json?
            (println (json/generate-string {:action "dry-run-plan" :tag pd-tag
                                             :concept concept :files (count files)}))
            (println (format "dry-run: would enrich %s (%s files) as tag %s"
                             concept (count files) pd-tag)))
          (let [resp    (enrich-by-tag! server pd-tag concept opts)
                pstatus (get-in resp [:json :status] "unknown")]
            (if json?
              (println (json/generate-string {:action "enrich-by-tag" :tag pd-tag
                                              :concept concept :profile-status pstatus
                                              :uploaded uploaded :retagged retagged
                                              :existing existing :failed failed}))
              (println (format "%s: uploaded=%s retagged=%s existing=%s failed=%s profile-status=%s tag=%s"
                              concept uploaded retagged existing failed pstatus pd-tag)))))
        (when (pos? failed) (System/exit 1))))))

(defn -main [argv]
  (let [{:keys [opt-tokens files]} (parse-argv argv)
        {:keys [opts]} (cli/parse-args opt-tokens {:spec cli-spec})]
    (cond
      (:help opts)
      (println usage)

      (:upload-dir opts)
      (upload-dir! opts)

      (empty? files)
      (do (println usage)
          (binding [*out* *err*] (println "error: no media files given"))
          (System/exit 2))

      :else
      (let [server (base-url opts)
            tags (vec (distinct (concat (:tag opts []) (split-tags (:tags opts)))))
            failures (atom 0)]
        (doseq [file files]
          (let [result (try
                         (process-file! server opts tags file)
                         (catch Exception e
                           {:file file :error (or (ex-message e) (str e))}))]
            (when (:error result) (swap! failures inc))
            (result! (:json opts) result)))
        (when (pos? @failures)
          (System/exit 1))))))

;; Only run when executed as a script, not when loaded/required by a test.
(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
