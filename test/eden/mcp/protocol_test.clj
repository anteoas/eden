(ns eden.mcp.protocol-test
  "Tests for MCP protocol"
  (:require [clojure.test :refer [deftest testing is]]
            [eden.mcp.protocol :as protocol]))

(def test-config
  {:site-edn "site.edn"
   :port 3000
   :mode :dev
   :site-config {:output-path "dist"}})

(defn json-rpc-request [method params & [id]]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id (or id 1)})

(deftest test-protocol-initialization
  (testing "Initialize method returns correct protocol version"
    (let [request (json-rpc-request "initialize" {:protocolVersion "2024-11-05"})
          response (protocol/handle-method test-config "initialize" (:params request))]
      (is (= "2024-11-05" (:protocolVersion response)))
      (is (= "Eden MCP Server" (get-in response [:serverInfo :name])))
      (is (map? (:capabilities response)))
      (is (map? (get-in response [:capabilities :resources])))
      (is (map? (get-in response [:capabilities :tools]))))))

(deftest test-error-handling
  (testing "Invalid method returns proper error"
    (is (thrown-with-msg? Exception #"Method not found"
                          (protocol/handle-method test-config "invalid-method" {})))))
