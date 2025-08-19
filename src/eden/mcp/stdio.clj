(ns eden.mcp.stdio
  "STDIO transport for Eden MCP server"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [eden.mcp.protocol :as protocol]
            [eden.loader :as loader]
            [clojure.string :as str])
  (:import [java.io BufferedReader]))

(defn read-json-rpc-message
  "Read a JSON-RPC message from stdin"
  [reader]
  (try
    (when-let [line (.readLine reader)]
      (when-not (str/blank? line)
        (json/read-str line :key-fn keyword)))
    (catch Exception e
      {:error "Failed to parse JSON-RPC message"
       :details (.getMessage e)})))

(defn write-json-rpc-response
  "Write a JSON-RPC response to stdout"
  [response]
  (println (json/write-str response))
  (flush))

(defn start-stdio-server
  "Start MCP server in stdio mode"
  [site-edn]
  ;; Load site configuration
  (let [site-config (:config (loader/load-site-data site-edn "dist"))
        config {:site-edn site-edn
                :site-config site-config
                :mode :stdio
                :protocol :stdio}
        reader (BufferedReader. (io/reader System/in))]

    ;; Log to stderr so it doesn't interfere with JSON-RPC on stdout
    (binding [*err* *err*]
      (.println *err* "Eden MCP Server (stdio mode) started")
      (.println *err* (str "Site: " site-edn)))

    ;; Main message loop
    (loop []
      (when-let [request (read-json-rpc-message reader)]
        (try
          (let [response-body (protocol/handle-request config (json/write-str request))
                response (json/read-str response-body :key-fn keyword)]
            (write-json-rpc-response response))
          (catch Exception e
            ;; Send error response
            (write-json-rpc-response
             {:jsonrpc "2.0"
              :error {:code -32603
                      :message "Internal error"
                      :data {:error (.getMessage e)}}
              :id (:id request)})))
        (recur)))

    ;; Clean shutdown
    (binding [*err* *err*]
      (.println *err* "Eden MCP Server (stdio mode) shutting down"))))

