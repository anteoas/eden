(ns eden.mcp.tools-test
  "Tests for MCP tools"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [eden.mcp.tools :as tools]))

(def test-config
  {:site-edn "site.edn"
   :port 3000
   :mode :dev
   :site-config {:output-path "dist"}})

(deftest test-tools-list
  (testing "List tools returns expected tools"
    (let [response (tools/list-tools test-config)]
      (is (map? response))
      (is (vector? (:tools response)))
      (let [tool-names (set (map :name (:tools response)))]
        (is (contains? tool-names "list-content"))
        (is (contains? tool-names "read-content"))
        (is (contains? tool-names "write-content"))
        (is (contains? tool-names "delete-content"))
        (is (contains? tool-names "list-templates"))
        (is (contains? tool-names "preview-template"))
        (is (contains? tool-names "build-site"))
        (is (contains? tool-names "get-build-status"))))))

(deftest test-content-operations
  (testing "Content operations return MCP-compliant responses"
    ;; Test list-content
    (let [list-response (tools/call-tool test-config "list-content" {})]
      (is (map? list-response))
      (is (vector? (:content list-response)))
      (when (seq (:content list-response))
        (let [item (first (:content list-response))]
          (is (= "text" (:type item)))
          (is (string? (:text item))))))

    ;; Test write-content
    (let [test-path "test/test-content.md"
          write-response (tools/call-tool test-config "write-content"
                                          {:path test-path
                                           :frontmatter {:template "page" :title "Test"}
                                           :content "Test content"})]
      (is (or (map? (:content write-response))
              (vector? (:content write-response))))
      (when (:content write-response)
        (let [content (if (vector? (:content write-response))
                        (first (:content write-response))
                        (:content write-response))]
          (is (= "text" (:type content)))
          (is (string? (:text content)))
          (is (re-find #"Successfully wrote" (:text content)))))

      ;; Clean up test file
      (fs/delete-if-exists (io/file "content" test-path)))

    ;; Test read-content with error case
    (let [read-response (tools/call-tool test-config "read-content"
                                         {:path "nonexistent.md"})]
      (is (or (:error read-response)
              (and (:content read-response)
                   (vector? (:content read-response))))))))

(deftest test-template-operations
  (testing "Template operations return MCP-compliant responses"
    (let [list-response (tools/call-tool test-config "list-templates" {})]
      (is (map? list-response))
      (is (or (:content list-response)
              (:error list-response)))
      (when (:content list-response)
        (is (vector? (:content list-response)))
        (when (seq (:content list-response))
          (let [item (first (:content list-response))]
            (is (= "text" (:type item)))
            (is (string? (:text item)))))))))

(deftest test-build-operations
  (testing "Build operations return MCP-compliant responses"
    ;; Test get-build-status
    (let [status-response (tools/call-tool test-config "get-build-status" {})]
      (is (map? status-response))
      (is (or (:content status-response)
              (:error status-response)))
      (when (:content status-response)
        (is (vector? (:content status-response)))
        (let [item (first (:content status-response))]
          (is (= "text" (:type item)))
          (is (string? (:text item))))))))

(deftest test-error-handling
  (testing "Invalid tool returns error response"
    (let [response (tools/call-tool test-config "invalid-tool" {})]
      (is (:error response))
      (is (string? (:error response))))))

(deftest test-mcp-content-schema-compliance
  (testing "All tool responses comply with MCP content schema"
    (let [tools-to-test ["list-content" "list-templates" "get-build-status"]]
      (doseq [tool-name tools-to-test]
        (testing (str "Tool: " tool-name)
          (let [response (tools/call-tool test-config tool-name {})]
            (when (:content response)
              (is (vector? (:content response))
                  (str tool-name " should return content as vector"))
              (doseq [item (:content response)]
                (is (contains? #{"text" "image" "audio" "resource_link" "resource"}
                               (:type item))
                    (str tool-name " content item should have valid type"))
                (case (:type item)
                  "text" (is (string? (:text item))
                             (str tool-name " text content should have text field"))
                  "image" (do (is (string? (:data item)))
                              (is (string? (:mimeType item))))
                  "audio" (do (is (string? (:data item)))
                              (is (string? (:mimeType item))))
                  "resource_link" (do (is (string? (:uri item)))
                                      (is (string? (:name item))))
                  "resource" (is (map? (:resource item)))
                  nil)))))))))
