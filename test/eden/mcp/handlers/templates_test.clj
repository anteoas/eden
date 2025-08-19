(ns eden.mcp.handlers.templates-test
  "Tests for MCP templates handler"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [eden.mcp.handlers.templates :as templates]))

(def test-dir (str (System/getProperty "java.io.tmpdir") "/eden-templates-test-" (System/currentTimeMillis)))
(def test-site-edn (str test-dir "/site.edn"))
(def test-config {:site-edn test-site-edn})

(defn setup-test-site
  "Create a minimal test site structure"
  []
  (fs/create-dirs (str test-dir "/templates"))

  (spit test-site-edn
        (pr-str {:wrapper :base
                 :index :home}))

  (spit (str test-dir "/templates/base.edn")
        (pr-str [:html
                 [:head [:title [:eden/get :title]]]
                 [:body [:eden/body]]]))

  (spit (str test-dir "/templates/home.edn")
        (pr-str [:div
                 [:h1 [:eden/get :title]]
                 [:div [:eden/get :content]]])))

(defn cleanup-test-site []
  (fs/delete-tree test-dir))

(use-fixtures :each
  (fn [f]
    (setup-test-site)
    (f)
    (cleanup-test-site)))

(deftest test-list-templates-returns-templates
  (testing "list-templates should return available templates"
    (let [result (templates/list-templates test-config {})]
      (is (not (:error result)) "Should not have error")
      (is (contains? result :content) "Should have :content key")

      (when-let [content (:content result)]
        (is (vector? content) "Content should be a vector")
        (is (pos? (count content)) "Should have at least one template")

        (when (seq content)
          (let [item (first content)]
            (is (map? item) "Content items should be maps")
            (is (= "text" (:type item)) "Should have type :text")
            (is (string? (:text item)) "Should have text content")
            (is (or (str/includes? (:text item) "base")
                    (str/includes? (:text item) "home"))
                "Should mention template names")))))))

(deftest test-list-templates-handler-works
  (testing "Handler should successfully list templates"
    (let [result (templates/list-templates test-config {})]
      (is (not (:error result)) "Should not return an error")
      (is (contains? result :content) "Should have :content key")
      (is (vector? (:content result)) "Content should be a vector")
      (when-let [content (:content result)]
        (when (seq content)
          (let [text-item (first content)]
            (is (= "text" (:type text-item)) "Should have text type")
            (let [text (:text text-item)]
              (is (str/includes? text "base") "Should list base template")
              (is (str/includes? text "home") "Should list home template"))))))))

(deftest test-preview-template-returns-html
  (testing "preview-template should return rendered HTML"
    (let [result (templates/preview-template test-config
                                             {:template "home"
                                              :data {:title "Test Title"
                                                     :content "Test content"}})]
      (is (not (:error result)) "Should not have error")
      (is (contains? result :content) "Should have :content key")

      (when-let [content (:content result)]
        (is (vector? content) "Content should be a vector")
        (is (pos? (count content)) "Should have content")

        (when (seq content)
          (let [item (first content)]
            (is (map? item) "Content items should be maps")
            (is (= "text" (:type item)) "Should have type :text")
            (is (string? (:text item)) "Should have text content")
            (is (str/includes? (:text item) "Test Title")
                "Should include the provided title")))))))
