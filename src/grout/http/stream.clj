(ns grout.http.stream
  "Byte-range HTTP streaming fallback (GROUT.md §7). PV's primary path is
   by-path off the shared mount; this endpoint serves remote/non-co-mounted
   callers and honours HTTP Range requests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grout.media.store :as store])
  (:import [java.io File FileInputStream InputStream]))

(def ^:private content-types
  {"mp4" "video/mp4" "m4v" "video/mp4" "webm" "video/webm"
   "mkv" "video/x-matroska" "mov" "video/quicktime"
   "avi" "video/x-msvideo" "ts" "video/mp2t"})

(defn- content-type [path]
  (let [ext (some-> (re-find #"\.([^.]+)$" path) second str/lower-case)]
    (get content-types ext "application/octet-stream")))

(defn parse-range
  "Parse a single 'bytes=' Range header into an inclusive [start end], clamped
   to `len`. Returns nil when there is no usable byte range, or :unsatisfiable
   when the requested start is past the end of the file."
  [header ^long len]
  (when (and header (str/starts-with? header "bytes="))
    (let [[s e] (str/split (subs header 6) #"-" 2)
          s (when (seq s) (parse-long s))
          e (when (seq e) (parse-long e))]
      (cond
        (and (nil? s) (nil? e)) nil
        (nil? s)                [(max 0 (- len e)) (dec len)]        ; suffix
        (>= s len)              :unsatisfiable
        (nil? e)                [s (dec len)]                        ; open-ended
        :else                   [s (min e (dec len))]))))

(defn- bounded-stream
  "Wrap `in` so it yields at most `limit` bytes, then reports EOF."
  ^InputStream [^InputStream in ^long limit]
  (let [remaining (atom limit)]
    (proxy [InputStream] []
      (read
        ([]
         (if (pos? ^long @remaining)
           (let [b (.read in)]
             (when (>= b 0) (swap! remaining dec))
             b)
           -1))
        ([buf off l]
         (let [rem (long @remaining)]
           (if (pos? rem)
             (let [n (.read in buf off (int (min (long l) rem)))]
               (when (pos? n) (swap! remaining - n))
               n)
             -1))))
      (available [] (int (min (long (.available in)) (long @remaining))))
      (close [] (.close in)))))

(defn stream-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters :as req}]
    (if-let [row (store/find-by-id ds id {:include-superseded? true})]
      (let [^File f (io/file (:path row))]
        (if-not (.exists f)
          {:status 404 :body {:error "Media file missing on disk"}}
          (let [len (.length f)
                ct  (content-type (:path row))
                rng (parse-range (get-in req [:headers "range"]) len)]
            (cond
              (= rng :unsatisfiable)
              {:status 416
               :headers {"Content-Range" (str "bytes */" len)
                         "Accept-Ranges" "bytes"}
               :body {:error "Requested range not satisfiable"}}

              (nil? rng)
              {:status 200
               :headers {"Content-Type" ct
                         "Content-Length" (str len)
                         "Accept-Ranges" "bytes"}
               :body f}

              :else
              (let [[start end] rng
                    length (inc (- end start))
                    in (FileInputStream. f)]
                (.position (.getChannel in) (long start))
                {:status 206
                 :headers {"Content-Type" ct
                           "Content-Length" (str length)
                           "Accept-Ranges" "bytes"
                           "Content-Range" (str "bytes " start "-" end "/" len)}
                 :body (bounded-stream in length)})))))
      {:status 404 :body {:error "Not found"}})))
