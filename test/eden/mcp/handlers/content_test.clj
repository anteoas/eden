(ns eden.mcp.handlers.content-test
  "Tests for MCP content handler"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [eden.mcp.handlers.content :as content]
            [eden.mcp.simulator :as simulator]))

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

(deftest test-write-content-with-validation
  (testing "Write content validates through simulator"
    (let [result (content/write-content test-config
                                        {:path "en/validated.edn"
                                         :frontmatter {:title "Validated Page"
                                                       :template :home
                                                       :slug "validated"}
                                         :content "This content is validated before writing"})]
      (is (= :content (first (keys result))) "Should return content key")
      (is (str/includes? (-> result :content first :text) "Successfully")
          "Should indicate success")
      (is (str/includes? (-> result :content first :text) "HTML Preview")
          "Should include HTML preview"))))

(deftest test-write-content-prevents-invalid
  (testing "Write content prevents invalid EDN"
    ;; Create a file with truly broken content directly
    (let [broken-file (io/file test-dir "content/en/syntax-test.edn")]
      (fs/create-dirs (.getParentFile broken-file))
      (spit broken-file "{:broken") ; Invalid EDN

      ;; Now try to simulate it
      (let [result (simulator/simulate-content-change
                    {:site-edn test-site-edn
                     :path "en/syntax-test.edn"
                     :content (slurp broken-file)})]
        (is (not (:success? result)) "Should fail on broken EDN")
        (is (:error result) "Should have error message"))

      ;; Clean up
      (.delete broken-file))))

(deftest test-write-markdown-with-frontmatter
  (testing "Writing markdown with EDN frontmatter"
    (let [result (content/write-content test-config
                                        {:path "en/blog.md"
                                         :frontmatter {:title "Blog Post"
                                                       :template :home
                                                       :slug "blog"}
                                         :content "# Heading\n\nMarkdown content"})]
      (is (str/includes? (-> result :content first :text) "Successfully"))
      (when (.exists (io/file test-dir "content/en/blog.md"))
        (let [content (slurp (io/file test-dir "content/en/blog.md"))]
          ;; Check for correct EDN format (no ---eden wrapper)
          (is (str/starts-with? content "{") "Should start with EDN map")
          (is (str/includes? content "\n---\n") "Should have single --- separator")
          (is (not (str/includes? content "---eden")) "Should NOT have ---eden wrapper")
          (is (str/includes? content "# Heading") "Should preserve markdown"))
        ;; Clean up
        (.delete (io/file test-dir "content/en/blog.md"))))))

(deftest test-list-content-returns-mcp-format
  (testing "list-content should return MCP-compatible format"
    (let [result (content/list-content test-config {})]
      (is (contains? result :content) "Should have :content key")
      (when-let [content (:content result)]
        (is (vector? content) "Content should be a vector")
        (when (seq content)
          (let [item (first content)]
            (is (map? item) "Content items should be maps")
            (is (contains? item :type) "Content items should have :type field for MCP")
            (is (#{"text" "image" "resource"} (:type item))
                "Type should be valid MCP content type")))))))

(deftest test-read-content-handles-invalid-paths
  (testing "read-content should return error for non-existent files"
    (let [result (content/read-content test-config {:path "nonexistent/file.edn"})]
      (is (contains? result :error) "Should have :error key for non-existent file")
      (is (string? (:error result)) "Error should be a string message")
      (is (or (str/includes? (:error result) "not found")
              (str/includes? (:error result) "does not exist"))
          "Error message should indicate file not found")))

  (testing "read-content should return error for paths outside content directory"
    ;; Create a file outside content directory
    (spit (str test-dir "/outside.txt") "secret data")
    (let [result (content/read-content test-config {:path "../outside.txt"})]
      (is (contains? result :error) "Should have :error key for invalid path")
      (is (string? (:error result)) "Error should be a string message")
      (is (or (str/includes? (:error result) "Invalid")
              (str/includes? (:error result) "not allowed")
              (str/includes? (:error result) "outside"))
          "Error message should indicate invalid path"))
    ;; Clean up
    (.delete (io/file test-dir "outside.txt"))))
