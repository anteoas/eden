(ns eden.core
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [hawkeye.core :as hawk]
            [eden.config :as config]
            [eden.report :as report]
            [eden.mcp :as mcp]
            [eden.loader :as loader]
            [eden.pipeline :as pipeline]
            [eden.init :as init]))

(defn- find-available-port
  "Find an available port in the given range, or use port 0 to get any available port"
  ([]
   (find-available-port 0))
  ([min-port]
   (find-available-port min-port (+ min-port 1000)))
  ([min-port max-port]
   (if (zero? min-port)
     ;; Let the OS assign any available port
     (with-open [socket (java.net.ServerSocket. 0)]
       (.getLocalPort socket))
     ;; Try to find a port in the specified range
     (loop [port min-port]
       (if (>= port max-port)
         (throw (ex-info "No available port found in range"
                         {:min-port min-port :max-port max-port}))
         (if (try
               (with-open [_t (java.net.ServerSocket. port)]
                 true)
               (catch Exception _ false))
           port
           (recur (inc port))))))))

(defn- start-dev-server
  "Start browser-sync dev server"
  [output-dir]
  (println "Starting development server...")
  (let [proc (process/start
              {:out :inherit ; Send output to console
               :err :inherit ; Send errors to console
               :dir "."} ; Run from current dir
              "npx" "browser-sync" "start"
              "--server" output-dir
              "--files" (str output-dir "/**/*")
              "--no-notify"
              "--open" "false")]
    (println "Dev server starting at http://localhost:3000")
    ;; Return process so it can be managed
    proc))

(defn build
  "Build the site. Suitable for clj -X invocation."
  [& {:keys [site-edn output-dir mode] :or {site-edn "site.edn" mode :prod}}]
  (println "Building site from:" site-edn)

  ;; Compute the actual output path like loader does
  (let [site-edn-file (io/file site-edn)
        parent-file (or (.getParentFile site-edn-file) (io/file "."))
        site-root (str (fs/absolutize parent-file))
        actual-output-path (io/file site-root (or output-dir "dist"))
        ;; Make it relative to current dir for display
        cwd (fs/absolutize (io/file "."))
        relative-output (str (fs/relativize cwd (.toPath actual-output-path)))]
    (println "Output directory:" relative-output))

  (println "Build mode:" mode)

  (let [initial-ctx {:site-edn site-edn
                     :output-dir (or output-dir "dist")
                     :mode mode
                     :warnings {}
                     :timings {}
                     :results {}}
        final-ctx (-> initial-ctx
                      (pipeline/run-step pipeline/load-step :load)
                      (pipeline/run-step pipeline/build-html-step :build-html)
                      (pipeline/run-step pipeline/process-images-step :process-images)
                      (pipeline/run-step pipeline/bundle-assets-step :bundle-assets)
                      (pipeline/run-step pipeline/copy-static-step :copy-static)
                      (pipeline/run-step pipeline/write-output-step :write-output)
                      (pipeline/run-step pipeline/copy-processed-images-step :copy-processed-images))]

    (report/print-build-report final-ctx)

    ;; Write HTML report in dev mode
    (when (= mode :dev)
      (let [report-path (io/file (:output-dir final-ctx) "_report.html")
            report-html (report/generate-html-report final-ctx)]
        (io/make-parents report-path)
        (spit report-path report-html)
        (println (format "\n📊 Build report: %s" (.getPath report-path)))))

    (when (:error final-ctx)
      (throw (ex-info "Build failed" final-ctx)))

    (println "\nBuild complete!")))

(defn- start-watch
  "Start watching for file changes and rebuild on change.
   Returns a stop function."
  [& {:keys [site-edn output-dir mode] :or {output-dir "dist" mode :dev}}]
  (when-not site-edn
    (throw (ex-info "Missing required :site-edn parameter" {})))

  (println "Starting file watcher...")
  (let [site-dir (.getParent (io/file site-edn))
        debounced-build (hawk/debounce
                         (fn [event]
                           ;; With :events :last, we get the event directly, not in a collection
                           (println (format "\nChange detected: %s" (:path event)))
                           (try
                             (build :site-edn site-edn :output-dir output-dir :mode mode)
                             (catch Exception e
                               (println "Build failed:" (.getMessage e))
                               (.printStackTrace e))))
                         10
                         :events :last)]
    (hawk/watch
     site-dir
     debounced-build
     (fn [e _ctx]
       (println "Watch error:" (.getMessage ^Exception e))))))

