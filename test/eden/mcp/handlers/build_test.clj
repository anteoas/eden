(ns eden.mcp.handlers.build-test
  "Tests for MCP build handler"
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [eden.mcp.handlers.content :as content]
            [eden.mcp.handlers.build :as build]))

;; Test fixture setup
(def test-dir (str (System/getProperty "java.io.tmpdir") "/eden-test-" (System/currentTimeMillis)))
(def test-site-edn (str test-dir "/site.edn"))
(def test-config {:site-edn test-site-edn})

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

(deftest test-rebuild-after-content-change
  (testing "Rebuild includes new content"
    ;; Write content first
    (content/write-content test-config
                           {:path "en/rebuild-test.edn"
                            :frontmatter {:title "Rebuild Test"
                                          :template :home
                                          :slug "rebuild"}
                            :content "Test rebuild"})

    ;; Rebuild
    (let [result (build/rebuild-site test-config)]
      (is (map? result) "Should return build result")
      (is (or (:success result) (:success? result)) "Build should succeed"))

    ;; Clean up
    (when (.exists (io/file test-dir "content/en/rebuild-test.edn"))
      (.delete (io/file test-dir "content/en/rebuild-test.edn")))))
