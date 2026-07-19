(ns grout.media.accel
  "Hardware-acceleration backend selection for the intake transcode
   (GROUT.md §8).

   Grout's transcode is a one-shot file->file normalize, not a live HLS
   encode, so this is a deliberately trimmed-down cousin of Pseudovision's
   `pseudovision.ffmpeg.profile`: it borrows that service's proven
   host-probe + graceful-fallback model but drops everything HLS-specific
   (GOP tuning, segment timing, rate-control presets). The intake pipeline
   already stream-copies conforming files (see `probe/remux-args`), so this
   only shapes the genuinely off-profile transcode.

   Three backends: software (`:none`), NVIDIA NVENC (`:nvenc`), and VAAPI
   (`:vaapi`, Intel/AMD). Detection is a cheap filesystem probe of the render
   nodes and the NVIDIA device node; when a requested backend is unavailable
   on this host we fall back (to the other hardware backend, then software)
   with a warning, so the same image runs unchanged on GPU and CPU-only
   nodes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]))

(def default-vaapi-device
  "Default DRM render node used to init the VAAPI device."
  "/dev/dri/renderD128")

(def ^:private video-codec-map
  "Logical codec name -> per-accel FFmpeg encoder."
  {"h264" {:none "libx264" :nvenc "h264_nvenc" :vaapi "h264_vaapi"}
   "hevc" {:none "libx265" :nvenc "hevc_nvenc" :vaapi "hevc_vaapi"}
   "h265" {:none "libx265" :nvenc "hevc_nvenc" :vaapi "hevc_vaapi"}})

;; ---------------------------------------------------------------------------
;; Host detection (mirrors pseudovision.ffmpeg.profile)
;; ---------------------------------------------------------------------------

(defn- file-exists? [^String path]
  (.exists (File. path)))

(defn- render-node-drivers
  "DRM driver backing each present /dev/dri/renderD* node, e.g. \"i915\",
   \"amdgpu\", \"nvidia\". A node whose driver can't be read yields
   \"unknown\" so it is still treated as potentially VAAPI-capable."
  []
  (keep (fn [n]
          (when (file-exists? (str "/dev/dri/renderD" n))
            (let [ue (io/file (str "/sys/class/drm/renderD" n "/device/uevent"))]
              (or (when (.exists ue)
                    (some #(second (re-find #"^DRIVER=(.+)$" (str/trim %)))
                          (str/split-lines (slurp ue))))
                  "unknown"))))
        (range 128 144)))

(defn accels-from
  "Pure decision: given the DRM render-node drivers and whether an NVIDIA
   device node is present, which accel backends are available.

   VAAPI requires a render node that is NOT nvidia-backed (an iHD/AMD VAAPI
   stack cannot drive an NVIDIA node). NVENC requires an actual /dev/nvidia*
   device (the NVIDIA DRM render node alone, without the injected userspace
   driver, cannot encode)."
  [render-drivers nvidia-device?]
  (cond-> #{:none}
    (some #(not= % "nvidia") render-drivers) (conj :vaapi)
    nvidia-device?                            (conj :nvenc)))

(defn detect-accels
  "Probes the host for available hardware-acceleration backends. Returns a set
   always containing :none plus any of :nvenc / :vaapi actually usable here."
  []
  (accels-from (render-node-drivers) (file-exists? "/dev/nvidia0")))

(def available-accels
  "Memoised set of accel backends available on this host. Detection is a cheap
   filesystem probe; memoised so it runs once per process."
  (memoize detect-accels))

;; ---------------------------------------------------------------------------
;; Backend resolution
;; ---------------------------------------------------------------------------

(defn resolve-accel
  "Resolve the requested accel backend against what this host actually offers.

   `requested` may be `:auto`/nil (pick the best hardware backend available,
   else software), `:none` (force software), or `:nvenc`/`:vaapi` (use if
   present, otherwise fall back to the other hardware backend, then software,
   with a warning). Preference order for `:auto` and for hardware fallback is
   NVENC then VAAPI.

   The single-arg arity uses the memoised host detection; the two-arg arity
   takes an explicit `available` set (used by tests)."
  ([requested] (resolve-accel requested (available-accels)))
  ([requested available]
   (let [req      (keyword (or requested :auto))
         best-hw  (some available [:nvenc :vaapi])]
     (cond
       (= req :auto)             (or best-hw :none)
       (contains? available req) req
       (= req :none)             :none
       best-hw                   (do (log/warn "Requested FFmpeg accel unavailable; falling back to alternative hardware accel"
                                               {:requested req :fallback best-hw :available available})
                                     best-hw)
       :else                     (do (log/warn "Requested FFmpeg accel unavailable; using software encoding"
                                               {:requested req :available available})
                                     :none)))))

(defn- resolve-encoder
  "Logical codec name (h264/hevc) -> concrete FFmpeg encoder for `accel`.
   An unrecognised codec passes through verbatim (e.g. an explicit encoder
   name), defaulting to the software H.264 encoder if truly unknown."
  [vcodec accel]
  (or (get-in video-codec-map [vcodec accel])
      vcodec
      "libx264"))

;; ---------------------------------------------------------------------------
;; Argument builders
;; ---------------------------------------------------------------------------

(defn input-args
  "Decode / device-setup flags that must appear BEFORE `-i`.

   Uses best-effort GPU decode (`-hwaccel` without the strict
   `-hwaccel_output_format`) so a codec the GPU cannot decode falls back to
   software decode per-stream instead of failing the whole transcode — the
   robust choice for arbitrary intake sources. The device is also initialised
   as a filter device so the `hwupload` in `video-filter` has a target."
  ([accel] (input-args accel default-vaapi-device))
  ([accel device]
   (case accel
     :vaapi ["-init_hw_device" (str "vaapi=va:" (or device default-vaapi-device))
             "-hwaccel" "vaapi" "-hwaccel_device" "va" "-filter_hw_device" "va"]
     :nvenc ["-init_hw_device" "cuda=cu"
             "-hwaccel" "cuda" "-hwaccel_device" "cu" "-filter_hw_device" "cu"]
     [])))

(defn video-filter
  "The `-vf` filter string for `accel`, or nil when none is needed.

   GPU encoders are fed from system memory here (decode may fall back to
   software), so frames are converted to nv12 and uploaded to the GPU. Grout
   does not rescale on intake — only codec/pixel-format/audio are normalised —
   so there is no scale/pad, just the format+upload the hardware encoder
   requires. Software encoding needs no filter (the pixel format is set with
   `-pix_fmt`)."
  [accel]
  (case accel
    :vaapi "format=nv12,hwupload"
    :nvenc "format=nv12,hwupload_cuda"
    nil))

(defn video-encode-args
  "Video encoder selection + a constant-quality rate control for `accel`.

   `vcodec` is the profile's logical codec (\"h264\"). The software path keeps
   libx264 at its default CRF (unchanged from the original intake behaviour);
   the hardware paths pin a constant-quality mode so that files — which Grout
   encodes once and Pseudovision then streams by path many times — stay at a
   sensible quality rather than a low default bitrate."
  [accel vcodec]
  (let [enc (resolve-encoder vcodec accel)]
    (into ["-c:v" enc]
          (case accel
            ;; VAAPI constant-quantiser: quality-targeted, driver-independent.
            :vaapi ["-rc_mode" "CQP" "-qp" "23"]
            ;; NVENC constant-quality VBR (-cq with -b:v 0 = pure CQ).
            :nvenc ["-preset" "p5" "-rc" "vbr" "-cq" "23" "-b:v" "0"]
            ;; Software libx264 — unchanged; rely on the default CRF.
            []))))