(defn dev
  "Build and start dev server with file watching. Suitable for clj -X invocation.
   Example: clj -X eden.core/dev :site-edn '\"site/site.edn\"'"
  [& {:keys [site-edn output-dir] :or {site-edn "site.edn" output-dir "dist"}}]
  ;; Initial build
  (build :site-edn site-edn :output-dir output-dir :mode :dev)

  ;; Start watcher
  (let [stop-watch (start-watch :site-edn site-edn :output-dir output-dir :mode :dev)]
    (println "File watcher started.")

    ;; Start dev server - use absolute output path from site config
    (let [site-data (loader/load-site-data site-edn output-dir)
          output-path (:output-path (:config site-data))
          proc (start-dev-server output-path)]
      (println "Dev server running. Press Ctrl+C to stop.")
      (try
        (.waitFor ^Process proc)
        (finally
          (stop-watch)
          (println "Stopped file watcher."))))))

(defn clean
  "Clean build artifacts. Suitable for clj -T or -X invocation."
  [& {:keys [output-dir temp-dir site-edn] :or {site-edn "site.edn" output-dir "dist" temp-dir ".temp"}}]
  ;; Determine the base directory
  (let [base-dir (if (and site-edn (not= site-edn "site.edn"))
                   ;; If site-edn provided and not default, use its parent directory
                   (let [site-file (io/file site-edn)]
                     (when-not (.exists site-file)
                       (throw (ex-info "Site EDN file not found" {:path site-edn})))
                     (str (fs/absolutize (.getParentFile site-file))))
                   ;; Otherwise use current directory
                   (str (fs/absolutize ".")))
        dirs-to-clean [(io/file base-dir output-dir)
                       (io/file base-dir temp-dir)]]
    (doseq [dir dirs-to-clean]
      (when (.exists ^java.io.File dir)
        (fs/delete-tree dir)
        (println "✓ Cleaned" (.getPath ^java.io.File dir))))
    (println "Clean complete!")))

(defn init
  "Initialize a new Eden site in the current directory.
   Delegates to eden.init/create-site for the actual implementation."
  [_] ; Accept but ignore params for tool compatibility
  (init/create-site))

(defn mcp
  "Start MCP server in production mode with authentication"
  [& {:keys [site-edn port secret]
      :or {site-edn "site.edn"}}]
  (let [port (or port (find-available-port 3000 4000))
        secret (or secret (str (java.util.UUID/randomUUID)))]
    (println "Starting MCP server...")
    (println (str "MCP URL: http://localhost:" port "/mcp/" secret "/"))
    (println "Save this URL for connecting MCP clients")
    (println "For HTTPS, use a reverse proxy (nginx, caddy, etc.)")
    (mcp/start-server site-edn port secret)))

(defn mcp-dev
  "Start MCP server in development mode (no auth)"
  [& {:keys [site-edn port]
      :or {site-edn "site.edn"}}]
  (require '[eden.mcp :as mcp])

  ;; Build the site first
  (println "Building site...")
  (build :site-edn site-edn :mode :dev)

  ;; Find available ports for both servers
  (let [mcp-port (or port (find-available-port 3000 4000))
        browser-sync-port (find-available-port 3100 4000)
        browser-sync-proc (process/start
                           {:out :inherit
                            :err :inherit}
                           "npx" "browser-sync" "start"
                           "--server" "dist"
                           "--files" "dist/**/*"
                           "--port" (str browser-sync-port)
                           "--no-notify"
                           "--no-open")]
    ;; Start browser-sync
    (println "\nStarting MCP development server...")
    (println (str "MCP URL: http://localhost:" mcp-port "/mcp/"))
    (println (str "Browser-sync URL: http://localhost:" browser-sync-port))
    (println "⚠️  WARNING: No authentication - development only!")

    ;; Add shutdown hook to kill browser-sync when JVM exits
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (when (.isAlive browser-sync-proc)
                                   (.destroy browser-sync-proc)))))

    ;; Start MCP server with browser-sync port in config
    (mcp/start-dev-server-with-browser-sync
     site-edn mcp-port browser-sync-port)))

