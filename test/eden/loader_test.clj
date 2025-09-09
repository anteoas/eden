(ns eden.loader-test
  (:require [clojure.test :refer [deftest is testing] :as t]
            [eden.loader :as loader]
            [clojure.java.io :as io]))

(deftest load-templates
  (testing "can load templates"
    (let [templates (loader/load-templates (io/file (io/resource "init-site/templates")))]
      (is (map? templates))
      (is (< 0 (count templates))))))


(deftest load-content-file
  (testing "basic loading"
    (#'loader/load-content-file (io/file "site/content/en/home.md")))
)
#_(t/run-tests)
