(ns eden.mcp.simulator-test
  "Tests for MCP simulator"
  (:require [clojure.test :refer [deftest testing use-fixtures is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [eden.mcp.simulator :as simulator]))

;; Test fixture setup
(def test-dir (str (System/getProperty "java.io.tmpdir") "/eden-test-" (System/currentTimeMillis)))
(def test-site-edn (str test-dir "/site.edn"))

(defn setup-test-site
  "Create a minimal test site structure"
  []
  (fs/create-dirs (str test-dir "/templates"))
  (fs/create-dirs (str test-dir "/content/en"))

  ;; Create minimal site.edn
  (spit test-site-edn
        (pr-str {:wrapper :base
                 :index :home
                 :render-roots #{:home}
                 :url-strategy :nested
                 :lang {:en {:name "English" :default true}}}))

  ;; Create base template
  (spit (str test-dir "/templates/base.edn")
        (pr-str [:html
                 [:head [:title [:eden/get :title]]]
                 [:body [:eden/body]]]))

  ;; Create home template
  (spit (str test-dir "/templates/home.edn")
        (pr-str [:div
                 [:h1 [:eden/get :title]]
                 [:div [:eden/get :content]]]))

  ;; Create minimal home content so site loads
  (spit (str test-dir "/content/en/home.edn")
        (pr-str {:title "Test Home"
                 :template :home
                 :slug "index"
                 :content "Test site"})))

(defn cleanup-test-site
  "Remove test site directory"
  []
  (when (fs/exists? test-dir)
    (fs/delete-tree test-dir)))

(use-fixtures :each
  (fn [f]
    (setup-test-site)
    (try
      (f)
      (finally
        (cleanup-test-site)))))

(deftest test-simulator-valid-content
  (testing "Simulator accepts valid content"
    (let [result (simulator/simulate-content-change
                  {:site-edn test-site-edn
                   :path "en/test.edn"
                   :content (pr-str {:title "Test Page"
                                     :template :home
                                     :slug "test"
                                     :content "Test content"})})]
      (is (:success? result) "Should succeed")
      (is (:html result) "Should generate HTML")
      (is (map? (:performance result)) "Should include performance metrics"))))

(deftest test-simulator-invalid-edn
  (testing "Simulator catches invalid EDN"
    (let [result (simulator/simulate-content-change
                  {:site-edn test-site-edn
                   :path "en/broken.edn"
                   :content "{:title \"Broken\" :content"})] ; Missing closing brace
      (is (not (:success? result)) "Should fail on invalid EDN")
      (is (:error result) "Should provide error message")
      (is (str/includes? (:error result) "parse") "Error should mention parsing"))))

(deftest test-simulator-missing-template
  (testing "Simulator warns about missing template"
    (let [result (simulator/simulate-content-change
                  {:site-edn test-site-edn
                   :path "en/missing.edn"
                   :content (pr-str {:title "Missing Template"
                                     :template :does-not-exist
                                     :slug "missing"
                                     :content "Test"})})]
      (is (:success? result) "Should still succeed")
      (is (some #(str/includes? (str %) "does-not-exist") (:warnings result))
          "Should warn about missing template"))))

(deftest test-simulator-performance
  (testing "Simulator completes in reasonable time"
    (let [start (System/currentTimeMillis)
          result (simulator/simulate-content-change
                  {:site-edn test-site-edn
                   :path "en/perf.edn"
                   :content (pr-str {:title "Performance Test"
                                     :template :home
                                     :content "Test"})})
          elapsed (- (System/currentTimeMillis) start)]
      (is (< elapsed 1000) "Should complete in under 1 second")
      (is (:performance result) "Should include performance metrics")
      (is (< (reduce + (vals (:performance result))) 500)
          "Total pipeline time should be under 500ms"))))
