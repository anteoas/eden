(ns eden.mcp
  "Integrated MCP server using clojure-mcp with embedded nREPL.
   Runs both MCP server and Eden in a single process."
  (:require [clojure-mcp.core :as mcp-core]
            [clojure-mcp.main :as mcp-main]
            [nrepl.server :as nrepl-server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eden.loader :as loader]))

(defonce nrepl-server-instance (atom nil))

(defn- parse-mcp-resource
  "Parse an MCP resource file using Eden's markdown parser"
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (let [content (slurp resource)
          parsed (loader/parse-markdown content)
          ;; Extract markdown content and remove it from metadata
          markdown-content (:markdown/content parsed)
          metadata (dissoc parsed :markdown/content)]
      {:metadata metadata
       :content markdown-content})))

(defn- load-mcp-resource
  "Load an MCP resource from a markdown file"
  [resource-type resource-name]
  (let [path (str "mcp/" resource-type "/" resource-name ".md")
        {:keys [metadata content]} (parse-mcp-resource path)]
    (when metadata
      (assoc metadata
             :resource-fn (fn [_ _ clj-result-k]
                            (clj-result-k [content]))))))

(defn- load-mcp-prompt
  "Load an MCP prompt from a markdown file"
  [prompt-name]
  (let [path (str "mcp/prompts/" prompt-name ".md")
        {:keys [metadata content]} (parse-mcp-resource path)]
    (when metadata
      {:name (:name metadata)
       :description (:description metadata)
       :arguments (or (:arguments metadata) [])
       :prompt-fn (fn [_ args clj-result-k]
                    ;; Replace template variables in content
                    (let [processed-content (reduce (fn [text [arg-name arg-value]]
                                                      (str/replace text
                                                                   (str "{{" arg-name "}}")
                                                                   (str arg-value)))
                                                    content
                                                    args)]
                      (clj-result-k
                       {:description (:description metadata)
                        :messages [{:role :user
                                    :content processed-content}]})))})))

(defn make-eden-prompts
  "Create Eden-specific prompts by loading from resources"
  [nrepl-client-atom working-dir]
  (concat
   ;; Include standard clojure-mcp prompts
   (mcp-main/make-prompts nrepl-client-atom working-dir)
   ;; Add Eden-specific prompts from resources
   (filter some?
           [(load-mcp-prompt "project-context")
            (load-mcp-prompt "create-page")
            (load-mcp-prompt "debug-build")])))

(defn make-eden-resources
  "Create Eden-specific resources including documentation"
  [nrepl-client-atom working-dir]
  ;; Get default resources from clojure-mcp
  (let [default-resources (mcp-main/make-resources nrepl-client-atom working-dir)
        ;; Filter to only include relevant ones for Eden projects
        filtered-resources (filter #(contains? #{"README.md"
                                                 "CLAUDE.md"
                                                 "Clojure Project Info"}
                                               (:name %))
                                   default-resources)]
    (concat
     filtered-resources
     ;; Add Eden-specific documentation from resources
     (filter some?
             [(load-mcp-resource "resources" "template-directives")
              (load-mcp-resource "resources" "content-structure")
              (load-mcp-resource "resources" "site-config")]))))

(defn make-minimal-tools
  "Create a minimal set of tools - file operations, eval, and bash"
  [nrepl-client-atom working-dir]
  ;; Filter to just the essential tools from mcp-main
  (let [all-tools (mcp-main/make-tools nrepl-client-atom working-dir)]
    (filter #(contains? #{"unified_read_file"
                          "file_edit"
                          "file_write"
                          "LS"
                          "grep"
                          "glob_files"
                          "clojure_eval"
                          "bash"
                          "think"
                          "scratch_pad"}
                        (:name %))
            all-tools)))

(defn start-embedded-nrepl!
  "Start an embedded nREPL server on a random port"
  []
  (let [server (nrepl-server/start-server :port 0) ; Random available port
        port (.getLocalPort ^java.net.ServerSocket (:server-socket server))]
    (reset! nrepl-server-instance {:server server :port port})
    (println "Started embedded nREPL server on port" port)
    port))

(defn stop-embedded-nrepl!
  "Stop the embedded nREPL server"
  []
  (when-let [{:keys [server]} @nrepl-server-instance]
    (nrepl-server/stop-server server)
    (reset! nrepl-server-instance nil)
    (println "Stopped embedded nREPL server")))

(defn start-stdio-server
  "Start Eden with integrated MCP server.
   This runs both the MCP server and Eden in a single process.
   Must be run from the project root directory containing site.edn."
  [site-edn]
  (let [;; Verify site.edn exists
        site-file (io/file site-edn)
        _ (when-not (.exists site-file)
            (throw (ex-info (str "Site EDN file not found: " site-edn
                                 "\nMust be run from project root directory containing site.edn")
                            {:site-edn site-edn})))

        ;; Start embedded nREPL server on random port
        nrepl-port (start-embedded-nrepl!)

        ;; Project root is current directory (where site.edn is)
        site-root (.getCanonicalPath (io/file "."))

        ;; Configure MCP to connect to our embedded nREPL
        mcp-config {:port nrepl-port
                    :host "localhost"
                    :project-dir site-root}]

    (println "Starting integrated Eden MCP server...")
    (println "Site EDN:" site-edn)
    (println "Project directory:" site-root)

    ;; Start the MCP server with our custom configuration
    (try
      (mcp-core/build-and-start-mcp-server
       mcp-config
       {:make-tools-fn make-minimal-tools
        :make-prompts-fn make-eden-prompts
        :make-resources-fn make-eden-resources})

      (println "\nEden MCP server ready!")
      (println "The AI assistant can now:")
      (println "  - Read/edit files in the project directory")
      (println "  - Evaluate Clojure code including (eden.core/build ...)")
      (println "  - Run git and build commands")
      (println "  - Access Eden documentation via resources")
      (println "  - Use Eden-specific prompts for common tasks")

      (catch Exception e
        (println "Error starting MCP server:" (.getMessage e))
        (.printStackTrace e)
        (stop-embedded-nrepl!)
        (throw e)))))

;; -main function removed - MCP server is started via eden.core/mcp-stdio
;; Shutdown handling should be done by the calling code if needed