(ns eden.mcp.resources-test
  "Tests for MCP resources"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [eden.mcp.resources :as resources]))

(def test-config
  {:site-edn "site.edn"
   :port 3000
   :mode :dev
   :site-config {:output-path "dist"}})

(deftest test-resources-list
  (testing "List resources returns expected resources"
    (let [response (resources/list-resources test-config)]
      (is (map? response))
      (is (vector? (:resources response)))
      (let [resource-uris (set (map :uri (:resources response)))]
        (is (contains? resource-uris "prompt://eden-assistant"))
        (is (contains? resource-uris "eden://config"))
        (is (contains? resource-uris "eden://build-report"))
        (is (contains? resource-uris "eden://server"))))))

(deftest test-server-info-resource
  (testing "Server info resource returns correct browser-sync URL"
    (let [response (resources/read-resource test-config "eden://server")]
      (is (map? response))
      (is (vector? (:contents response)))
      (let [content (first (:contents response))]
        (is (= "application/json" (:mimeType content)))
        (is (string? (:text content)))
        (let [server-info (json/read-str (:text content) :key-fn keyword)]
          (is (string? (:browser-sync-url server-info)))
          (is (string? (:mcp-url server-info)))
          (is (number? (:browser-sync-port server-info)))
          (is (number? (:mcp-port server-info))))))))
