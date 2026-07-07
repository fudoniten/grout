(ns grout.http.media
  "HTTP handlers for the media API. Each constructor closes over the media store
   component ({:ds ... :media-dir ...}) and returns a ring handler."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grout.media.enrich :as enrich]
            [grout.media.intake :as intake]
            [grout.media.store :as store]
            [taoensso.timbre :as log]))

(defn- stream-url [id]
  (str "/grout/media/" id "/stream"))

(defn- row->summary [row]
  {:id (:id row)
   :name (:name row)
   :duration-ms (:duration_ms row)
   :path (:path row)
   :stream-url (stream-url (:id row))
   :vcodec (:vcodec row)
   :acodec (:acodec row)
   :tags (vec (:tags row))})

(defn- row->full [row]
  {:id (:id row)
   :kind (:kind row)
   :path (:path row)
   :name (:name row)
   :description (:description row)
   :channel (:channel row)
   :tags (vec (:tags row))
   :duration-ms (:duration_ms row)
   :width (:width row)
   :height (:height row)
   :vcodec (:vcodec row)
   :acodec (:acodec row)
   :source (:source row)
   :source-url (:source_url row)
   :enriched (:enriched row)
   :content-hash (:content_hash row)
   :stream-url (stream-url (:id row))
   :created-at (some-> (:created_at row) str)
   :superseded-at (some-> (:superseded_at row) str)})

(defn- parse-tags
  "Split a comma-separated tag string into a vector, trimming and dropping
   blanks. Returns nil for a nil/blank input."
  [s]
  (when (some? s)
    (let [ts (->> (str/split s #",")
                  (map str/trim)
                  (remove str/blank?)
                  vec)]
      (when (seq ts) ts))))

(def ^:private not-found
  {:status 404 :body {:error "Not found"}})

(def ^:private valid-kinds #{"bumper" "filler" "program"})

(defn intake-handler
  "POST /grout/media is a multipart/form-data upload — the caller need not
   share a filesystem with the server (CLI clients, Tunarr Scheduler, etc. all
   push bytes over HTTP). The uploaded file is spooled to a temp file by
   ring's multipart middleware; that temp path is handed to the same
   hash/probe/normalize/insert pipeline path-based callers used, then removed."
  [media]
  (fn [{:keys [multipart-params]}]
    (let [{:strs [file kind channel tags source source-url name description]} multipart-params
          tempfile (:tempfile file)]
      (cond
        (not (map? file))
        {:status 400 :body {:error "No file uploaded (expected multipart field `file`)"}}

        (not (contains? valid-kinds kind))
        {:status 400 :body {:error "kind is required and must be one of bumper, filler, program"}}

        :else
        (try
          (let [req (cond-> {:path (.getAbsolutePath ^java.io.File tempfile)
                             :kind kind
                             :tags (vec (or (parse-tags tags) []))}
                      (not (str/blank? channel))     (assoc :channel channel)
                      (not (str/blank? source))      (assoc :source source)
                      (not (str/blank? source-url))  (assoc :source-url source-url)
                      (not (str/blank? name))        (assoc :name name)
                      (not (str/blank? description))  (assoc :description description))
                {:keys [row deduplicated]} (intake/intake! media req)]
            {:status (if deduplicated 200 201)
             :body (row->full row)})
          (catch clojure.lang.ExceptionInfo e
            (log/error e "Intake failed" (ex-data e))
            {:status 422 :body {:error (ex-message e)}})
          (finally
            (when (and tempfile (.exists ^java.io.File tempfile))
              (io/delete-file tempfile true))))))))

(defn get-by-hash-handler [{:keys [ds]}]
  (fn [{{{:keys [hash]} :path} :parameters}]
    (if-let [row (store/find-by-hash ds hash)]
      {:status 200 :body (row->full row)}
      not-found)))

(defn query-handler [{:keys [ds]}]
  (fn [{{q :query} :parameters}]
    (let [params {:channel (:channel q)
                  :tags    (parse-tags (:tags q))
                  :min-ms  (:min_ms q)
                  :max-ms  (:max_ms q)
                  :kind    (:kind q)
                  :limit   (or (:limit q) 10)
                  :random  (boolean (:random q))}
          rows (store/query ds params)]
      {:status 200
       :body {:count (count rows)
              :items (mapv row->summary rows)}})))

(defn get-one-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (store/find-by-id ds id)]
      {:status 200 :body (row->full row)}
      not-found)))

(defn patch-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path body :body} :parameters}]
    (let [patch (select-keys body [:name :description :channel :tags])]
      (if (empty? patch)
        {:status 400 :body {:error "No mutable fields provided"}}
        (if-let [row (store/update-metadata! ds id patch)]
          {:status 200 :body (row->full row)}
          not-found)))))

(defn delete-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path {:keys [hard]} :query} :parameters}]
    (if hard
      (if-let [row (store/hard-delete! ds id)]
        (do (when-let [p (:path row)]
              (try (io/delete-file p true)
                   (catch Exception e
                     (log/warn e "Failed to unlink media file" {:path p}))))
            {:status 200 :body {:deleted true :hard true}})
        not-found)
      (if (store/soft-delete! ds id)
        {:status 200 :body {:deleted true :hard false}}
        not-found))))

(defn get-tags-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (store/find-by-id ds id {:include-superseded? true})]
      {:status 200 :body {:tags (vec (:tags row))}}
      not-found)))

(defn add-tag-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path {:keys [tag]} :body} :parameters}]
    (if-let [row (store/add-tag! ds id tag)]
      {:status 201 :body {:tags (vec (:tags row))}}
      not-found)))

(defn enrich-handler [{:keys [ds] :as media}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (enrich/enrich-one! ds (:tunabrain media) (:dim-config media) id)]
      {:status 200 :body (row->full row)}
      (if (store/find-by-id ds id {:include-superseded? true})
        {:status 502 :body {:error "Enrichment failed or produced no metadata"}}
        not-found))))
