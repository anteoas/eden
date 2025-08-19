(ns eden.mcp.protocol
  "MCP JSON-RPC 2.0 protocol implementation"
  (:require [clojure.data.json :as json]
            [eden.mcp.tools :as tools]
            [eden.mcp.resources :as resources]))

(defn json-rpc-error
  "Create a JSON-RPC error response"
  [id code message & [data]]
  {:jsonrpc "2.0"
   :error {:code code
           :message message
           :data data}
   :id id})

(defn json-rpc-success
  "Create a JSON-RPC success response"
  [id result]
  {:jsonrpc "2.0"
   :result result
   :id id})

(defn handle-method
  "Route method to appropriate handler"
  [config method params]
  (cond
    ;; MCP initialization
    (= method "initialize")
    {:protocolVersion "2024-11-05"
     :serverInfo {:name "Eden MCP Server"
                  :version "1.0.0"}
     :capabilities {:resources {}
                    :tools {}}}

    ;; List available resources
    (= method "resources/list")
    (resources/list-resources config)

    ;; Read a specific resource
    (= method "resources/read")
    (resources/read-resource config (:uri params))

    ;; List available tools
    (= method "tools/list")
    (tools/list-tools config)

    ;; Call a tool
    (= method "tools/call")
    (tools/call-tool config (:name params) (:arguments params))

    ;; Unknown method
    :else
    (throw (ex-info "Method not found" {:code -32601 :method method}))))

(defn handle-request
  "Handle a JSON-RPC request"
  [config request-body]
  (try
    (let [;; Handle different input types
          body-str (cond
                     (string? request-body) request-body
                     ;; InputStream from Ring
                     (instance? java.io.InputStream request-body) (slurp request-body)
                     ;; Otherwise try to convert to string
                     :else (str request-body))
          request (json/read-str body-str :key-fn keyword)
          {:keys [jsonrpc method params id]} request]

      ;; Validate JSON-RPC version
      (when-not (= jsonrpc "2.0")
        (throw (ex-info "Invalid JSON-RPC version" {:code -32600})))

      ;; Handle the method
      (let [result (handle-method config method params)]
        (json/write-str (json-rpc-success id result))))

    (catch Exception e
      (let [data (ex-data e)
            code (or (:code data) -32603)
            message (or (.getMessage e) "Internal error")]
        (json/write-str (json-rpc-error nil code message data))))))