(ns eden.mcp.server
  "Jetty/Ring server for MCP protocol"
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]
            [eden.mcp.protocol :as protocol]
            [eden.mcp.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn handle-mcp-request
  "Handle MCP JSON-RPC request"
  [config request]
  (let [body (:body request)]
    (-> (protocol/handle-request config body)
        (response/response)
        (response/content-type "application/json"))))

(defn handle-upload
  "Handle asset upload"
  [_config _request]
  ;; TODO: Implement asset upload
  (response/response {:error "Upload not yet implemented"}))

(defn serve-static-file
  "Serve a static file from the output directory"
  [config path]
  (let [output-path (get-in config [:site-config :output-path])
        file (io/file output-path path)]
    (if (.exists file)
      (-> (response/file-response (.getPath file))
          (response/content-type (cond
                                   (str/ends-with? path ".html") "text/html"
                                   (str/ends-with? path ".css") "text/css"
                                   (str/ends-with? path ".js") "application/javascript"
                                   (str/ends-with? path ".json") "application/json"
                                   :else "application/octet-stream")))
      (response/not-found "File not found"))))

(defn create-routes
  "Create Ring routes for the MCP server"
  [config]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]
      (cond
        ;; MCP RPC endpoint (with or without auth)
        (and (= :post method)
             (or (= uri "/mcp/rpc") ; Dev mode
                 (re-matches #"/mcp/[^/]+/rpc" uri))) ; Prod mode with secret
        (if (:auth? config)
          (auth/wrap-auth handle-mcp-request config request)
          (handle-mcp-request config request))

        ;; Asset upload endpoint
        (and (= :post method)
             (or (= uri "/mcp/upload")
                 (re-matches #"/mcp/[^/]+/upload" uri)))
        (if (:auth? config)
          (auth/wrap-auth handle-upload config request)
          (handle-upload config request))

        ;; Build report
        (= uri "/_report.html")
        (let [report-file (io/file (get-in config [:site-config :output-path]) "_report.html")]
          (if (.exists report-file)
            (response/file-response (.getPath report-file))
            (response/not-found "Build report not available")))

        ;; Static files (dev server)
        (= :get method)
        (serve-static-file config (if (= uri "/") "index.html" (subs uri 1)))

        :else
        (response/not-found "Not found")))))

(defn wrap-cors
  "Add CORS headers for development"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, OPTIONS")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type")))))

(defn create-app
  "Create Ring application"
  [config]
  (-> (create-routes config)
      wrap-cors))

(defn start-server
  "Start the MCP server"
  [config]
  (let [app (create-app config)
        port (:port config)
        server (jetty/run-jetty app
                                {:port port
                                 :join? false})]
    (println (str "MCP Server started on http://localhost:" port))
    (when (:auth? config)
      (println "Authentication enabled"))
    server))
