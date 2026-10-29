(ns grout.media.hash
  "Content hashing for dedup / idempotent intake. Hashes the ORIGINAL source
   bytes (not the normalized output) so a client can hash a local file and ask
   Grout whether an upload is needed."
  (:require [clojure.java.io :as io])
  (:import [java.security DigestInputStream MessageDigest]))

(defn- hex [^bytes digest]
  (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))

(defn sha256-file
  "Return the lowercase hex SHA-256 of the file at `path`. Matches sha256sum."
  [path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (io/input-stream (io/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (hex (.digest md))))

(defn copy-and-hash!
  "Copy `in` to file `out` and return the SHA-256 (as `sha256-file` would) of
   the bytes written, computed in the same pass. Upload spooling uses this so
   intake doesn't read the whole file back just to hash it."
  [^java.io.InputStream in out]
  ;; `in` is the caller's (e.g. the multipart part's) stream: read it through
  ;; the digest but leave closing it to the caller, as io/copy would.
  (let [md (MessageDigest/getInstance "SHA-256")]
    (io/copy (DigestInputStream. in md) (io/file out))
    (hex (.digest md))))

(def fingerprint-chunk-bytes
  "Size of each sampled region in `fingerprint-file`."
  (* 64 1024))

(defn- fingerprint-regions
  "[offset length] pairs a fingerprint samples for a file of `size` bytes: the
   start, middle and end chunks, or the whole file when it is too small to
   hold three distinct chunks."
  [size]
  (let [c fingerprint-chunk-bytes]
    (if (<= size (* 3 c))
      [[0 size]]
      [[0 c] [(- (quot size 2) (quot c 2)) c] [(- size c) c]])))

(defn fingerprint-file
  "A cheap content fingerprint for `path`: \"fp1:\" + hex SHA-256 over the
   file size and three 64 KiB samples (start, middle, end). It reads at most
   192 KiB whatever the file size, so a client can ask \"is this stored?\"
   without a full hash. Re-encodes, remuxes and truncated downloads all change
   the size or a sampled region; two different media files matching on all of
   them is vanishingly rare, but possible, so `content-hash` (the full SHA-256)
   stays the authoritative dedup key server-side.

   Must stay byte-for-byte in sync with `fingerprint-file` in
   bin/grout-cli.bb; both are checked against the same test vector. Bump the
   `fp1:` prefix if the algorithm ever changes."
  [path]
  (let [f    (io/file path)
        size (.length f)
        md   (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str size "\n") "UTF-8"))
    (with-open [raf (java.io.RandomAccessFile. f "r")]
      (doseq [[off len] (fingerprint-regions size)]
        (let [buf (byte-array len)]
          (.seek raf off)
          (.readFully raf buf)
          (.update md buf))))
    (str "fp1:" (hex (.digest md)))))

(defn content-path
  "Content-addressed storage path for `hash` under `base-dir`:
   <base-dir>/<ab>/<hash>.mp4 (sharded by the first two hex chars)."
  [base-dir hash]
  (str base-dir "/" (subs hash 0 2) "/" hash ".mp4"))
