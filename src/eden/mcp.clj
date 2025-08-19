(ns eden.mcp
  "MCP Server public API for Eden static site generator"
  (:require [eden.mcp.server :as server]
            [eden.loader :as loader]
            [clojure.java.io :as io]))

(defn start-server
  "Start MCP server in production mode with authentication"
  [site-edn port secret]
  (let [site-file (io/file site-edn)]
    (when-not (.exists site-file)
      (throw (ex-info "Site configuration not found" {:site-edn site-edn})))

    (let [site-config (:config (loader/load-site-data site-edn "dist"))]
      (server/start-server
       {:mode :prod
        :port port
        :site-edn site-edn
        :site-config site-config
        :secret secret
        :auth? true}))))

(defn start-dev-server
  "Start MCP server in development mode without authentication"
  [site-edn port]
  (let [site-file (io/file site-edn)]
    (when-not (.exists site-file)
      (throw (ex-info "Site configuration not found" {:site-edn site-edn})))

    (let [site-config (:config (loader/load-site-data site-edn "dist"))]
      (server/start-server
       {:mode :dev
        :port port
        :site-edn site-edn
        :site-config site-config
        :auth? false}))))

(defn start-dev-server-with-browser-sync
  "Start MCP server in development mode with browser-sync port info"
  [site-edn mcp-port browser-sync-port]
  (let [site-file (io/file site-edn)]
    (when-not (.exists site-file)
      (throw (ex-info "Site configuration not found" {:site-edn site-edn})))

    (let [site-config (:config (loader/load-site-data site-edn "dist"))]
      (server/start-server
       {:mode :dev
        :port mcp-port
        :browser-sync-port browser-sync-port
        :site-edn site-edn
        :site-config site-config
        :auth? false}))))

(defn start-stdio-server
  "Start MCP server in stdio mode for Claude Desktop integration"
  [site-edn]
  (require '[eden.mcp.stdio :as stdio])
  ((resolve 'eden.mcp.stdio/start-stdio-server) site-edn))