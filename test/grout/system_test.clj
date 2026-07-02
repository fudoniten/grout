(ns grout.system-test
  (:require [clojure.test :refer [deftest is]]
            [grout.system :as system]))

(deftest system-config-has-expected-components
  (let [c (system/->system-config {:database {:jdbc-url "jdbc:x"}})]
    (is (every? c [:grout/logger :grout/db :grout/tunabrain :grout/media
                   :grout/enrichment-worker :grout/retention-job :grout/http]))))

(deftest enabled-flags-coerced-from-strings
  (let [c (system/->system-config {:enrichment {:enabled "false"}
                                   :retention {:enabled "true"}})]
    (is (false? (get-in c [:grout/enrichment-worker :enabled])))
    (is (true? (get-in c [:grout/retention-job :enabled])))))

(deftest media-profile-threaded-through
  (let [profile {:vcodec "h264" :acodec "aac"}
        c (system/->system-config {:media {:media-dir "/m" :profile profile}})]
    (is (= profile (get-in c [:grout/media :profile])))
    (is (= "/m" (get-in c [:grout/media :media-dir])))))
