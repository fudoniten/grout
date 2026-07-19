(ns grout.media.accel-test
  "Unit tests for hardware-acceleration backend detection, resolution and
   ffmpeg argument construction. No ffmpeg/GPU is invoked — the host-detection
   result is passed in explicitly."
  (:require [clojure.test :refer [deftest is testing]]
            [grout.media.accel :as accel]))

(deftest accels-from-detection
  (testing "CPU-only host offers software only"
    (is (= #{:none} (accel/accels-from [] false))))
  (testing "Intel/AMD render node enables VAAPI"
    (is (= #{:none :vaapi} (accel/accels-from ["i915"] false)))
    (is (= #{:none :vaapi} (accel/accels-from ["amdgpu"] false)))
    (is (= #{:none :vaapi} (accel/accels-from ["unknown"] false))))
  (testing "NVIDIA device enables NVENC"
    (is (= #{:none :nvenc} (accel/accels-from ["nvidia"] true))))
  (testing "an NVIDIA-only render node does NOT advertise VAAPI"
    (is (= #{:none :nvenc} (accel/accels-from ["nvidia"] true)))
    (is (not (contains? (accel/accels-from ["nvidia"] false) :vaapi))))
  (testing "a mixed host exposes both"
    (is (= #{:none :nvenc :vaapi} (accel/accels-from ["i915" "nvidia"] true)))))

(deftest resolve-auto-prefers-hardware
  (is (= :nvenc (accel/resolve-accel :auto #{:none :nvenc})))
  (is (= :vaapi (accel/resolve-accel :auto #{:none :vaapi})))
  (is (= :nvenc (accel/resolve-accel :auto #{:none :nvenc :vaapi})) "NVENC preferred over VAAPI")
  (is (= :none (accel/resolve-accel :auto #{:none})) "software when no GPU"))

(deftest resolve-nil-and-string-default-to-auto
  (is (= :nvenc (accel/resolve-accel nil #{:none :nvenc})))
  (is (= :vaapi (accel/resolve-accel "auto" #{:none :vaapi}))))

(deftest resolve-explicit-none-forces-software
  (is (= :none (accel/resolve-accel :none #{:none :nvenc :vaapi})))
  (is (= :none (accel/resolve-accel "none" #{:none :vaapi}))))

(deftest resolve-honours-available-backend
  (is (= :nvenc (accel/resolve-accel :nvenc #{:none :nvenc})))
  (is (= :vaapi (accel/resolve-accel :vaapi #{:none :vaapi}))))

(deftest resolve-falls-back-when-backend-absent
  (testing "requested HW backend absent -> the other HW backend"
    (is (= :vaapi (accel/resolve-accel :nvenc #{:none :vaapi})))
    (is (= :nvenc (accel/resolve-accel :vaapi #{:none :nvenc}))))
  (testing "no HW at all -> software"
    (is (= :none (accel/resolve-accel :nvenc #{:none})))
    (is (= :none (accel/resolve-accel :vaapi #{:none})))))

(deftest software-args-unchanged
  (testing "no pre-input device flags and no filter for software"
    (is (= [] (accel/input-args :none)))
    (is (nil? (accel/video-filter :none))))
  (testing "software video encode is bare libx264 (default CRF)"
    (is (= ["-c:v" "libx264"] (accel/video-encode-args :none "h264")))))

(deftest nvenc-args
  (is (= ["-init_hw_device" "cuda=cu"
          "-hwaccel" "cuda" "-hwaccel_device" "cu" "-filter_hw_device" "cu"]
         (accel/input-args :nvenc)))
  (is (= "format=nv12,hwupload_cuda" (accel/video-filter :nvenc)))
  (let [args (accel/video-encode-args :nvenc "h264")]
    (is (= ["-c:v" "h264_nvenc"] (take 2 args)))
    (is (some #{"vbr"} args))))

(deftest vaapi-args
  (is (= ["-init_hw_device" "vaapi=va:/dev/dri/renderD128"
          "-hwaccel" "vaapi" "-hwaccel_device" "va" "-filter_hw_device" "va"]
         (accel/input-args :vaapi)))
  (is (= ["-init_hw_device" "vaapi=va:/dev/dri/renderD200"
          "-hwaccel" "vaapi" "-hwaccel_device" "va" "-filter_hw_device" "va"]
         (accel/input-args :vaapi "/dev/dri/renderD200"))
      "device is configurable")
  (is (= "format=nv12,hwupload" (accel/video-filter :vaapi)))
  (let [args (accel/video-encode-args :vaapi "h264")]
    (is (= ["-c:v" "h264_vaapi"] (take 2 args)))
    (is (some #{"CQP"} args))))

(deftest encoder-map-covers-hevc
  (is (= "hevc_nvenc" (second (accel/video-encode-args :nvenc "hevc"))))
  (is (= "hevc_vaapi" (second (accel/video-encode-args :vaapi "h265")))))