(defn mcp-stdio
  "Start MCP server in stdio mode (for Claude Desktop)"
  [& {:keys [site-edn]
      :or {site-edn "site.edn"}}]
  (require '[eden.mcp :as mcp])
  (mcp/start-stdio-server site-edn))

(defn help
  "Display Eden command help"
  [_]
  (println "Eden - Static Site Generator")
  (println)
  (println "Available commands:")
  (println "  clj -Teden init         - Initialize a new Eden site in current directory")
  (println "  clj -Teden build        - Build the site (production mode)")
  (println "  clj -Teden dev          - Start development server with file watching")
  (println "  clj -Teden clean        - Clean build artifacts")
  (println "  clj -Teden mcp          - Start MCP server (production mode with auth)")
  (println "  clj -Teden mcp-dev      - Start MCP server (dev mode, no auth)")
  (println "  clj -Teden mcp-stdio    - Start MCP server in stdio mode (for Claude Desktop)")
  (println "  clj -Teden help         - Show this help message")
  (println)
  (println "Common options:")
  (println "  :site-edn '\"path\"'     - Path to site.edn (default: \"site.edn\")")
  (println "  :mode '\"dev/prod\"'     - Build mode (build command only)")
  (println)
  (println "MCP options:")
  (println "  :port '3000'           - Port for MCP server (auto-assigned if not specified)")
  (println "  :secret '\"uuid\"'       - Authentication secret (mcp command, auto-generated if not specified)")
  (println)
  (println "Examples:")
  (println "  clj -Teden build :site-edn '\"site.edn\"' :mode '\"prod\"'")
  (println "  clj -Teden dev :site-edn '\"mysite/site.edn\"'")
  (println "  clj -Teden mcp-dev                        - Start with auto-assigned ports")
  (println "  clj -Teden mcp-dev :port '3000'           - Start with specific port")
  (println)
  (println "MCP stdio mode (for Claude Desktop):")
  (println "  clj -Teden mcp-stdio                      - Run as stdio server"))

(defn -main
  "CLI entry point for site generator.
   Usage: clojure -M:run path/to/site.edn [options]
   Options:
     --output-dir PATH  Output directory (default: dist)
     --mode MODE        Build mode: dev or prod (default: prod)
     --serve            Start dev server after build
     --clean            Clean build artifacts"
  [& args]
  (let [parsed (config/parse-args args)]
    (cond
      ;; Clean command
      (some #{"--clean"} args)
      (clean :site-edn (:site-edn parsed)
             :output-dir (:output-dir parsed))

      ;; Build/serve commands
      (:site-edn parsed)
      (if (:serve parsed)
        (dev :site-edn (:site-edn parsed)
             :output-dir (:output-dir parsed))
        (build :site-edn (:site-edn parsed)
               :output-dir (:output-dir parsed)
               :mode (or (:mode parsed) :prod)))

      ;; No site-edn provided
      :else
      (do
        (println "Usage: clojure -M:run path/to/site.edn [options]")
        (println "Options:")
        (println "  --output-dir PATH  Output directory (default: dist)")
        (println "  --mode MODE        Build mode: dev or prod (default: prod)")
        (println "  --serve            Start dev server after build (implies --mode dev)")
        (println "  --clean            Clean build artifacts")
        (System/exit 1)))))

(comment
  ;; Start watching
  (def stop (start-watch :site-edn "site/site.edn" :output-dir "dist"))

  ;; When done, stop it
  (stop)

  (clean nil)
  (build :site-edn "site/site.edn" :mode :dev)
  (dev :site-edn "site/site.edn"))
