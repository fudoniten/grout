(ns grout.media.hash-test
  (:require [clojure.test :refer [deftest is]]
            [grout.media.hash :as hash]))

(deftest sha256-matches-known-vector
  (let [f (java.io.File/createTempFile "grout-hash" ".bin")]
    (try
      (spit f "abc")
      ;; sha256("abc") — the standard NIST test vector
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (hash/sha256-file (.getPath f))))
      (finally (.delete f)))))

(deftest content-path-is-sharded
  (is (= "/data/media/grout/ba/ba7816bf.mp4"
         (hash/content-path "/data/media/grout" "ba7816bf"))))

(deftest copy-and-hash-matches-sha256-file
  (let [f (java.io.File/createTempFile "grout-hash" ".bin")]
    (try
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (hash/copy-and-hash! (java.io.ByteArrayInputStream. (.getBytes "abc")) f)))
      (is (= "abc" (slurp f)))
      (finally (.delete f)))))

;; Shared test vectors: test-bb/grout_cli_test.clj asserts the CLI's
;; fingerprint-file yields the same values. If one changes, both must.
(defn- write-pattern-file
  "A file of `n` bytes where byte i is (i mod 251)."
  [n]
  (let [f (java.io.File/createTempFile "grout-fp" ".bin")]
    (with-open [o (java.io.FileOutputStream. f)]
      (.write o (byte-array (map #(unchecked-byte (mod % 251)) (range n)))))
    f))

(deftest fingerprint-vectors
  (doseq [[n expected] [[3 "fp1:976162828ad3ba7f1c0c6253d65b546ee2ea03a4fd3e8d9e84d61706bac0bce8"]
                        [1000000 "fp1:1dd735f6779c0b12387c59a61b31276173092c692e950f846cb482580837719b"]]]
    (let [f (write-pattern-file n)]
      (try (is (= expected (hash/fingerprint-file (.getPath f))) (str n " bytes"))
           (finally (.delete f))))))

(deftest fingerprint-ignores-unsampled-bytes-but-not-size
  (let [f (write-pattern-file 1000000)]
    (try
      (let [before (hash/fingerprint-file (.getPath f))]
        ;; a byte between the start and middle samples
        (with-open [raf (java.io.RandomAccessFile. f "rw")]
          (.seek raf 200000) (.write raf 255))
        (is (= before (hash/fingerprint-file (.getPath f))) "outside every sample")
        (with-open [raf (java.io.RandomAccessFile. f "rw")]
          (.seek raf (quot 1000000 2)) (.write raf 255))
        (is (not= before (hash/fingerprint-file (.getPath f))) "inside the middle sample")
        (with-open [raf (java.io.RandomAccessFile. f "rw")]
          (.setLength raf 999999))
        (is (not= before (hash/fingerprint-file (.getPath f))) "size change"))
      (finally (.delete f)))))
