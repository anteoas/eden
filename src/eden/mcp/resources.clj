(ns eden.mcp.resources
  "MCP resource definitions for Eden"
  (:require [eden.mcp.prompts :as prompts]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(defn list-resources
  "List available MCP resources"
  [_config]
  {:resources
   [{:uri "prompt://eden-assistant"
     :name "Eden Assistant"
     :description "Documentation and context for Eden static site generator"
     :mimeType "text/plain"}

    {:uri "eden://config"
     :name "Site Configuration"
     :description "Current site.edn configuration"
     :mimeType "application/edn"}

    {:uri "eden://build-report"
     :name "Build Report"
     :description "Latest build report with warnings and metrics"
     :mimeType "text/html"}

    {:uri "eden://server"
     :name "Server Info"
     :description "Dev server URL and status"
     :mimeType "application/json"}]})

(defn read-config
  "Read the site configuration"
  [config]
  (let [site-file (io/file (:site-edn config))]
    (when (.exists site-file)
      (slurp site-file))))

(defn read-build-report
  "Read the latest build report"
  [config]
  (let [report-file (io/file (get-in config [:site-config :output-path]) "_report.html")]
    (when (.exists report-file)
      (slurp report-file))))

(defn get-server-info
  "Get server information"
  [config]
  (let [browser-port (or (:browser-sync-port config) 3000)
        mcp-port (:port config)]
    {:browser-sync-url (str "http://localhost:" browser-port "/")
     :mcp-url (str "http://localhost:" mcp-port "/mcp/")
     :status "running"
     :browser-sync-port browser-port
     :mcp-port mcp-port
     :mode (name (:mode config))}))

(defn read-resource
  "Read a specific resource by URI"
  [config uri]
  (cond
    (= uri "prompt://eden-assistant")
    {:contents [{:uri uri
                 :mimeType "text/plain"
                 :text (prompts/generate-system-prompt (:site-config config))}]}

    (= uri "eden://config")
    {:contents [{:uri uri
                 :mimeType "application/edn"
                 :text (read-config config)}]}

    (= uri "eden://build-report")
    {:contents [{:uri uri
                 :mimeType "text/html"
                 :text (read-build-report config)}]}

    (= uri "eden://server")
    {:contents [{:uri uri
                 :mimeType "application/json"
                 :text (json/write-str (get-server-info config))}]}

    :else
    {:error (str "Resource not found: " uri)}))
