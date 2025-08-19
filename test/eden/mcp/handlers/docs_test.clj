(ns eden.mcp.handlers.docs-test
  "Tests for MCP documentation handlers"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [eden.mcp.handlers.docs :as docs]))

(def test-dir (str (System/getProperty "java.io.tmpdir") "/eden-docs-test-" (System/currentTimeMillis)))
(def test-site-edn (str test-dir "/site.edn"))
(def test-config {:site-edn test-site-edn})

(defn setup-test-site
  "Create a minimal test site structure"
  []
  (fs/create-dirs test-dir)

  (spit test-site-edn
        (pr-str {:wrapper :base
                 :index :home
                 :render-roots #{:home :about}
                 :lang {:en {:name "English" :default true}
                        :no {:name "Norwegian"}}
                 :url-strategy :nested
                 :page-url-strategy :clean})))

(defn cleanup-test-site []
  (fs/delete-tree test-dir))

(use-fixtures :each
  (fn [f]
    (setup-test-site)
    (f)
    (cleanup-test-site)))

(deftest test-get-documentation-returns-topics
  (testing "get-documentation without params returns available topics"
    (let [result (docs/get-documentation test-config {})]
      (is (not (:error result)) "Should not have error")
      (is (contains? result :content) "Should have :content key")

      (when-let [content (:content result)]
        (is (vector? content) "Content should be a vector")
        (let [text-item (first content)]
          (is (= "text" (:type text-item)) "Should have text type")
          (is (str/includes? (:text text-item) "Available Documentation Topics")
              "Should list available topics")
          (is (str/includes? (:text text-item) "template-directives")
              "Should include template-directives topic")
          (is (str/includes? (:text text-item) "content-files")
              "Should include content-files topic"))))))

(deftest test-get-documentation-template-directives
  (testing "get-documentation returns template directives reference"
    (let [result (docs/get-documentation test-config {:topic "template-directives"})]
      (is (not (:error result)) "Should not have error")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text ":eden/get") "Should document :eden/get")
          (is (str/includes? text ":eden/body") "Should document :eden/body")
          (is (str/includes? text ":eden/if") "Should document :eden/if")
          (is (str/includes? text ":eden/each") "Should document :eden/each")
          (is (str/includes? text ":eden/link") "Should document :eden/link")
          (is (re-find #"Example|example" text) "Should include examples"))))))

(deftest test-get-documentation-content-files
  (testing "get-documentation returns content files information"
    (let [result (docs/get-documentation test-config {:topic "content-files"})]
      (is (not (:error result)) "Should not have error")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text "Content Files") "Should have content files title")
          (is (str/includes? text "no fixed schema") "Should mention no schema")
          (is (str/includes? text ":title") "Should document title field")
          (is (str/includes? text ":template") "Should document template field")
          (is (str/includes? text ":slug") "Should document slug field")
          (is (str/includes? text ".edn") "Should mention EDN format")
          (is (str/includes? text ".md") "Should mention Markdown format"))))))

(deftest test-get-documentation-quickstart
  (testing "get-documentation returns quickstart guide"
    (let [result (docs/get-documentation test-config {:topic "quickstart"})]
      (is (not (:error result)) "Should not have error")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text "init") "Should include init command")
          (is (str/includes? text "templates") "Should mention templates")
          (is (str/includes? text "content") "Should mention content")
          (is (re-find #"\.edn|\.md" text) "Should mention file formats"))))))

(deftest test-get-documentation-invalid-topic
  (testing "get-documentation handles invalid topic gracefully"
    (let [result (docs/get-documentation test-config {:topic "nonexistent"})]
      (is (contains? result :error) "Should return error for invalid topic")
      (is (str/includes? (:error result) "Unknown topic")
          "Error should indicate unknown topic"))))

(deftest test-get-site-config
  (testing "get-site-config returns current site configuration"
    (let [result (docs/get-site-config test-config {})]
      (is (not (:error result)) "Should not have error")
      (is (contains? result :content) "Should have :content key")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text "Site Configuration") "Should have title")
          (is (str/includes? text ":wrapper") "Should show wrapper template")
          (is (str/includes? text ":index") "Should show index template")
          (is (str/includes? text ":render-roots") "Should show render roots")
          (is (str/includes? text "Languages") "Should show language config")
          (is (str/includes? text "English") "Should show configured languages")
          (is (str/includes? text ":url-strategy") "Should show URL strategy"))))))

(deftest test-get-site-config-with-missing-site
  (testing "get-site-config handles missing site.edn gracefully"
    (.delete (io/file test-site-edn))
    (let [result (docs/get-site-config test-config {})]
      (is (contains? result :error) "Should return error for missing site")
      (is (str/includes? (:error result) "not found")
          "Error should indicate file not found"))))

(deftest test-analyze-template
  (testing "analyze-template extracts template metadata"
    (fs/create-dirs (str test-dir "/templates"))

    (spit (str test-dir "/templates/test.edn")
          (pr-str [:div
                   [:h1 [:eden/get :title]]
                   [:p [:eden/get :description "No description"]]
                   [:eden/if :show-author
                    [:span [:eden/get :author]]]
                   [:eden/each :items
                    [:li [:eden/get :name]]]]))

    (let [result (docs/analyze-template test-config {:template "test"})]
      (is (not (:error result)) "Should not have error")
      (is (contains? result :content) "Should have :content key")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text "Template Analysis: test") "Should show template name")
          (is (str/includes? text "Data Fields Used") "Should list used fields")
          (is (str/includes? text ":title") "Should detect :title field")
          (is (str/includes? text ":description") "Should detect :description field")
          (is (str/includes? text "default") "Should note default values")
          (is (str/includes? text ":show-author") "Should detect conditional fields")
          (is (str/includes? text ":items") "Should detect collection fields")
          (is (str/includes? text "Directives Used") "Should list directives")
          (is (str/includes? text ":eden/get") "Should list eden/get")
          (is (str/includes? text ":eden/if") "Should list eden/if")
          (is (str/includes? text ":eden/each") "Should list eden/each"))))))

(deftest test-list-directives
  (testing "list-directives returns all available Eden directives"
    (let [result (docs/list-directives test-config {})]
      (is (not (:error result)) "Should not have error")

      (when-let [content (:content result)]
        (let [text (:text (first content))]
          (is (str/includes? text "Eden Template Directives") "Should have title")
          (is (str/includes? text ":eden/get") "Should list :eden/get")
          (is (str/includes? text ":eden/get-in") "Should list :eden/get-in")
          (is (str/includes? text ":eden/body") "Should list :eden/body")
          (is (str/includes? text ":eden/if") "Should list :eden/if")
          (is (str/includes? text ":eden/each") "Should list :eden/each")
          (is (str/includes? text ":eden/with") "Should list :eden/with")
          (is (str/includes? text ":eden/link") "Should list :eden/link")
          (is (str/includes? text ":eden/render") "Should list :eden/render")
          (is (str/includes? text ":eden/include") "Should list :eden/include")
          (is (str/includes? text ":eden/t") "Should list :eden/t for translations")
          (is (re-find #"Example|Usage" text) "Should include examples or usage"))))))